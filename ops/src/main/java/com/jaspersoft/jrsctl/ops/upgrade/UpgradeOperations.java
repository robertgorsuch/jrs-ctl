package com.jaspersoft.jrsctl.ops.upgrade;

import com.jaspersoft.jrsctl.core.engine.Plan;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * Upgrade orchestration entry points (spec §10). Invariants: planning never mutates the server or
 * the installation (it may read the target package and the state store); every returned {@link
 * Plan} carries the five phases of spec §10.2 in order ({@code preflight}, {@code backup}, {@code
 * vendor-upgrade}, {@code reconcile}, {@code verify}) for an upgrade, or the single {@code
 * rollback} phase for a rollback; every plan carries the spec §10.1 warning that rollback restores
 * files only, because the vendor script changes the repository database in both modes (ADR-0012).
 */
public interface UpgradeOperations {

  /** Operation id recorded in the run journal for an upgrade. */
  String UPGRADE_OPERATION = "upgrade";

  /** Operation id recorded in the run journal for a rollback to point B or C. */
  String ROLLBACK_OPERATION = "upgrade.rollback";

  /**
   * How the vendor script treats the repository database (spec §10.1, ADR-0012): {@code samedb}
   * migrates its schema in place, {@code newdb} drops it and recreates it from the point-B full
   * export. Neither can be undone by jrsctl, so both require {@code --db-backup-confirmed}.
   */
  enum Mode {
    NEWDB,
    SAMEDB;

    public String vendorSuffix() {
      return switch (this) {
        case NEWDB -> "newdb";
        case SAMEDB -> "samedb";
      };
    }
  }

  /**
   * Rollback points of spec §10.2: B is the state after the backup phase, C the state after the
   * vendor upgrade. Restoring to C means restoring the point-B backups, so both restore the same
   * snapshot set; the distinction is kept for the operator's vocabulary.
   */
  enum RollbackPoint {
    B,
    C
  }

  /** Everything the operator chooses on the command line. */
  record UpgradeOptions(
      String toVersion,
      Path packageDir,
      Mode mode,
      boolean dbBackupConfirmed,
      boolean reapplyHotfixes,
      Optional<Path> tomcatDir) {
    public UpgradeOptions {
      Objects.requireNonNull(toVersion, "toVersion");
      Objects.requireNonNull(packageDir, "packageDir");
      Objects.requireNonNull(mode, "mode");
      Objects.requireNonNull(tomcatDir, "tomcatDir");
      if (toVersion.isBlank()) {
        throw new IllegalArgumentException("toVersion must not be blank");
      }
      packageDir = packageDir.toAbsolutePath().normalize();
    }

    /** A {@code newdb} upgrade with the database backup confirmed, as every run requires. */
    /** The options with the webapp staying in the Tomcat the server runs in now. */
    public UpgradeOptions(
        String toVersion,
        Path packageDir,
        Mode mode,
        boolean dbBackupConfirmed,
        boolean reapplyHotfixes) {
      this(toVersion, packageDir, mode, dbBackupConfirmed, reapplyHotfixes, Optional.empty());
    }

    public static UpgradeOptions newdb(String toVersion, Path packageDir) {
      return new UpgradeOptions(toVersion, packageDir, Mode.NEWDB, true, false);
    }
  }

  Plan planUpgrade(UpgradeOptions options);

  Plan planRollback(String runId, RollbackPoint point);
}
