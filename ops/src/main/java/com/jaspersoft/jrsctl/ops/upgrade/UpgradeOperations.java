package com.jaspersoft.jrsctl.ops.upgrade;

import com.jaspersoft.jrsctl.core.engine.Plan;
import com.jaspersoft.jrsctl.core.secrets.SecretRef;
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
  /**
   * What the operator asked for. {@code existingExport} is an export taken earlier, here or on
   * another server, that a newdb upgrade rebuilds the repository from instead of exporting now
   * (ADR-0028); {@code keyAlias} and {@code keyPassword} name the key that export was encrypted
   * with, written into the target buildomatic's properties for the vendor import.
   */
  record UpgradeOptions(
      String toVersion,
      Path packageDir,
      Mode mode,
      boolean dbBackupConfirmed,
      boolean reapplyHotfixes,
      Optional<Path> tomcatDir,
      Optional<Path> existingExport,
      Optional<String> keyAlias,
      Optional<SecretRef> keyPassword) {
    public UpgradeOptions {
      Objects.requireNonNull(toVersion, "toVersion");
      Objects.requireNonNull(packageDir, "packageDir");
      Objects.requireNonNull(mode, "mode");
      Objects.requireNonNull(tomcatDir, "tomcatDir");
      Objects.requireNonNull(existingExport, "existingExport");
      Objects.requireNonNull(keyAlias, "keyAlias");
      Objects.requireNonNull(keyPassword, "keyPassword");
      if (toVersion.isBlank()) {
        throw new IllegalArgumentException("toVersion must not be blank");
      }
      packageDir = packageDir.toAbsolutePath().normalize();
      existingExport = existingExport.map(p -> p.toAbsolutePath().normalize());
    }

    /** The options with this run's own export and the server's own key. */
    public UpgradeOptions(
        String toVersion,
        Path packageDir,
        Mode mode,
        boolean dbBackupConfirmed,
        boolean reapplyHotfixes,
        Optional<Path> tomcatDir) {
      this(
          toVersion,
          packageDir,
          mode,
          dbBackupConfirmed,
          reapplyHotfixes,
          tomcatDir,
          Optional.empty(),
          Optional.empty(),
          Optional.empty());
    }

    /** The options with the webapp staying in the Tomcat the server runs in now. */
    public UpgradeOptions(
        String toVersion,
        Path packageDir,
        Mode mode,
        boolean dbBackupConfirmed,
        boolean reapplyHotfixes) {
      this(toVersion, packageDir, mode, dbBackupConfirmed, reapplyHotfixes, Optional.empty());
    }

    public UpgradeOptions withExistingExport(Path export) {
      return new UpgradeOptions(
          toVersion,
          packageDir,
          mode,
          dbBackupConfirmed,
          reapplyHotfixes,
          tomcatDir,
          Optional.of(export),
          keyAlias,
          keyPassword);
    }

    public UpgradeOptions withKeyAlias(String alias) {
      return new UpgradeOptions(
          toVersion,
          packageDir,
          mode,
          dbBackupConfirmed,
          reapplyHotfixes,
          tomcatDir,
          existingExport,
          Optional.of(alias),
          keyPassword);
    }

    public UpgradeOptions withKeyPassword(SecretRef ref) {
      return new UpgradeOptions(
          toVersion,
          packageDir,
          mode,
          dbBackupConfirmed,
          reapplyHotfixes,
          tomcatDir,
          existingExport,
          keyAlias,
          Optional.of(ref));
    }

    public static UpgradeOptions newdb(String toVersion, Path packageDir) {
      return new UpgradeOptions(toVersion, packageDir, Mode.NEWDB, true, false);
    }
  }

  Plan planUpgrade(UpgradeOptions options);

  Plan planRollback(String runId, RollbackPoint point);
}
