package com.jaspersoft.jrsctl.ops.upgrade;

import static org.assertj.core.api.Assertions.assertThat;

import com.jaspersoft.jrsctl.core.engine.CheckResult;
import com.jaspersoft.jrsctl.core.engine.Context;
import com.jaspersoft.jrsctl.core.engine.Plan;
import com.jaspersoft.jrsctl.core.engine.RetryPolicy;
import com.jaspersoft.jrsctl.core.engine.RunOptions;
import com.jaspersoft.jrsctl.core.engine.RunOutcome;
import com.jaspersoft.jrsctl.core.engine.Step;
import com.jaspersoft.jrsctl.core.engine.StepResult;
import com.jaspersoft.jrsctl.core.event.EventSink;
import com.jaspersoft.jrsctl.core.platform.ServiceController;
import com.jaspersoft.jrsctl.core.state.HotfixInstalled;
import com.jaspersoft.jrsctl.core.state.HotfixState;
import com.jaspersoft.jrsctl.ops.FakeJrsAdapter;
import com.jaspersoft.jrsctl.ops.Idempotency;
import com.jaspersoft.jrsctl.ops.customizations.DefaultCustomizationOperations;
import com.jaspersoft.jrsctl.ops.hotfix.HotfixPaths;
import com.jaspersoft.jrsctl.ops.upgrade.UpgradeOperations.RollbackPoint;
import com.jaspersoft.jrsctl.ops.upgrade.UpgradeOperations.UpgradeOptions;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Phase 8 idempotency of every upgrade and upgrade-rollback step (spec §6.1, §10): re-executing a
 * step after a complete first execution, and re-running a compensation, leave the install tree, the
 * target package, the backups, the run directory, the keystore, the store rows and the service as
 * one execution did. {@code default_master.properties} copies are compared without their date
 * comment (the vendor format writes one); audit rows are an append-only log and not part of the
 * compared state.
 */
class UpgradeStepIdempotencyTest {

  private static final String ORIGINAL = "a=1\nb=2\n";
  private static final String CUSTOMIZED = "a=1\nb=two\n";

  @TempDir Path tmp;

  // ---------------------------------------------------------------- helpers

  private static Optional<String> normalised(Path file) throws IOException {
    String name = file.getFileName().toString();
    return name.startsWith("default_master.properties")
        ? Idempotency.withoutComments(file)
        : Optional.empty();
  }

  private static Map<String, String> state(UpgradeFixture f, String runId) throws IOException {
    Map<String, String> m = new TreeMap<>();
    m.putAll(Idempotency.tree("install", f.installDir, UpgradeStepIdempotencyTest::normalised));
    m.putAll(Idempotency.tree("package", f.packageDir, UpgradeStepIdempotencyTest::normalised));
    m.putAll(Idempotency.tree("snapshots", f.fake.home.snapshots()));
    m.putAll(Idempotency.tree("runs", f.fake.home.runs(), UpgradeStepIdempotencyTest::normalised));
    m.putAll(Idempotency.tree("keystore", f.keystoreDir));
    m.put("service", f.fake.platform.serviceState.name());
    m.put("hotfixes", f.store().hotfixes().toString());
    m.put("customizations", f.store().customizations().toString());
    m.put("snapshot-rows", f.store().snapshots(runId).toString());
    return m;
  }

  private static UpgradeOptions newdb(UpgradeFixture f) {
    return UpgradeOptions.newdb(UpgradeFixture.NEW_VERSION, f.packageDir);
  }

  private static Context start(UpgradeFixture f, Plan plan, String runId) {
    f.store().recordRunStart(runId, plan.summary().operation(), Optional.empty(), Instant.EPOCH);
    return f.ctx(runId);
  }

  private static void assertReexecutionConverges(
      UpgradeFixture f, Plan plan, String runId, String stepId) throws IOException {
    Context ctx = start(f, plan, runId);
    Idempotency.runUpTo(plan, ctx, stepId, f.events::add);
    Map<String, String> once = state(f, runId);
    Idempotency.executeOk(Idempotency.step(plan, stepId), ctx, f.events::add);
    assertThat(state(f, runId)).as(String.join("\n", f.logs())).isEqualTo(once);
  }

