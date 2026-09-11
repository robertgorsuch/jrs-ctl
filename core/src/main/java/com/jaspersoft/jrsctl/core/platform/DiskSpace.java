package com.jaspersoft.jrsctl.core.platform;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Free-space arithmetic per volume (review finding 1.16). Invariants: needs are grouped by the
 * volume {@link FileOps#volumeId} reports for them (implementations resolve a directory that does
 * not exist yet through its nearest existing ancestor, so staging is measured where it will be
 * created); every volume must hold the sum of its needs plus {@link #MARGIN_BYTES}; nothing here
 * mutates.
 */
public final class DiskSpace {

  /** Kept free on every volume beyond what the operation itself needs. */
  public static final long MARGIN_BYTES = 64L << 20;

  /** {@code bytes} that {@code what} will put under {@code under}. */
  public record Need(String what, Path under, long bytes) {}

  private DiskSpace() {}

  /** One problem per volume that cannot hold its needs, or per path whose volume is unknown. */
  public static List<String> problems(FileOps files, List<Need> needs) {
    List<String> problems = new ArrayList<>();
    Map<String, List<Need>> byVolume = new LinkedHashMap<>();
    Map<String, Path> probes = new LinkedHashMap<>();
    for (Need need : needs) {
      Path probe = need.under();
      try {
        String volume = files.volumeId(probe);
        byVolume.computeIfAbsent(volume, v -> new ArrayList<>()).add(need);
        probes.putIfAbsent(volume, probe);
      } catch (IOException e) {
        problems.add("cannot determine free space under " + need.under() + ": " + e.getMessage());
      }
    }
    for (Map.Entry<String, List<Need>> volume : byVolume.entrySet()) {
      long total = MARGIN_BYTES;
      StringBuilder parts = new StringBuilder();
      for (Need need : volume.getValue()) {
        total += need.bytes();
        parts.append(need.what()).append(' ').append(need.bytes()).append(" + ");
      }
      parts.append("margin ").append(MARGIN_BYTES);
      try {
        long free = files.freeSpaceBytes(probes.get(volume.getKey()));
        if (free < total) {
          problems.add(
              "volume "
                  + volume.getKey()
                  + ": "
                  + free
                  + " bytes free, need "
                  + total
                  + " ("
                  + parts
                  + ")");
        }
      } catch (IOException e) {
        problems.add(
            "cannot determine free space on volume " + volume.getKey() + ": " + e.getMessage());
      }
    }
    return List.copyOf(problems);
  }

  /** Bytes in the regular files under {@code root}; a file is its own size, a missing path is 0. */
  public static long treeBytes(Path root) throws IOException {
    if (Files.isRegularFile(root)) {
      return Files.size(root);
    }
    if (!Files.isDirectory(root)) {
      return 0;
    }
    long[] total = {0};
    Files.walkFileTree(
        root,
        new SimpleFileVisitor<>() {
          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
            if (attrs.isRegularFile()) {
              total[0] += attrs.size();
            }
            return FileVisitResult.CONTINUE;
          }
        });
    return total[0];
  }
}
