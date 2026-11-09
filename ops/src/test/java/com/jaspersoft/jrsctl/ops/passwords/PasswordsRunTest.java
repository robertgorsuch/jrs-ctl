package com.jaspersoft.jrsctl.ops.passwords;

import static org.assertj.core.api.Assertions.assertThat;

import com.jaspersoft.jrsctl.core.engine.Plan;
import com.jaspersoft.jrsctl.core.engine.RunOutcome;
import com.jaspersoft.jrsctl.ops.passwords.PasswordOperations.MigrateOptions;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** ADR-0053, spec §10.6: the migration run against a fake buildomatic. */
class PasswordsRunTest {

  @TempDir Path tmp;

  /** The index of the vendor-log line that is exactly {@code command}, whatever the line ending. */
  private static int indexOf(String text, String command) {
    int i = text.lines().map(String::strip).toList().indexOf(command);
    assertThat(i).as(command + " in\n" + text).isNotNegative();
    return i;
  }

  @Test
  void should_dry_run_migrate_and_verify_in_the_vendor_order() throws Exception {
    try (PasswordFixture f = PasswordFixture.create(tmp)) {
      RunOutcome outcome = f.run(f.ops().planMigrate(f.options()), "r-pw");

      assertThat(outcome).as(String.join("\n", f.logs())).isInstanceOf(RunOutcome.Succeeded.class);
      String log = f.vendorLogText();
      int dry = indexOf(log, "js-ant migrate-passwords-dry-run");
      int migrate = indexOf(log, "js-ant migrate-passwords");
      int detect = indexOf(log, "js-ant detect-password-strategy");
      assertThat(dry).isLessThan(migrate);
      assertThat(migrate).isLessThan(detect);
      assertThat(log).doesNotContain("cleanup-password-migration");
      assertThat(f.migrated()).isTrue();
      assertThat(f.logs())
          .anyMatch(l -> l.contains("before the migration: 6 users: 0 modern, 6 legacy"));
      assertThat(f.logs())
          .anyMatch(l -> l.contains("migration complete: 6 users: 5 modern, 1 legacy"));
      assertThat(f.store().auditRows(20))
          .anyMatch(a -> a.action().equals(PasswordSteps.AUDIT_DB_BACKUP_CONFIRMED))
          .anyMatch(a -> a.action().equals(PasswordSteps.AUDIT_MIGRATED));
    }
  }

  @Test
  void should_clean_the_tracking_table_after_a_successful_migration_when_asked() throws Exception {
    try (PasswordFixture f = PasswordFixture.create(tmp)) {
      RunOutcome outcome = f.run(f.ops().planMigrate(new MigrateOptions(true, true)), "r-pw");

      assertThat(outcome).as(String.join("\n", f.logs())).isInstanceOf(RunOutcome.Succeeded.class);
      assertThat(f.cleaned()).isTrue();
      assertThat(f.vendorLogText().indexOf("detect-password-strategy"))
          .isLessThan(f.vendorLogText().indexOf("cleanup-password-migration"));
    }
  }

  /** Installation guide 10.1 p.195: no migration without the operator's word on a backup. */
  @Test
  void should_refuse_before_running_any_vendor_target_without_the_backup_confirmation()
      throws Exception {
    try (PasswordFixture f = PasswordFixture.create(tmp)) {
      RunOutcome outcome = f.run(f.ops().planMigrate(new MigrateOptions(false, false)), "r-pw");

      assertThat(outcome).isInstanceOf(RunOutcome.PrecheckFailed.class);
      RunOutcome.PrecheckFailed failed = (RunOutcome.PrecheckFailed) outcome;
      assertThat(failed.stepId()).isEqualTo(PasswordSteps.CONFIRM_DB_BACKUP);
      assertThat(failed.message()).isEqualTo(PasswordSteps.DB_BACKUP_GATE);
      assertThat(f.vendorLogText()).isEmpty();
      assertThat(f.migrated()).isFalse();
    }
  }

  @Test
  void should_report_the_failure_and_leave_the_verification_out_when_the_migration_fails()
      throws Exception {
    try (PasswordFixture f = PasswordFixture.create(tmp)) {
      f.failTarget("migrate-passwords");
      Plan plan = f.ops().planMigrate(f.options());

      RunOutcome outcome = f.run(plan, "r-pw");

      assertThat(outcome).isNotInstanceOf(RunOutcome.Succeeded.class);
      assertThat(f.vendorLogText()).contains("migrate-passwords-dry-run");
      assertThat(f.vendorLogText()).doesNotContain("detect-password-strategy");
      assertThat(f.migrated()).isFalse();
    }
  }

  @Test
  void should_refuse_before_anything_when_the_storage_settings_file_is_missing() throws Exception {
    try (PasswordFixture f = PasswordFixture.createWithStrategy(tmp, null)) {
      RunOutcome outcome = f.run(f.ops().planMigrate(f.options()), "r-pw");

      assertThat(outcome).isInstanceOf(RunOutcome.PrecheckFailed.class);
      assertThat(((RunOutcome.PrecheckFailed) outcome).stepId())
          .isEqualTo(PasswordSteps.VERIFY_TOOLS);
      assertThat(((RunOutcome.PrecheckFailed) outcome).message())
          .contains(PasswordInput.STORAGE_CONFIG);
      assertThat(f.vendorLogText()).isEmpty();
    }
  }
}
