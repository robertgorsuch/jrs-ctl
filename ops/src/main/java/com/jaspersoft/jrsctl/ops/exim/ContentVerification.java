package com.jaspersoft.jrsctl.ops.exim;

import com.jaspersoft.jrsctl.jrs.api.JrsAdapter;
import com.jaspersoft.jrsctl.jrs.api.JrsUnreachableException;
import com.jaspersoft.jrsctl.jrs.api.ResourceFields;
import com.jaspersoft.jrsctl.jrs.strategy.ArchiveContent;
import com.jaspersoft.jrsctl.ops.Services;
import com.jaspersoft.jrsctl.ops.exim.ImportVerifyReport.Difference;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * The {@code content} comparison of {@code import verify --content} (issue #307, ADR-0060): every
 * file the archive holds against the server's copy by SHA-256, and for every other top-level
 * resource its label and description, plus a report unit's data source and input controls, against
 * the server's descriptor. Invariants: read-only; one request at a time, in URI order; a download
 * goes to a temporary file under the jrsctl home that is deleted before the next one, and is never
 * held in memory; versions, times, permissions and passwords are never compared, because an import
 * rewrites them; a server that cannot be reached ends the comparison with {@link
 * JrsUnreachableException}, while any other failure on one resource is recorded for that resource
 * and the comparison continues.
 */
final class ContentVerification {

  static final String CONTENT_DIFFERS = "content differs";
  static final String NOT_ON_SERVER = "not on the server";

  private final Services services;

  ContentVerification(Services services) {
    this.services = Objects.requireNonNull(services, "services");
  }

  /**
   * Whether {@code uri} lies under {@code prefix} ({@code /a} takes {@code /a} and {@code /a/b}).
   */
  static Predicate<String> under(Optional<String> prefix) {
    if (prefix.isEmpty()) {
      return uri -> true;
    }
    String p = normalize(prefix.get());
    return uri -> p.equals("/") || uri.equals(p) || uri.startsWith(p + "/");
  }

  /** A leading {@code /}, no trailing one. */
  static String normalize(String prefix) {
    String p = prefix.strip();
    if (!p.startsWith("/")) {
      p = "/" + p;
    }
    while (p.length() > 1 && p.endsWith("/")) {
      p = p.substring(0, p.length() - 1);
    }
    return p;
  }

  ImportVerifyReport.Content compare(Path archive, Optional<String> prefix) throws IOException {
    ArchiveContent content = ArchiveContent.read(archive, under(prefix));
    JrsAdapter adapter = services.adapter().get();
    List<Difference> differences = new ArrayList<>();
    Path scratch = Files.createDirectories(services.home().nativeTemp());
    for (Map.Entry<String, String> file : content.fileSha256().entrySet()) {
      fileDifference(adapter, scratch, file.getKey(), file.getValue()).ifPresent(differences::add);
    }
    for (Map.Entry<String, ArchiveContent.Descriptor> d : content.descriptors().entrySet()) {
      differences.addAll(descriptorDifferences(adapter, d.getKey(), d.getValue()));
    }
    differences.sort(
        java.util.Comparator.comparing(Difference::uri).thenComparing(Difference::what));
    return new ImportVerifyReport.Content(
        content.fileSha256().size(), content.descriptors().size(), differences);
  }

  private Optional<Difference> fileDifference(
      JrsAdapter adapter, Path scratch, String uri, String archiveSha) throws IOException {
    Path tmp = Files.createTempFile(scratch, "verify-", ".data");
    try {
      Optional<Path> fetched;
      try {
        fetched = adapter.downloadResource(uri, tmp);
      } catch (JrsUnreachableException e) {
        throw e;
      } catch (RuntimeException e) {
        return Optional.of(new Difference(uri, "could not be read: " + e.getMessage()));
      }
      if (fetched.isEmpty()) {
        return Optional.of(new Difference(uri, NOT_ON_SERVER));
      }
      String serverSha = services.platform().files().sha256(tmp);
      return serverSha.equalsIgnoreCase(archiveSha)
          ? Optional.empty()
          : Optional.of(new Difference(uri, CONTENT_DIFFERS));
    } finally {
      Files.deleteIfExists(tmp);
    }
  }

  private static List<Difference> descriptorDifferences(
      JrsAdapter adapter, String uri, ArchiveContent.Descriptor archived) {
    Optional<ResourceFields> server;
    try {
      server = adapter.resourceFields(uri, archived.file());
    } catch (JrsUnreachableException e) {
      throw e;
    } catch (RuntimeException e) {
      return List.of(new Difference(uri, "could not be read: " + e.getMessage()));
    }
    if (server.isEmpty()) {
      return List.of(new Difference(uri, NOT_ON_SERVER));
    }
    ResourceFields a = archived.fields();
    ResourceFields s = server.get();
    List<Difference> out = new ArrayList<>();
    if (!a.label().equals(s.label())) {
      out.add(new Difference(uri, "label"));
    }
    if (!a.description().equals(s.description())) {
      out.add(new Difference(uri, "description"));
    }
    // REST describes these two consistently for report units only: an Ad Hoc view's input
    // controls, for one, are not in its descriptor (verified on 10.0.0, ADR-0060)
    if (archived.reportUnit()) {
      if (!a.dataSource().equals(s.dataSource())) {
        out.add(new Difference(uri, "data source"));
      }
      if (!a.inputControls().equals(s.inputControls())) {
        out.add(new Difference(uri, "input controls"));
      }
    }
    return out;
  }
}
