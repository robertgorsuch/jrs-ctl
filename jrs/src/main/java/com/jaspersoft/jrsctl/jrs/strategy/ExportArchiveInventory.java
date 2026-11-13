package com.jaspersoft.jrsctl.jrs.strategy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * What an export archive holds, read from the layout {@code js-export} writes (issue #262):
 * organisations as {@code organizations/<id>.xml} (the root tenant's own descriptor left out),
 * users as {@code users/[<org>/]<name>.xml} and roles as {@code roles/[<org>/]<name>.xml} (recorded
 * as {@code name|org} for an organisation's, {@code name} for the root's, the way the REST
 * directory services report them), folders by their {@code resources/<path>/.folder.xml} marker and
 * resources by their {@code resources/<path>/<id>.xml} descriptor, with the {@code <id>_files/}
 * data beside a descriptor left out. Every name is decoded from the vendor's path escaping, where a
 * character outside js-export's safe set is written as {@code -} and four hex digits ({@code -}
 * itself as {@code -002d}, #314), segment by segment, so a decoded {@code /} never splits a path.
 * Invariants: the archive is read as a stream of entry names and never held in memory; every list
 * is sorted and immutable; an archive without {@code index.xml} is refused with an {@link
 * IllegalArgumentException}, never read as empty; nothing here asks a server.
 */
public record ExportArchiveInventory(
    List<String> organizations,
    List<String> users,
    List<String> roles,
    List<String> folders,
    List<String> resources) {

  static final String ROOT_TENANT = "organizations";
  private static final String FOLDER_MARKER = ".folder.xml";
  private static final String DATA_SUFFIX = "_files/";
  private static final Pattern ESCAPE = Pattern.compile("-([0-9a-fA-F]{4})");

  public ExportArchiveInventory {
    organizations = List.copyOf(organizations);
    users = List.copyOf(users);
    roles = List.copyOf(roles);
    folders = List.copyOf(folders);
    resources = List.copyOf(resources);
  }

  /** Reads the inventory out of {@code archive}. */
  public static ExportArchiveInventory read(Path archive) throws IOException {
    Objects.requireNonNull(archive, "archive");
    TreeSet<String> organizations = new TreeSet<>();
    TreeSet<String> users = new TreeSet<>();
    TreeSet<String> roles = new TreeSet<>();
    TreeSet<String> folders = new TreeSet<>();
    TreeSet<String> resources = new TreeSet<>();
    boolean index = false;
    try (InputStream in = Files.newInputStream(archive);
        ZipInputStream zip = new ZipInputStream(in)) {
      ZipEntry entry;
      while ((entry = zip.getNextEntry()) != null) {
        if (entry.isDirectory()) {
          continue;
        }
        String name = entry.getName();
        if (name.equals(ExportArchives.INDEX)) {
          index = true;
        } else if (name.startsWith("organizations/")) {
          String id = stripXml(name.substring("organizations/".length()));
          if (id != null && !id.contains("/") && !id.equals(ROOT_TENANT)) {
            organizations.add(unescape(id));
          }
        } else if (name.startsWith("users/")) {
          principal(name.substring("users/".length()), users);
        } else if (name.startsWith("roles/")) {
          principal(name.substring("roles/".length()), roles);
        } else if (name.startsWith("resources/")) {
          String rel = name.substring("resources/".length());
          if (rel.contains(DATA_SUFFIX)) {
            continue;
          }
          if (rel.endsWith(FOLDER_MARKER)) {
            String folder = rel.substring(0, rel.length() - FOLDER_MARKER.length());
            if (!folder.isEmpty()) {
              folders.add("/" + unescapePath(stripSlash(folder)));
            }
          } else {
            String id = stripXml(rel);
            if (id != null) {
              resources.add("/" + unescapePath(id));
            }
          }
        }
      }
    }
    if (!index) {
      throw new IllegalArgumentException(
          archive + " holds no " + ExportArchives.INDEX + ": not a JasperReports Server export");
    }
    return new ExportArchiveInventory(
        List.copyOf(organizations),
        List.copyOf(users),
        List.copyOf(roles),
        List.copyOf(folders),
        List.copyOf(resources));
  }

  /** {@code <name>.xml} to {@code name}, {@code <org>/<name>.xml} to {@code name|org}. */
  private static void principal(String rel, TreeSet<String> into) {
    String id = stripXml(rel);
    if (id == null) {
      return;
    }
    int slash = id.indexOf('/');
    if (slash < 0) {
      into.add(unescape(id));
    } else if (id.indexOf('/', slash + 1) < 0) {
      into.add(unescape(id.substring(slash + 1)) + "|" + unescape(id.substring(0, slash)));
    }
  }

  /** Decodes every {@code -hhhh} in one path segment; a dash without four hex digits stays. */
  static String unescape(String segment) {
    if (segment.indexOf('-') < 0) {
      return segment;
    }
    Matcher m = ESCAPE.matcher(segment);
    StringBuilder out = new StringBuilder(segment.length());
    while (m.find()) {
      m.appendReplacement(
          out, Matcher.quoteReplacement(Character.toString(Integer.parseInt(m.group(1), 16))));
    }
    m.appendTail(out);
    return out.toString();
  }

  /** {@link #unescape} applied to each {@code /}-separated segment of an archive path. */
  private static String unescapePath(String path) {
    String[] segments = path.split("/", -1);
    StringBuilder out = new StringBuilder(path.length());
    for (int i = 0; i < segments.length; i++) {
      if (i > 0) {
        out.append('/');
      }
      out.append(unescape(segments[i]));
    }
    return out.toString();
  }

  private static String stripXml(String name) {
    return name.endsWith(".xml") ? name.substring(0, name.length() - ".xml".length()) : null;
  }

  private static String stripSlash(String s) {
    return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
  }
}
