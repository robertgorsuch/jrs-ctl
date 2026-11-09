package com.jaspersoft.jrsctl.ops.passwords;

import com.jaspersoft.jrsctl.core.engine.Plan;

/**
 * The vendor's migration of stored passwords to the modern format as a command of its own (spec
 * §10.6, ADR-0053; installation guide 10.1 pp.194-199): {@link #status()} is the read-only
 * distribution of modern and legacy passwords, {@link #planMigrate} the journaled run of {@code
 * js-ant migrate-passwords-dry-run}, {@code migrate-passwords}, {@code detect-password-strategy}
 * and, on request, {@code cleanup-password-migration}. Invariants: both need the installation on
 * this machine, because the utility reads {@code WEB-INF/js.password-storage-config.properties} of
 * the deployed webapp; a tools-only or REST-only configuration is refused while planning; the
 * migration is irreversible and the plan says so, gated on {@code --db-backup-confirmed}.
 */
public interface PasswordOperations {

  /** Operation name of a migration run in the journal and the stored plan. */
  String MIGRATE_OPERATION = "passwords.migrate";

  /**
   * What the operator asked for: {@code dbBackupConfirmed} is the gate the plan refuses without
   * (audited); {@code cleanup} adds the vendor's removal of completed rows from the migration
   * tracking table after a successful migration.
   */
  record MigrateOptions(boolean dbBackupConfirmed, boolean cleanup) {}

  PasswordStatusReport status();

  Plan planMigrate(MigrateOptions options);
}
