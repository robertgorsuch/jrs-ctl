package com.jaspersoft.jrsctl.ops.upgrade;

import com.jaspersoft.jrsctl.core.JrsctlHome;
import com.jaspersoft.jrsctl.core.platform.Platform;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Where one upgrade run keeps its rollback-point-B artefacts under {@code snapshots/<runId>/}: the
 * full export, the webapp and buildomatic archives, and the {@code SnapshotStore} entries for the
 * keystore and the configuration files. Invariant: every path is derived from the run id alone, so
 * a rollback plan built later from the same id finds the same files.
 */
record SnapshotSet(Path dir, Platform.OsFamily os) {

  static final String FULL_EXPORT = "full-export.zip";
  static final String KEYSTORE_STEP = "backup-keystore";
  static final String CONFIG_STEP = "backup-config";
  static final String ARCHIVES_DIR = "backup-webapp";
  static final String WEBAPP_ARCHIVE = "webapp";
  static final String BUILDOMATIC_ARCHIVE = "buildomatic";
  static final String MANIFEST = "upgrade.json";
  static final String SHA_SUFFIX = ".sha256";

  SnapshotSet {
    Objects.requireNonNull(dir, "dir");
    Objects.requireNonNull(os, "os");
  }

  static SnapshotSet of(JrsctlHome home, String runId, Platform.OsFamily os) {
    return new SnapshotSet(home.snapshots().resolve(runId).toAbsolutePath().normalize(), os);
  }

  static Path placeholder(JrsctlHome home) {
    return home.snapshots().resolve("{runId}");
  }

  Path fullExport() {
    return dir.resolve(FULL_EXPORT);
  }

  Path archivesDir() {
    return dir.resolve(ARCHIVES_DIR);
  }

  Path webappArchive() {
    return archivesDir().resolve(WEBAPP_ARCHIVE + Archives.extension(os));
  }

  Path buildomaticArchive() {
    return archivesDir().resolve(BUILDOMATIC_ARCHIVE + Archives.extension(os));
  }

  Path manifest() {
    return dir.resolve(MANIFEST);
  }

  static Path shaFileFor(Path archive) {
    return archive.resolveSibling(archive.getFileName() + SHA_SUFFIX);
  }
}
