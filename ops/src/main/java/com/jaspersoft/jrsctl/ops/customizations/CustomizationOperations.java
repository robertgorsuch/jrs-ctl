package com.jaspersoft.jrsctl.ops.customizations;

import com.jaspersoft.jrsctl.core.state.Customization;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Registry of operator-customised files (spec §10.3): registering snapshots the file and records
 * its hash as "original", so that an upgrade can decide between automatic re-application and a
 * reported conflict (spec §10.2 step 13). Invariants: only files under {@code server.installDir} or
 * {@code server.tomcatDir} are accepted; a registered customization's snapshot is
 * retention-protected until it is unregistered; every mutation is audited; nothing here touches the
 * server.
 */
public interface CustomizationOperations {

  /** Result of {@link #diff(Path)}: the registered copy against the file on disk. */
  record Diff(
      Path path,
      String originalSha256,
      String registeredSha256,
      String currentSha256,
      boolean identical,
      List<String> lines) {
    public Diff {
      Objects.requireNonNull(path, "path");
      Objects.requireNonNull(originalSha256, "originalSha256");
      Objects.requireNonNull(registeredSha256, "registeredSha256");
      Objects.requireNonNull(currentSha256, "currentSha256");
      lines = List.copyOf(lines);
    }
  }

  /** Registers with the file's current hash as the "original" (spec §10.3). */
  default Customization register(Path path) {
    return register(path, java.util.Optional.empty());
  }

  /**
   * Registers {@code path}; when {@code pristineCopy} names a file (for example the vendor's
   * unmodified version from the distribution) its hash is recorded as the "original" so the
   * upgrade's 3-way comparison can tell "unchanged by the vendor" from "customized".
   */
  Customization register(Path path, java.util.Optional<Path> pristineCopy);

  boolean unregister(Path path);

  List<Customization> list();

  Diff diff(Path path);
}
