package com.jaspersoft.jrsctl.ops.customizations;

import com.jaspersoft.jrsctl.core.platform.FileOps;
import com.jaspersoft.jrsctl.ops.customizations.CustomizationOperations.ScanEntry;
import com.jaspersoft.jrsctl.ops.customizations.CustomizationOperations.TargetChange;
import com.jaspersoft.jrsctl.ops.customizations.CustomizationOperations.TargetClass;
import com.jaspersoft.jrsctl.ops.customizations.CustomizationOperations.TargetEntry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Classifies the files of a {@code customizations scan} against the target version's vendor webapp
 * and writes the three-way merge inputs (issue #305, ADR-0057). Invariants: the class of a file
 * depends only on its scan change and on how the vendor's file at that path changed from the source
 * to the target version, both compared by SHA-256; {@code scripts/} is always {@link
 * TargetClass#OVERLAY} and an installer-written file always {@link TargetClass#INSTALLER}; a war is
 * never unpacked, only the entries a merge needs are copied out; nothing is written without an
 * output directory, and an output directory that exists and is not empty is refused before anything
 * is written; every written file stays under the output directory.
 */
final class TargetClassifier {

  static final String MINE = ".mine";
  static final String BASE = ".base";
  static final String THEIRS = ".theirs";

  private TargetClassifier() {}

  /** How the vendor's file at {@code rel} changed from {@code source} to {@code target}. */
  static TargetChange change(String rel, Map<String, String> source, Map<String, String> target) {
    String s = source.get(rel);
    String t = target.get(rel);
    if (s != null && t != null) {
      return s.equals(t) ? TargetChange.UNCHANGED : TargetChange.CHANGED;
    }
    if (s != null) {
      return TargetChange.REMOVED;
    }
    return t != null ? TargetChange.ADDED : TargetChange.ABSENT;
  }

  /** The class of one scan entry, given how the vendor's file at its path changed. */
  static TargetClass classify(ScanEntry entry, TargetChange target) {
    if (entry.relativePath().startsWith(CustomizationOperations.SCRIPTS_PREFIX)) {
      return TargetClass.OVERLAY;
    }
    return switch (entry.change()) {
      case INSTALLER -> TargetClass.INSTALLER;
      case REMOVED -> TargetClass.REMOVED;
      case ADDED -> target == TargetChange.ADDED ? TargetClass.CLASH : TargetClass.OWN;
      case CHANGED ->
          switch (target) {
            case UNCHANGED -> TargetClass.CARRY;
            case REMOVED, ABSENT -> TargetClass.OBSOLETE;
            case CHANGED, ADDED -> TargetClass.MERGE;
          };
    };
  }

  /** Every entry of {@code entries} classified, in class order, then by path. */
  static List<TargetEntry> classifyAll(
      List<ScanEntry> entries, Path sourceVendor, Path targetVendor, FileOps files)
      throws IOException {
    Map<String, String> source = WebappScanner.sha256s(sourceVendor, files);
    Map<String, String> target = WebappScanner.sha256s(targetVendor, files);
    List<TargetEntry> out = new ArrayList<>();
    for (ScanEntry e : entries) {
      TargetChange change = change(e.relativePath(), source, target);
      out.add(new TargetEntry(e, classify(e, change), change, List.of()));
    }
    out.sort(
        Comparator.comparing(TargetEntry::targetClass)
            .thenComparing(t -> t.entry().relativePath()));
    return out;
  }

  /**
   * Writes {@code <path>.mine} (the site's file), {@code .base} (the source vendor's) and {@code
   * .theirs} (the target vendor's) under {@code out} for every {@link TargetClass#MERGE} entry, and
   * returns the entries with their written files.
   */
  static List<TargetEntry> writeMerges(
      List<TargetEntry> entries, Path sourceVendor, Path targetVendor, Path out)
      throws IOException {
    Path root = out.toAbsolutePath().normalize();
    refuseNonEmpty(root);
    Files.createDirectories(root);
    Map<String, Path> base = new LinkedHashMap<>();
    Map<String, Path> theirs = new LinkedHashMap<>();
    List<TargetEntry> result = new ArrayList<>();
    for (TargetEntry t : entries) {
      if (t.targetClass() != TargetClass.MERGE) {
        result.add(t);
        continue;
      }
      String rel = t.entry().relativePath();
      Path mine = under(root, rel + MINE);
      Files.createDirectories(mine.getParent());
      Files.copy(t.entry().installed().orElseThrow(), mine);
      Path b = under(root, rel + BASE);
      Path th = under(root, rel + THEIRS);
      base.put(rel, b);
      theirs.put(rel, th);
      result.add(new TargetEntry(t.entry(), t.targetClass(), t.target(), List.of(mine, b, th)));
    }
    Set<String> noBase = WebappScanner.extract(sourceVendor, base);
    Set<String> noTheirs = WebappScanner.extract(targetVendor, theirs);
    if (!noBase.isEmpty() || !noTheirs.isEmpty()) {
      // the scan just found them by hash, so a miss means a vendor copy changed under us
      throw new IOException(
          "the vendor copies changed while scanning; missing "
              + String.join(", ", noBase)
              + (noBase.isEmpty() || noTheirs.isEmpty() ? "" : ", ")
              + String.join(", ", noTheirs));
    }
    return result;
  }

  private static void refuseNonEmpty(Path root) throws IOException {
    if (!Files.exists(root)) {
      return;
    }
    if (!Files.isDirectory(root)) {
      throw new CustomizationException(
          root + " is a file, not a directory", "name a new or empty directory with --out");
    }
    try (Stream<Path> children = Files.list(root)) {
      if (children.findAny().isPresent()) {
        throw new CustomizationException(
            root + " is not empty; --out never overwrites", "name a new or empty directory");
      }
    }
  }

  /** {@code root/rel}, refused when {@code rel} would leave {@code root}. */
  private static Path under(Path root, String rel) {
    Path p = root.resolve(rel).normalize();
    if (!p.startsWith(root)) {
      throw new CustomizationException(
          "refusing to write " + rel + " outside " + root, "report this path as a bug");
    }
    return p;
  }
}
