package com.jaspersoft.jrsctl.ops.exim;

import com.jaspersoft.jrsctl.jrs.api.JrsAdapter;
import com.jaspersoft.jrsctl.jrs.strategy.ExportArchiveInventory;
import com.jaspersoft.jrsctl.ops.ReportItem;
import com.jaspersoft.jrsctl.ops.Services;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * {@code jrsctl import verify <archive>} (issue #262, spec §9.4): is everything the archive holds
 * on the server? The archive's organisations, users, roles, folders and resources come from its own
 * layout ({@link ExportArchiveInventory}); the server answers over REST: one request per
 * organisation, one listing each for users and roles, and the recursive resource listing of the
 * root, the same one the import's pre-import listing takes. Invariants: read-only, a plain function
 * returning a report (spec §0); an item is SKIP when the archive has nothing of that kind, PASS
 * when every entry is on the server, FAIL naming what is missing (the first few in the item, all of
 * them in the report's {@code missing} map) or FAIL naming the server's error when it could not be
 * asked; the archive is refused, not reported, when it is not an export; the comparison is by id,
 * never by content.
 */
public final class ImportVerification {

  static final String ARCHIVE = "archive";
  static final String ORGANIZATIONS = "organizations";
  static final String USERS = "users";
  static final String ROLES = "roles";
  static final String RESOURCES = "resources";
  static final String CONTENT = "content";

  /**
   * What {@code import verify} compares beyond the ids: with {@code content}, file payloads and key
   * descriptor fields (issue #307, ADR-0060), limited to {@code uriPrefix} when given.
   */
  public record Options(boolean content, Optional<String> uriPrefix) {
    public Options {
      Objects.requireNonNull(uriPrefix, "uriPrefix");
      uriPrefix = uriPrefix.map(String::strip).filter(p -> !p.isEmpty());
    }

    /** The id comparison only. */
    public static Options idsOnly() {
      return new Options(false, Optional.empty());
    }
  }

  /** How many missing ids an item's detail names before saying "and N more". */
  public static final int NAMED = 5;

  private final Services services;

  public ImportVerification(Services services) {
    this.services = Objects.requireNonNull(services, "services");
  }

  public ImportVerifyReport verify(Path archive) {
    return verify(archive, Options.idsOnly());
  }

  public ImportVerifyReport verify(Path archive, Options options) {
    Objects.requireNonNull(archive, "archive");
    Objects.requireNonNull(options, "options");
    Path file = archive.toAbsolutePath().normalize();
    if (!Files.isRegularFile(file)) {
      throw new IllegalArgumentException("archive " + file + " does not exist");
    }
    ExportArchiveInventory inventory;
    try {
      inventory = ExportArchiveInventory.read(file);
    } catch (IOException e) {
      throw new UncheckedIOException("cannot read " + file + ": " + e.getMessage(), e);
    }
    JrsAdapter adapter = services.adapter().get();
    List<ReportItem> items = new ArrayList<>();
    Map<String, List<String>> missing = new LinkedHashMap<>();
    ImportVerifyReport.ArchiveCounts counts =
        new ImportVerifyReport.ArchiveCounts(
            inventory.organizations().size(),
            inventory.users().size(),
            inventory.roles().size(),
            inventory.folders().size(),
            inventory.resources().size());
    items.add(
        ReportItem.pass(
            ARCHIVE,
            file.getFileName()
                + " holds "
                + plural(counts.organizations(), "organisation")
                + ", "
                + plural(counts.users(), "user")
                + ", "
                + plural(counts.roles(), "role")
                + ", "
                + plural(counts.folders(), "folder")
                + " and "
                + plural(counts.resources(), "resource")));

    items.add(
        compare(
            ORGANIZATIONS,
            inventory.organizations(),
            () -> {
              List<String> absent = new ArrayList<>();
              for (String id : inventory.organizations()) {
                if (!adapter.organizationExists(id)) {
                  absent.add(id);
                }
              }
              return absent;
            },
            "GET /rest_v2/organizations/<id>",
            missing));
    items.add(
        compare(
            USERS,
            inventory.users(),
            () -> absent(inventory.users(), adapter.listUserIds()),
            "GET /rest_v2/users",
            missing));
    items.add(
        compare(
            ROLES,
            inventory.roles(),
            () -> absent(inventory.roles(), adapter.listRoleNames()),
            "GET /rest_v2/roles",
            missing));
    List<String> all = new ArrayList<>(inventory.folders());
    all.addAll(inventory.resources());
    items.add(
        compare(
            RESOURCES,
            all,
            () -> absent(all, adapter.listTree("/")),
            "GET /rest_v2/resources?folderUri=/&recursive=true",
            missing));
    Optional<ImportVerifyReport.Content> content = Optional.empty();
    if (options.content()) {
      content = content(file, options.uriPrefix(), items);
    }
    return ImportVerifyReport.of(items, file.toString(), counts, missing, content);
  }

  /**
   * The {@code content} item (issue #307, ADR-0060): SKIP when nothing is in scope, FAIL naming the
   * first few differences or the server's error, PASS otherwise.
   */
  private Optional<ImportVerifyReport.Content> content(
      Path file, Optional<String> prefix, List<ReportItem> items) {
    String scope = prefix.map(p -> " under " + ContentVerification.normalize(p)).orElse("");
    ImportVerifyReport.Content result;
    try {
      result = new ContentVerification(services).compare(file, prefix);
    } catch (IOException e) {
      throw new UncheckedIOException("cannot read " + file + ": " + e.getMessage(), e);
    } catch (RuntimeException e) {
      items.add(
          ReportItem.fail(
              CONTENT,
              "the server could not be asked for the content" + scope + ": " + e.getMessage(),
              "check that GET /rest_v2/resources answers for the configured account, then run"
                  + " again"));
      return Optional.empty();
    }
    String compared =
        plural(result.files(), "file")
            + " and "
            + plural(result.descriptors(), "descriptor")
            + scope;
    if (result.files() + result.descriptors() == 0) {
      items.add(
          ReportItem.skip(CONTENT, "the archive holds no resource" + scope, "nothing to compare"));
    } else if (result.differences().isEmpty()) {
      items.add(
          ReportItem.pass(
              CONTENT,
              compared
                  + " identical (files by SHA-256; label and description, and a report unit's"
                  + " data source and input controls)"));
    } else {
      List<ImportVerifyReport.Difference> d = result.differences();
      List<String> named =
          d.subList(0, Math.min(NAMED, d.size())).stream()
              .map(x -> x.uri() + " (" + x.what() + ")")
              .toList();
      items.add(
          ReportItem.fail(
              CONTENT,
              d.size()
                  + " difference(s) in "
                  + compared
                  + ": "
                  + String.join(", ", named)
                  + (d.size() > named.size() ? " and " + (d.size() - named.size()) + " more" : ""),
              "compare those resources on the server with the archive; with --uris the JSON report"
                  + " lists every difference"));
    }
    return Optional.of(result);
  }

  private static List<String> absent(List<String> wanted, List<String> present) {
    Set<String> have = new HashSet<>(present);
    List<String> out = new ArrayList<>();
    for (String id : wanted) {
      if (!have.contains(id)) {
        out.add(id);
      }
    }
    return out;
  }

  /**
   * One item: SKIP when the archive has nothing of the kind, FAIL when the server could not be
   * asked or lacks something, PASS otherwise; the missing ids go to {@code missing} in full.
   */
  private static ReportItem compare(
      String kind,
      List<String> wanted,
      Supplier<List<String>> absent,
      String call,
      Map<String, List<String>> missing) {
    if (wanted.isEmpty()) {
      missing.put(kind, List.of());
      return ReportItem.skip(kind, "the archive holds no " + kind, "nothing to compare");
    }
    List<String> gone;
    try {
      gone = absent.get();
    } catch (RuntimeException e) {
      missing.put(kind, List.of());
      return ReportItem.fail(
          kind,
          "the server could not be asked for its " + kind + ": " + e.getMessage(),
          "check that " + call + " answers for the configured account, then run again");
    }
    missing.put(kind, List.copyOf(gone));
    if (gone.isEmpty()) {
      return ReportItem.pass(kind, "all " + wanted.size() + " on the server");
    }
    List<String> named = gone.subList(0, Math.min(NAMED, gone.size()));
    return ReportItem.fail(
        kind,
        gone.size()
            + " of "
            + wanted.size()
            + " missing: "
            + String.join(", ", named)
            + (gone.size() > named.size() ? " and " + (gone.size() - named.size()) + " more" : ""),
        "read the import's log (jrsctl runs show <id>) for the resources it skipped; with --uris"
            + " the JSON report lists every missing id");
  }

  private static String plural(int n, String word) {
    return n + " " + word + (n == 1 ? "" : "s");
  }
}
