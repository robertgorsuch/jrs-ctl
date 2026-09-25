package com.jaspersoft.jrsctl.ops.hotfix;

import com.jaspersoft.jrsctl.core.platform.FileOps;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The precheck behind issue #157: a file a hotfix swap or restore writes is created by this
 * process, and when this account may not give it back its previous owner, the swap used to leave it
 * owned by this account and still succeed. Invariants: files are grouped by their current owner and
 * one file per owner is probed through {@link FileOps#canRestoreOwner}, so a package of hundreds of
 * files costs one probe per owner; nothing on disk changes; missing files and files whose owner
 * cannot be read are skipped, since there is nothing to restore for them.
 */
final class OwnerRestore {

  private OwnerRestore() {}

  /** A problem line naming every owner that would be lost, or empty when none would be. */
  static Optional<String> problem(FileOps files, Collection<Path> existing) {
    Map<String, List<Path>> byOwner = new LinkedHashMap<>();
    for (Path p : existing) {
      if (!Files.isRegularFile(p)) {
        continue;
      }
      try {
        byOwner.computeIfAbsent(Files.getOwner(p).getName(), k -> new ArrayList<>()).add(p);
      } catch (IOException | UnsupportedOperationException e) {
        // no owner to restore
      }
    }
    List<String> lost = new ArrayList<>();
    for (Map.Entry<String, List<Path>> e : byOwner.entrySet()) {
      List<Path> paths = e.getValue();
      if (!files.canRestoreOwner(paths.get(0))) {
        lost.add(e.getKey() + " (" + paths.size() + " file(s), for example " + paths.get(0) + ")");
      }
    }
    if (lost.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(
        "this account cannot give the files it writes back to their owner "
            + String.join(", ", lost)
            + ", so they would end up owned by this account; run jrsctl elevated (an"
            + " administrator prompt on Windows, root on Linux) or as that owner");
  }
}