  private static void assertCompensationConverges(
      UpgradeFixture f, Plan plan, String runId, String stepId) throws IOException {
    Context ctx = start(f, plan, runId);
    Idempotency.runAll(plan, ctx, f.events::add);
    Step step = Idempotency.step(plan, stepId);
    Idempotency.compensateOk(step, ctx, f.events::add);
    Map<String, String> once = state(f, runId);
    Idempotency.compensateOk(step, ctx, f.events::add);
    assertThat(state(f, runId)).as(String.join("\n", f.logs())).isEqualTo(once);
  }

  /**
   * A configuration file outside the webapp and buildomatic trees, so only restore-config owns it.
   */
  private static Path contextXml(UpgradeFixture f) {
    return f.tomcatDir
        .resolve("conf")
        .resolve("Catalina")
        .resolve("localhost")
        .resolve("jasperserver-pro.xml");
  }

  private static final String CHANGED_XML =
      "<Context docBase=\"jasperserver-pro\" changed=\"yes\"/>";

  private static HotfixInstalled installed(String id, String runId) {
    return new HotfixInstalled(
        id, "1", "old fix " + id, runId, Optional.empty(), HotfixState.INSTALLED, Instant.EPOCH);
  }

  private static UpgradeInput input(UpgradeFixture f) throws IOException {
    return new UpgradeInput(
        newdb(f),
        HotfixPaths.from(f.services.config(), f.services.platform()),
        "jasperserver-pro",
        TargetPackage.inspect(f.packageDir, f.runtime().locator()),
        Optional.of(FakeJrsAdapter.identity("8.2.0")),
        Map.of());
  }

  /** A successful upgrade run, followed by changes the operator would see after it. */
  private static Plan rollbackPlan(UpgradeFixture f) throws Exception {
    Plan up = f.ops().planUpgrade(newdb(f));
    assertThat(f.run(up, "r-up", RunOptions.DEFAULT))
        .as(String.join("\n", f.logs()))
        .isInstanceOf(RunOutcome.Succeeded.class);
    UpgradeFixture.write(contextXml(f), CHANGED_XML);
    UpgradeFixture.write(f.keystoreDir.resolve(".jrsks"), "keystore-changed-by-the-upgrade");
    f.fake.platform.controller.events.clear();
    return f.ops().planRollback("r-up", RollbackPoint.C);
  }

  private static Path registerCustomization(UpgradeFixture f, String name) throws Exception {
    Path file = f.webappDir.resolve("WEB-INF").resolve("classes").resolve(name);
    Path pristine = f.root.resolve("pristine-" + name);
    UpgradeFixture.write(pristine, ORIGINAL);
    UpgradeFixture.write(file, CUSTOMIZED);
    new DefaultCustomizationOperations(f.services, f.snapshots())
        .register(file, Optional.of(pristine));
    UpgradeFixture.write(file, ORIGINAL);
    return file;
  }

  // ---------------------------------------------------------------- upgrade plan

