package com.jaspersoft.jrsctl.ops.upgrade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jaspersoft.jrsctl.core.engine.CheckResult;
import com.jaspersoft.jrsctl.core.engine.Plan;
import com.jaspersoft.jrsctl.core.engine.Step;
import com.jaspersoft.jrsctl.ops.upgrade.UpgradeOperations.Mode;
import com.jaspersoft.jrsctl.ops.upgrade.UpgradeOperations.UpgradeOptions;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UpgradePlanTest {

  @TempDir Path tmp;

  private static UpgradeOptions newdb(UpgradeFixture f) {
    return UpgradeOptions.newdb(UpgradeFixture.NEW_VERSION, f.packageDir);
  }

  @Test
  void should_list_five_phases_and_step_ids_when_mode_newdb() throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      Plan plan = f.ops().planUpgrade(newdb(f));

      assertThat(plan.byPhase().keySet())
          .containsExactly("preflight", "backup", "vendor-upgrade", "reconcile", "verify");
      assertThat(UpgradeFixture.ids(plan))
          .containsExactly(
              "doctor",
              "verify-target-package",
              "full-export-stop-service",
              "full-export",
              "full-export-start-service",
              "full-export-wait-for-server",
              "backup-keystore",
              "backup-webapp",
              "backup-config",
              "write-master-properties",
              "stop-service",
              "run-vendor-upgrade",
              "start-service",
              "wait-for-server",
              "plan-hotfix-reapply",
              "plan-customization-reapply",
              "smoke",
              "record-upgrade");
      assertThat(plan.summary().operation()).isEqualTo("upgrade");
      assertThat(plan.summary().target()).isEqualTo("8.2.0 -> 9.0.0 (newdb)");
      assertThat(plan.summary().serviceRestart()).isTrue();
      assertThat(plan.summary().strategy()).isEqualTo("vendor-cli");
      assertThat(plan.summary().warnings())
          .contains(DefaultUpgradeOperations.NEWDB_WARNING)
          .doesNotContain(DefaultUpgradeOperations.SAMEDB_WARNING);
      assertThat(plan.summary().rollbackPointsByPhase())
          .containsKeys("preflight", "backup", "vendor-upgrade", "reconcile", "verify");
      assertThat(plan.summary().rollbackPointsByPhase().get("verify"))
          .contains("failure offers rollback to point B");
      assertThat(plan.fingerprint().inputs())
          .containsKeys("server", "package", "config", "to", "mode");
      assertThat(plan.steps()).allSatisfy(s -> assertThat(s.title()).isNotBlank());
      assertThat(UpgradeFixture.step(plan, "run-vendor-upgrade").irreversible()).isFalse();
      for (Step s : plan.byPhase().get("preflight")) {
        assertThat(s.mutating()).as(s.id()).isFalse();
      }
    }
  }

  @Test
  void should_add_confirm_db_backup_and_the_samedb_sentence_when_mode_samedb() throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      Plan plan =
          f.ops()
              .planUpgrade(
                  new UpgradeOptions(
                      UpgradeFixture.NEW_VERSION, f.packageDir, Mode.SAMEDB, true, false));

      assertThat(UpgradeFixture.ids(plan))
          .startsWith("doctor", "verify-target-package", "confirm-db-backup");
      assertThat(plan.summary().warnings())
          .contains(
              "Rollback restores files only. Restore the database from your own backup before"
                  + " running rollback.");
      assertThat(plan.summary().target()).endsWith("(samedb)");
      assertThat(UpgradeFixture.step(plan, "run-vendor-upgrade").title())
          .contains("js-upgrade-samedb");
      CheckResult ok = UpgradeFixture.step(plan, "confirm-db-backup").precheck(f.ctx("r-1"));
      assertThat(ok).isInstanceOf(CheckResult.Pass.class);
    }
  }

  @Test
  void should_fail_confirm_db_backup_precheck_when_samedb_not_confirmed() throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      Plan plan =
          f.ops()
              .planUpgrade(
                  new UpgradeOptions(
                      UpgradeFixture.NEW_VERSION, f.packageDir, Mode.SAMEDB, false, false));

      CheckResult result = UpgradeFixture.step(plan, "confirm-db-backup").precheck(f.ctx("r-1"));

      assertThat(result).isInstanceOf(CheckResult.Fail.class);
      assertThat(((CheckResult.Fail) result).message()).contains("--db-backup-confirmed");
    }
  }

  @Test
  void should_refuse_with_exit_6_when_upgrade_path_unsupported() throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      assertThatThrownBy(() -> f.ops().planUpgrade(UpgradeOptions.newdb("7.5.0", f.packageDir)))
          .isInstanceOf(UpgradeException.class)
          .hasMessageContaining("8.2.0 -> 7.5.0")
          .satisfies(e -> assertThat(((UpgradeException) e).exitCode()).isEqualTo(6));
    }
  }

  @Test
  void should_fail_verify_target_package_precheck_when_vendor_java_mismatches() throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      f.javaVersion("openjdk version \"11.0.24\" 2024-07-16");
      Plan plan = f.ops().planUpgrade(newdb(f));

      CheckResult result =
          UpgradeFixture.step(plan, "verify-target-package").precheck(f.ctx("r-1"));

      assertThat(result).isInstanceOf(CheckResult.Fail.class);
      assertThat(((CheckResult.Fail) result).message())
          .contains("Java 11")
          .contains("needs Java 17");
    }
  }

  @Test
  void should_pass_verify_target_package_precheck_when_java_matches_and_path_supported()
      throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      Plan plan = f.ops().planUpgrade(newdb(f));

      CheckResult result =
          UpgradeFixture.step(plan, "verify-target-package").precheck(f.ctx("r-1"));

      assertThat(result).as(result.toString()).isInstanceOf(CheckResult.Pass.class);
    }
  }

  @Test
  void should_fail_verify_target_package_precheck_when_package_lacks_vendor_scripts()
      throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      Path empty = Files.createDirectories(tmp.resolve("empty-pkg"));
      Plan plan = f.ops().planUpgrade(UpgradeOptions.newdb(UpgradeFixture.NEW_VERSION, empty));

      CheckResult result =
          UpgradeFixture.step(plan, "verify-target-package").precheck(f.ctx("r-1"));

      assertThat(result).isInstanceOf(CheckResult.Fail.class);
      assertThat(((CheckResult.Fail) result).message()).contains("buildomatic");
      assertThat(plan.summary().warnings()).anyMatch(w -> w.startsWith("target package:"));
    }
  }

  @Test
  void should_fail_verify_target_package_precheck_when_package_names_another_version()
      throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      Path renamed = tmp.resolve("jasperreports-server-9.1.0-bin");
      Files.move(f.packageDir, renamed);
      Plan plan = f.ops().planUpgrade(UpgradeOptions.newdb(UpgradeFixture.NEW_VERSION, renamed));

      CheckResult result =
          UpgradeFixture.step(plan, "verify-target-package").precheck(f.ctx("r-1"));

      assertThat(result).isInstanceOf(CheckResult.Fail.class);
      assertThat(((CheckResult.Fail) result).message())
          .contains("9.1.0")
          .contains("--to says 9.0.0");
    }
  }

  @Test
  void should_copy_master_properties_without_passwords_when_planning() throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      Plan plan = f.ops().planUpgrade(newdb(f));

      Step write = UpgradeFixture.step(plan, "write-master-properties");
      assertThat(write.detail()).contains("no passwords");
      List<String> touched = plan.summary().filesTouched().stream().map(Path::toString).toList();
      assertThat(touched).anyMatch(p -> p.endsWith("default_master.properties"));
      assertThat(plan.summary().warnings()).contains(DefaultUpgradeOperations.PASSWORD_WARNING);
    }
  }

  @Test
  void should_warn_and_defer_path_check_when_server_unreachable_at_plan_time() throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      f.fake.unreachable = true;
      Plan plan = f.ops().planUpgrade(newdb(f));

      assertThat(plan.summary().target()).startsWith("? -> 9.0.0");
      assertThat(plan.summary().warnings()).anyMatch(w -> w.contains("server unreachable"));
      assertThat(plan.fingerprint().inputs().get("server")).isEqualTo("unreachable");
      CheckResult result =
          UpgradeFixture.step(plan, "verify-target-package").precheck(f.ctx("r-1"));
      assertThat(result).isInstanceOf(CheckResult.Fail.class);
    }
  }
}
