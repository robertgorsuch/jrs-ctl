package com.jaspersoft.jrsctl.ops.upgrade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jaspersoft.jrsctl.core.engine.CheckResult;
import com.jaspersoft.jrsctl.core.engine.Plan;
import com.jaspersoft.jrsctl.core.engine.Step;
import com.jaspersoft.jrsctl.ops.ReportItem;
import com.jaspersoft.jrsctl.ops.doctor.DoctorOperation;
import com.jaspersoft.jrsctl.ops.doctor.DoctorOptions;
import com.jaspersoft.jrsctl.ops.doctor.DoctorReport;
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
              "confirm-db-backup",
              "backup-keystore",
              "backup-webapp",
              "backup-config",
              "write-master-properties",
              "stage-keystore-init",
              "stop-service",
              "full-export",
              "run-vendor-upgrade",
              "clear-tomcat-caches",
              "clear-repository-cache",
              "start-service",
              "wait-for-server",
              "plan-hotfix-reapply",
              "plan-customization-reapply",
              "smoke",
              "record-upgrade",
              "point-config-at-target");
      assertThat(plan.summary().operation()).isEqualTo("upgrade");
      assertThat(plan.summary().target()).isEqualTo("8.2.0 -> 9.0.0 (newdb)");
      assertThat(plan.summary().serviceRestart()).isTrue();
      assertThat(plan.summary().strategy()).isEqualTo("vendor-cli");
      assertThat(plan.summary().warnings())
          .contains(DefaultUpgradeOperations.NEWDB_WARNING)
          .contains(DefaultUpgradeOperations.NEWDB_STAYS_STOPPED_WARNING)
          .contains(DefaultUpgradeOperations.FILES_ONLY_WARNING)
          .doesNotContain(DefaultUpgradeOperations.SAMEDB_WARNING);
      assertThat(UpgradeFixture.step(plan, "run-vendor-upgrade").detail())
          .contains("js-upgrade-newdb <point-B full export>")
          .contains("-DimportFile=<point-B full export>");
      assertThat(plan.summary().rollbackPointsByPhase())
          .containsKeys("preflight", "backup", "vendor-upgrade", "reconcile", "verify");
      assertThat(plan.summary().rollbackPointsByPhase().get("verify"))
          .contains("failure offers rollback to point B");
      assertThat(plan.fingerprint().inputs())
          .containsKeys("server", "package", "config", "to", "mode");
      assertThat(plan.steps()).allSatisfy(s -> assertThat(s.title()).isNotBlank());
      assertThat(UpgradeFixture.step(plan, "run-vendor-upgrade").irreversible()).isFalse();
      // upgrade guide 10.1 pp.34-36 "Additional tasks": caches regenerate, nothing to put back
      assertThat(UpgradeFixture.step(plan, "clear-tomcat-caches").irreversible()).isTrue();
      assertThat(UpgradeFixture.step(plan, "clear-repository-cache").irreversible()).isTrue();
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
      // samedb migrates the database in place, so the server may serve between the export and
      // the vendor run; only newdb rebuilds the database from that export (review §1.6)
      assertThat(UpgradeFixture.ids(plan))
          .containsSubsequence(
              "full-export-stop-service",
              "full-export",
              "full-export-start-service",
              "full-export-wait-for-server",
              "backup-keystore");
      assertThat(UpgradeFixture.ids(plan))
          .containsSubsequence("write-master-properties", "stage-keystore-init", "stop-service");
      assertThat(UpgradeFixture.ids(plan))
          .containsSubsequence(
              "run-vendor-upgrade",
              "clear-tomcat-caches",
              "clear-repository-cache",
              "start-service");
      assertThat(plan.summary().warnings())
          .contains(
              "Rollback restores files only. Restore the database from your own backup before"
                  + " running rollback.")
          .contains(DefaultUpgradeOperations.SAMEDB_WARNING)
          .doesNotContain(DefaultUpgradeOperations.NEWDB_WARNING)
          .doesNotContain(DefaultUpgradeOperations.NEWDB_STAYS_STOPPED_WARNING);
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
  void should_fail_confirm_db_backup_precheck_when_newdb_not_confirmed() throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      Plan plan =
          f.ops()
              .planUpgrade(
                  new UpgradeOptions(
                      UpgradeFixture.NEW_VERSION, f.packageDir, Mode.NEWDB, false, false));

      CheckResult result = UpgradeFixture.step(plan, "confirm-db-backup").precheck(f.ctx("r-1"));

      assertThat(result).isInstanceOf(CheckResult.Fail.class);
      assertThat(((CheckResult.Fail) result).message())
          .contains("--db-backup-confirmed")
          .contains("drops and recreates");
    }
  }

  @Test
  void should_fail_run_vendor_upgrade_precheck_when_newdb_and_full_export_missing()
      throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      Plan plan = f.ops().planUpgrade(newdb(f));

      CheckResult result = UpgradeFixture.step(plan, "run-vendor-upgrade").precheck(f.ctx("r-1"));

      assertThat(result).isInstanceOf(CheckResult.Fail.class);
      assertThat(((CheckResult.Fail) result).message())
          .contains("full-export")
          .contains("js-upgrade-newdb rebuilds the repository database from it");
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
      // platform-support 9.0.0: JDK 8, 11 and 17; 21 is not on the sheet (review §1.2)
      f.javaVersion("openjdk version \"21.0.4\" 2024-07-16");
      Plan plan = f.ops().planUpgrade(newdb(f));

      CheckResult result =
          UpgradeFixture.step(plan, "verify-target-package").precheck(f.ctx("r-1"));

      assertThat(result).isInstanceOf(CheckResult.Fail.class);
      assertThat(((CheckResult.Fail) result).message())
          .contains("Java 21")
          .contains("needs Java 8, 11 or 17");
    }
  }

  @Test
  void should_pass_verify_target_package_precheck_when_java_is_any_allowed_major()
      throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      f.javaVersion("openjdk version \"11.0.24\" 2024-07-16");
      Plan plan = f.ops().planUpgrade(newdb(f));

      CheckResult result =
          UpgradeFixture.step(plan, "verify-target-package").precheck(f.ctx("r-1"));

      assertThat(result).as(result.toString()).isInstanceOf(CheckResult.Pass.class);
    }
  }

  /** Upgrade guide 10.0 pp.11-12: 8.x reaches 10.0 as newdb only (review §1.3). */
  @Test
  void should_refuse_with_exit_6_when_the_mode_is_not_offered_for_the_path() throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      assertThatThrownBy(
              () ->
                  f.ops()
                      .planUpgrade(
                          new UpgradeOptions("10.0.0", f.packageDir, Mode.SAMEDB, true, false)))
          .isInstanceOf(UpgradeException.class)
          .hasMessageContaining("8.2.0 -> 10.0.0")
          .hasMessageContaining("samedb")
          .hasMessageContaining("newdb")
          .satisfies(e -> assertThat(((UpgradeException) e).exitCode()).isEqualTo(6));
    }
  }

  /**
   * Review §2.1: 10.0 moved to Jakarta EE; the Tomcat that will host the target must be one the
   * platform sheet certifies for it. The fixture's target is 9.0.0, certified for Tomcat 8.5 and 9.
   */
  @Test
  void should_fail_verify_target_package_precheck_when_the_tomcat_is_not_certified_for_the_target()
      throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      UpgradeFixture.tomcatVersion(f.tomcatDir, "10.1.24");
      Plan plan = f.ops().planUpgrade(newdb(f));

      CheckResult result =
          UpgradeFixture.step(plan, "verify-target-package").precheck(f.ctx("r-1"));

      assertThat(result).isInstanceOf(CheckResult.Fail.class);
      assertThat(((CheckResult.Fail) result).message())
          .contains("Tomcat 10.1.24")
          .contains("not certified for JasperReports Server 9.0.0");
      assertThat(((CheckResult.Fail) result).remediation()).contains("--tomcat-dir");
    }
  }

  @Test
  void should_pass_verify_target_package_precheck_when_the_tomcat_is_certified() throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      UpgradeFixture.tomcatVersion(f.tomcatDir, "9.0.85");
      Plan plan = f.ops().planUpgrade(newdb(f));

      CheckResult result =
          UpgradeFixture.step(plan, "verify-target-package").precheck(f.ctx("r-1"));

      assertThat(result).as(result.toString()).isInstanceOf(CheckResult.Pass.class);
      assertThat(plan.summary().warnings()).noneMatch(w -> w.contains("Tomcat version"));
    }
  }

  @Test
  void should_warn_when_the_tomcat_version_cannot_be_read() throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      Plan plan = f.ops().planUpgrade(newdb(f));

      assertThat(plan.summary().warnings())
          .anyMatch(w -> w.contains("Tomcat version") && w.contains("could not be read"));
    }
  }

  /** ADR-0026: a registered service would start the old Tomcat after the vendor run. */
  @Test
  void should_refuse_tomcat_dir_with_exit_2_unless_the_service_is_manual() throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      assertThatThrownBy(() -> f.ops().planUpgrade(withNewTomcat(f)))
          .isInstanceOf(UpgradeException.class)
          .hasMessageContaining("service.kind manual")
          .hasMessageContaining("systemd")
          .satisfies(e -> assertThat(((UpgradeException) e).exitCode()).isEqualTo(2));
    }
  }

  @Test
  void should_copy_the_webapp_and_point_buildomatic_at_the_new_tomcat_when_tomcat_dir_is_given()
      throws Exception {
    try (UpgradeFixture f = UpgradeFixture.createWithManualService(tmp)) {
      Plan plan = f.ops().planUpgrade(withNewTomcat(f));

      assertThat(UpgradeFixture.ids(plan))
          .containsSubsequence(
              "stop-service", "full-export", "copy-webapp-to-tomcat", "run-vendor-upgrade");
      assertThat(UpgradeFixture.step(plan, "copy-webapp-to-tomcat").detail())
          .contains(f.newTomcatDir.toString());
      assertThat(UpgradeFixture.step(plan, "clear-tomcat-caches").detail())
          .contains(f.newTomcatDir.toString());
      assertThat(UpgradeFixture.step(plan, "point-config-at-target").detail())
          .contains("server.tomcatDir -> " + f.newTomcatDir);
      assertThat(plan.summary().warnings())
          .contains(DefaultUpgradeOperations.TOMCAT_DIR_WARNING.formatted(f.newTomcatDir));
      CheckResult result =
          UpgradeFixture.step(plan, "verify-target-package").precheck(f.ctx("r-1"));
      assertThat(result).as(result.toString()).isInstanceOf(CheckResult.Pass.class);
    }
  }

  static UpgradeOptions withNewTomcat(UpgradeFixture f) {
    return new UpgradeOptions(
        UpgradeFixture.NEW_VERSION,
        f.packageDir,
        Mode.NEWDB,
        true,
        false,
        java.util.Optional.of(f.newTomcatDir));
  }

  /** Review §1.2: doctor judges the vendor JDK against the set the platform sheet lists. */
  @Test
  void should_judge_doctor_vendor_java_against_every_allowed_major() throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      // the fixture's server is 8.2.0, whose sheet lists JDK 8 and 11; the fixture's JDK is 17
      ReportItem seventeen = vendorJava(new DoctorOperation(f.services).run(DoctorOptions.DEFAULT));
      assertThat(seventeen.status()).isEqualTo(ReportItem.Status.FAIL);
      assertThat(seventeen.detail()).contains("Java 17").contains("needs Java 8 or 11");

      f.javaVersion("openjdk version \"11.0.24\" 2024-07-16");
      ReportItem eleven = vendorJava(new DoctorOperation(f.services).run(DoctorOptions.DEFAULT));
      assertThat(eleven.status()).isEqualTo(ReportItem.Status.PASS);
      assertThat(eleven.detail()).contains("Java 11").contains("one of Java 8 or 11");
    }
  }

  private static ReportItem vendorJava(DoctorReport report) {
    return report.items().stream()
        .filter(i -> i.name().equals("vendor-java"))
        .findFirst()
        .orElseThrow();
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