  /** Each read-only step is re-executed at its own position in the plan, as a resume would. */
  @Test
  void should_not_mutate_when_read_only_upgrade_steps_execute_twice() throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      Plan plan = f.ops().planUpgrade(newdb(f));
      Context ctx = start(f, plan, "r-ro");
      List<String> readOnly =
          List.of(
              "doctor",
              "verify-target-package",
              "full-export-wait-for-server",
              "wait-for-server",
              "smoke");
      List<String> seen = new ArrayList<>();
      for (Step step : plan.steps()) {
        Idempotency.executeOk(step, ctx, f.events::add);
        if (readOnly.contains(step.id())) {
          assertThat(step.mutating()).as(step.id()).isFalse();
          Map<String, String> before = state(f, "r-ro");
          Idempotency.executeOk(step, ctx, f.events::add);
          Idempotency.compensateOk(step, ctx, f.events::add);
          assertThat(state(f, "r-ro")).as(step.id()).isEqualTo(before);
          seen.add(step.id());
        }
      }
      assertThat(seen).containsExactlyInAnyOrderElementsOf(readOnly);
      Step confirm = new PreflightSteps.ConfirmDbBackup(f.runtime(), input(f));
      assertThat(confirm.mutating()).isFalse();
      Map<String, String> before = state(f, "r-ro");
      Idempotency.executeOk(confirm, ctx, f.events::add);
      Idempotency.executeOk(confirm, ctx, f.events::add);
      assertThat(state(f, "r-ro")).isEqualTo(before);
    }
  }

  @Test
  void should_reuse_the_export_when_full_export_executes_twice() throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      assertReexecutionConverges(f, f.ops().planUpgrade(newdb(f)), "r-fe", "full-export");
      assertThat(f.logs()).anyMatch(m -> m.contains("reusing it"));
    }
  }

  @Test
  void should_converge_when_backup_keystore_executes_twice() throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      assertReexecutionConverges(f, f.ops().planUpgrade(newdb(f)), "r-bk", "backup-keystore");
      assertThat(f.store().snapshots("r-bk")).hasSize(1);
    }
  }

  @Test
  void should_reuse_the_archives_when_backup_webapp_executes_twice() throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      assertReexecutionConverges(f, f.ops().planUpgrade(newdb(f)), "r-bw", "backup-webapp");
      assertThat(f.logs()).anyMatch(m -> m.contains("already present and verified"));
    }
  }

  @Test
  void should_converge_when_backup_config_executes_twice() throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      assertReexecutionConverges(f, f.ops().planUpgrade(newdb(f)), "r-bc", "backup-config");
    }
  }

  /**
   * {@code MasterPropertiesTest#should_keep_pristine_backup_when_staged_twice} covers the file op.
   */
  @Test
  void should_keep_the_pristine_backup_when_write_master_properties_executes_twice()
      throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      Path target = f.packageDir.resolve("buildomatic").resolve("default_master.properties");
      UpgradeFixture.write(target, "pristine=yes\n");
      assertReexecutionConverges(
          f, f.ops().planUpgrade(newdb(f)), "r-wm", "write-master-properties");
      assertThat(f.fake.home.runDir("r-wm").resolve("default_master.properties.bak"))
          .hasContent("pristine=yes\n");
      assertThat(UpgradeFixture.read(target)).contains("pristine=yes").contains("dbHost=localhost");
    }
  }

  @Test
  void should_converge_when_write_master_properties_compensates_twice() throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      Path target = f.packageDir.resolve("buildomatic").resolve("default_master.properties");
      UpgradeFixture.write(target, "pristine=yes\n");
      Plan plan = f.ops().planUpgrade(newdb(f));
      Context ctx = start(f, plan, "r-wm-c");
      Idempotency.runUpTo(plan, ctx, "write-master-properties");
      Step step = Idempotency.step(plan, "write-master-properties");
      Idempotency.compensateOk(step, ctx);
      Map<String, String> once = state(f, "r-wm-c");
      Idempotency.compensateOk(step, ctx);
      assertThat(state(f, "r-wm-c")).isEqualTo(once);
      assertThat(target).hasContent("pristine=yes\n");
    }
  }

  @Test
  void should_run_the_vendor_script_once_when_run_vendor_upgrade_executes_twice() throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      assertReexecutionConverges(f, f.ops().planUpgrade(newdb(f)), "r-vu", "run-vendor-upgrade");
      assertThat(UpgradeFixture.read(f.vendorLog).strip().lines()).hasSize(1);
      assertThat(f.logs()).anyMatch(m -> m.contains("already completed in this run"));
    }
  }

  @Test
  void should_converge_when_run_vendor_upgrade_compensates_twice() throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      String oldHash = f.sha(f.webappDir.resolve("scripts").resolve("app.js"));
      Plan plan = f.ops().planUpgrade(newdb(f));
      Context ctx = start(f, plan, "r-vu-c");
      Idempotency.runUpTo(plan, ctx, "run-vendor-upgrade");
      Step step = Idempotency.step(plan, "run-vendor-upgrade");
      Idempotency.compensateOk(step, ctx);
      Map<String, String> once = state(f, "r-vu-c");
      Idempotency.compensateOk(step, ctx);
      assertThat(state(f, "r-vu-c")).isEqualTo(once);
      assertThat(f.sha(f.webappDir.resolve("scripts").resolve("app.js"))).isEqualTo(oldHash);
      assertThat(UpgradeFixture.read(f.webappDir.resolve("version.txt")))
          .isEqualTo(UpgradeFixture.OLD_VERSION);
    }
  }

  @Test
  void should_stop_once_when_stop_service_executes_twice() throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      assertReexecutionConverges(
          f, f.ops().planUpgrade(newdb(f)), "r-stop", "full-export-stop-service");
      assertThat(f.fake.platform.controller.events).containsExactly("stop");
      assertThat(f.fake.home.runDir("r-stop").resolve("full-export-stop-service.stopped")).exists();
    }
  }

  @Test
  void should_start_once_when_stop_service_compensates_twice() throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      Plan plan = f.ops().planUpgrade(newdb(f));
      Context ctx = start(f, plan, "r-stop-c");
      Idempotency.runUpTo(plan, ctx, "full-export-stop-service");
      Step stop = Idempotency.step(plan, "full-export-stop-service");
      Idempotency.compensateOk(stop, ctx);
      Idempotency.compensateOk(stop, ctx);
      assertThat(f.fake.platform.controller.events).containsExactly("stop", "start");
      assertThat(f.fake.platform.serviceState).isEqualTo(ServiceController.State.RUNNING);
      assertThat(f.fake.home.runDir("r-stop-c").resolve("full-export-stop-service.stopped"))
          .doesNotExist();
    }
  }

  @Test
  void should_start_once_when_start_service_executes_twice() throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      assertReexecutionConverges(
          f, f.ops().planUpgrade(newdb(f)), "r-start", "full-export-start-service");
      assertThat(f.fake.platform.controller.events).containsExactly("stop", "start");
    }
  }

  @Test
  void should_stop_once_when_start_service_compensates_twice() throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      Plan plan = f.ops().planUpgrade(newdb(f));
      Context ctx = start(f, plan, "r-start-c");
      Idempotency.runUpTo(plan, ctx, "full-export-start-service");
      Step startStep = Idempotency.step(plan, "full-export-start-service");
      Idempotency.compensateOk(startStep, ctx);
      Idempotency.compensateOk(startStep, ctx);
      assertThat(f.fake.platform.controller.events).containsExactly("stop", "start", "stop");
    }
  }

  @Test
  void should_converge_when_plan_hotfix_reapply_executes_twice() throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      f.store().recordHotfixInstalled(installed("HF-A", "r-a"), List.of());
      Step step = new ReconcileSteps.PlanHotfixReapply(f.runtime(), input(f), List.of("HF-A"));
      assertThat(step.mutating()).isTrue();
      Context ctx = f.ctx("r-hr");
      Idempotency.executeOk(step, ctx);
      Map<String, String> once = state(f, "r-hr");

      Idempotency.executeOk(step, ctx);

      assertThat(state(f, "r-hr")).isEqualTo(once);
      assertThat(f.store().hotfix("HF-A").orElseThrow().state()).isEqualTo(HotfixState.SUPERSEDED);
      assertThat(f.fake.home.runDir("r-hr").resolve("plan-hotfix-reapply.superseded"))
          .hasContent("HF-A");
    }
  }

  @Test
  void should_converge_when_plan_hotfix_reapply_compensates_twice() throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      f.store().recordHotfixInstalled(installed("HF-A", "r-a"), List.of());
      Step step = new ReconcileSteps.PlanHotfixReapply(f.runtime(), input(f), List.of("HF-A"));
      Context ctx = f.ctx("r-hr-c");
      Idempotency.executeOk(step, ctx);
      Idempotency.compensateOk(step, ctx);
      Map<String, String> once = state(f, "r-hr-c");

      Idempotency.compensateOk(step, ctx);

      assertThat(state(f, "r-hr-c")).isEqualTo(once);
      assertThat(f.store().hotfix("HF-A").orElseThrow().state()).isEqualTo(HotfixState.INSTALLED);
    }
  }

  @Test
  void should_converge_when_plan_customization_reapply_executes_twice() throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      Path file = registerCustomization(f, "custom.properties");
      Plan plan = f.ops().planUpgrade(newdb(f));
      Step step = Idempotency.step(plan, "plan-customization-reapply");
      Context ctx = f.ctx("r-cr");
      Idempotency.executeOk(step, ctx, f.events::add);
      Map<String, String> once = state(f, "r-cr");

      Idempotency.executeOk(step, ctx, f.events::add);

      assertThat(state(f, "r-cr")).isEqualTo(once);
      assertThat(UpgradeFixture.read(file)).isEqualTo(CUSTOMIZED);
      assertThat(f.logs()).anyMatch(m -> m.contains("already in place"));
    }
  }

  /**
   * A crash after the snapshot and the first copy leaves the second file un-customised; the
   * re-execution copies it, and the compensation must still restore both upgraded files.
   */
  @Test
  void
      should_restore_every_file_when_plan_customization_reapply_compensates_after_a_partial_re_execution()
          throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      Path first = registerCustomization(f, "first.properties");
      Path second = registerCustomization(f, "second.properties");
      Plan plan = f.ops().planUpgrade(newdb(f));
      Step step = Idempotency.step(plan, "plan-customization-reapply");
      Context ctx = f.ctx("r-cr-partial");
      Idempotency.executeOk(step, ctx);
      // the process died after copying the first file: the second is still the upgraded one
      UpgradeFixture.write(second, ORIGINAL);

      Idempotency.executeOk(step, ctx);
      assertThat(UpgradeFixture.read(first)).isEqualTo(CUSTOMIZED);
      assertThat(UpgradeFixture.read(second)).isEqualTo(CUSTOMIZED);

      Idempotency.compensateOk(step, ctx);

      assertThat(UpgradeFixture.read(first)).as("first file restored").isEqualTo(ORIGINAL);
      assertThat(UpgradeFixture.read(second)).as("second file restored").isEqualTo(ORIGINAL);
    }
  }

  @Test
  void should_converge_when_plan_customization_reapply_compensates_twice() throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      Path file = registerCustomization(f, "custom.properties");
      Plan plan = f.ops().planUpgrade(newdb(f));
      Step step = Idempotency.step(plan, "plan-customization-reapply");
      Context ctx = f.ctx("r-cr-c");
      Idempotency.executeOk(step, ctx);
      Idempotency.compensateOk(step, ctx);
      Map<String, String> once = state(f, "r-cr-c");

      Idempotency.compensateOk(step, ctx);

      assertThat(state(f, "r-cr-c")).isEqualTo(once);
      assertThat(UpgradeFixture.read(file)).isEqualTo(ORIGINAL);
    }
  }

  /**
   * The second execution finds no INSTALLED hotfix left (the first one superseded them all) and
   * must not lose the recorded list, or the compensation could no longer put the states back.
   */
  @Test
  void should_keep_the_superseded_list_when_record_upgrade_executes_twice() throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      f.store().recordHotfixInstalled(installed("HF-OLD", "r-old"), List.of());
      Plan plan = f.ops().planUpgrade(newdb(f));
      Context ctx = start(f, plan, "r-ru");
      Idempotency.runAll(plan, ctx);
      Map<String, String> once = state(f, "r-ru");
      Step step = Idempotency.step(plan, "record-upgrade");

      Idempotency.executeOk(step, ctx);

      assertThat(state(f, "r-ru")).isEqualTo(once);
      assertThat(f.fake.home.runDir("r-ru").resolve("record-upgrade.superseded"))
          .hasContent("HF-OLD");
      Idempotency.compensateOk(step, ctx);
      assertThat(f.store().hotfix("HF-OLD").orElseThrow().state()).isEqualTo(HotfixState.INSTALLED);
    }
  }

  @Test
  void should_converge_when_record_upgrade_compensates_twice() throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      f.store().recordHotfixInstalled(installed("HF-OLD", "r-old"), List.of());
      assertCompensationConverges(f, f.ops().planUpgrade(newdb(f)), "r-ru-c", "record-upgrade");
      assertThat(f.store().hotfix("HF-OLD").orElseThrow().state()).isEqualTo(HotfixState.INSTALLED);
    }
  }

  // ---------------------------------------------------------------- rollback plan

  @Test
  void should_converge_when_restore_webapp_executes_twice() throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      assertReexecutionConverges(f, rollbackPlan(f), "r-rb", "restore-webapp");
      assertThat(UpgradeFixture.read(f.webappDir.resolve("version.txt")))
          .isEqualTo(UpgradeFixture.OLD_VERSION);
      assertThat(f.fake.home.runDir("r-rb").resolve("aside").resolve("jasperserver-pro"))
          .isDirectory();
    }
  }

  @Test
  void should_converge_when_restore_webapp_compensates_twice() throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      assertCompensationConverges(f, rollbackPlan(f), "r-rb-c", "restore-webapp");
      assertThat(UpgradeFixture.read(f.webappDir.resolve("version.txt")))
          .as("the upgraded tree is back in place")
          .isEqualTo(UpgradeFixture.NEW_VERSION);
    }
  }

  @Test
  void should_converge_when_restore_buildomatic_executes_twice() throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      assertReexecutionConverges(f, rollbackPlan(f), "r-rbb", "restore-buildomatic");
    }
  }

  /**
   * The pre-restore snapshot must keep the post-upgrade file from the first execution, so that
   * compensation after a re-execution still brings the upgraded file back.
   */
  @Test
  void should_keep_the_pre_restore_snapshot_when_restore_config_executes_twice() throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      Plan back = rollbackPlan(f);
      assertReexecutionConverges(f, back, "r-rc", "restore-config");
      assertThat(UpgradeFixture.read(contextXml(f))).doesNotContain("changed");

      Idempotency.compensateOk(Idempotency.step(back, "restore-config"), f.ctx("r-rc"));

      assertThat(UpgradeFixture.read(contextXml(f))).isEqualTo(CHANGED_XML);
    }
  }

  @Test
  void should_converge_when_restore_config_compensates_twice() throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      assertCompensationConverges(f, rollbackPlan(f), "r-rc-c", "restore-config");
      assertThat(UpgradeFixture.read(contextXml(f))).isEqualTo(CHANGED_XML);
    }
  }

  @Test
  void should_keep_the_pre_restore_snapshot_when_restore_keystore_executes_twice()
      throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      Path keystore = f.keystoreDir.resolve(".jrsks");
      Plan back = rollbackPlan(f);
      assertReexecutionConverges(f, back, "r-rk", "restore-keystore");
      assertThat(UpgradeFixture.read(keystore)).isEqualTo("keystore-bytes");

      Idempotency.compensateOk(Idempotency.step(back, "restore-keystore"), f.ctx("r-rk"));

      assertThat(UpgradeFixture.read(keystore)).isEqualTo("keystore-changed-by-the-upgrade");
    }
  }

  @Test
  void should_converge_when_record_rollback_executes_twice() throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      assertReexecutionConverges(f, rollbackPlan(f), "r-rr", "record-rollback");
      assertThat(
              f.store().auditRows(50).stream()
                  .filter(a -> a.action().equals("upgrade.rolled-back")))
          .as("audit is an append-only log; a re-executed record step appends again")
          .hasSize(2);
    }
  }

  // ---------------------------------------------------------------- embedded step

  @Test
  void should_delegate_to_the_inner_step_when_embedded_step_executes_twice() throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      CountingStep inner = new CountingStep();
      Step embedded = new EmbeddedStep(inner, "JRS-8.2.0-HF-0001");
      Context ctx = f.ctx("r-emb");

      Idempotency.executeOk(embedded, ctx);
      Idempotency.executeOk(embedded, ctx);
      Idempotency.compensateOk(embedded, ctx);

      assertThat(embedded.id()).isEqualTo("reapply-jrs-8.2.0-hf-0001-inner");
      assertThat(embedded.phase()).isEqualTo(Phases.RECONCILE);
      assertThat(embedded.mutating()).isTrue();
      assertThat(embedded.irreversible()).isFalse();
      assertThat(embedded.retryPolicy()).isEqualTo(RetryPolicy.NONE);
      assertThat(inner.executions)
          .containsExactly("r-emb-hf-jrs-8.2.0-hf-0001", "r-emb-hf-jrs-8.2.0-hf-0001");
      assertThat(inner.compensations).containsExactly("r-emb-hf-jrs-8.2.0-hf-0001");
    }
  }

  /** Records the run ids it was executed and compensated under. */
  private static final class CountingStep implements Step {
    final List<String> executions = new ArrayList<>();
    final List<String> compensations = new ArrayList<>();

    @Override
    public String id() {
      return "inner";
    }

    @Override
    public String title() {
      return "inner";
    }

    @Override
    public String phase() {
      return "apply";
    }

    @Override
    public CheckResult precheck(Context ctx) {
      return CheckResult.pass();
    }

    @Override
    public StepResult execute(Context ctx, EventSink out) {
      executions.add(ctx.runId());
      return StepResult.ok();
    }

    @Override
    public StepResult compensate(Context ctx, EventSink out) {
      compensations.add(ctx.runId());
      return StepResult.ok();
    }
  }
}
