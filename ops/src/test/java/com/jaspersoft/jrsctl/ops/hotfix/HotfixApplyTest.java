package com.jaspersoft.jrsctl.ops.hotfix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jaspersoft.jrsctl.core.engine.Context;
import com.jaspersoft.jrsctl.core.engine.Plan;
import com.jaspersoft.jrsctl.core.engine.RunOutcome;
import com.jaspersoft.jrsctl.core.engine.Step;
import com.jaspersoft.jrsctl.core.engine.StepResult;
import com.jaspersoft.jrsctl.core.event.EventSink;
import com.jaspersoft.jrsctl.core.state.AuditEntry;
import com.jaspersoft.jrsctl.core.state.HotfixFile;
import com.jaspersoft.jrsctl.core.state.HotfixInstalled;
import com.jaspersoft.jrsctl.core.state.HotfixState;
import com.jaspersoft.jrsctl.ops.FakeJrsAdapter;
import com.jaspersoft.jrsctl.ops.hotfix.HotfixOperations.ApplyOptions;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HotfixApplyTest {

  private static final ApplyOptions SIGNED = new ApplyOptions(false);
  private static final ApplyOptions UNSIGNED = new ApplyOptions(true);

  @TempDir Path tmp;

  @Test
  void should_list_expected_steps_and_phases_when_restart_required() throws IOException {
    try (HotfixFixture f = HotfixFixture.create(tmp)) {
      Plan plan = f.ops().planApply(f.buildWebInf(), SIGNED);
      assertThat(HotfixFixture.ids(plan))
          .containsExactly(
              "verify-signature",
              "validate-manifest",
              "preflight",
              "run-prechecks",
              "snapshot",
              "stop-service",
              "stage-files",
              "atomic-swap",
              "start-service",
              "wait-for-server",
              "run-postchecks",
              "record-installed");
      assertThat(plan.byPhase().keySet()).containsExactly("verify", "backup", "apply", "record");
      assertThat(plan.byPhase().get("apply").stream().map(Step::id))
          .containsExactly(
              "stop-service",
              "stage-files",
              "atomic-swap",
              "start-service",
              "wait-for-server",
              "run-postchecks");
      assertThat(plan.summary().operation()).isEqualTo("hotfix.apply");
      assertThat(plan.summary().target()).startsWith(HotfixFixture.ID);
      assertThat(plan.summary().serviceRestart()).isTrue();
      assertThat(plan.summary().strategy()).isEqualTo("snapshot");
      assertThat(plan.summary().filesTouched())
          .containsExactlyInAnyOrder(
              f.target(HotfixFixture.FOO),
              f.target(HotfixFixture.FOO_OLDER),
              f.target(HotfixFixture.FIX),
              f.target(HotfixFixture.BAR));
      assertThat(plan.summary().rollbackPointsByPhase()).containsKeys("verify", "apply", "record");
      assertThat(plan.fingerprint().inputs())
          .containsKeys("server", "bundle", "config", "target:" + HotfixFixture.FOO);
      assertThat(HotfixFixture.step(plan, "stop-service").detail()).isEqualTo("timeout 180s");
      assertThat(HotfixFixture.step(plan, "atomic-swap").detail()).contains("rename");
      assertThat(plan.steps()).allSatisfy(s -> assertThat(s.title()).isNotBlank());
    }
  }

  @Test
  void should_omit_service_steps_when_restart_none() throws IOException {
    try (HotfixFixture f = HotfixFixture.create(tmp)) {
      Plan plan = f.ops().planApply(f.buildNone(), SIGNED);
      assertThat(HotfixFixture.ids(plan))
          .doesNotContain("stop-service", "start-service", "wait-for-server", "apply-sql");
      assertThat(plan.summary().serviceRestart()).isFalse();
    }
  }

  @Test
  void should_refuse_plan_when_bundle_unsigned() throws IOException {
    try (HotfixFixture f = HotfixFixture.create(tmp)) {
      Path unsigned = unsigned(f);
      assertThatThrownBy(() -> f.ops().planApply(unsigned, SIGNED))
          .isInstanceOf(HotfixException.class)
          .hasMessageContaining("SIGNATURE")
          .extracting(e -> ((HotfixException) e).exitCode())
          .isEqualTo(7);
    }
  }

  @Test
  void should_accept_unsigned_bundle_and_write_audit_row_when_allow_unsigned() throws IOException {
    try (HotfixFixture f = HotfixFixture.create(tmp)) {
      Plan plan = f.ops().planApply(unsigned(f), UNSIGNED);
      assertThat(plan.summary().warnings()).anyMatch(w -> w.contains("allow-unsigned"));
      RunOutcome outcome = f.run(plan, "r-unsigned");
      assertThat(outcome).isInstanceOf(RunOutcome.Succeeded.class);
      assertThat(f.store().auditRows(50).stream().map(AuditEntry::action))
          .contains(ApplySteps.AUDIT_ALLOW_UNSIGNED, ApplySteps.AUDIT_APPLIED);
    }
  }

  @Test
  void should_refuse_plan_when_hashes_do_not_match() throws IOException {
    try (HotfixFixture f = HotfixFixture.create(tmp)) {
      Path tampered =
          Zips.rewrite(
              f.buildWebInf(),
              tmp.resolve("tampered.zip"),
              Map.of("payload/" + HotfixFixture.FOO, Optional.of(new byte[] {1, 2, 3})),
              Map.of());
      assertThatThrownBy(() -> f.ops().planApply(tampered, SIGNED))
          .isInstanceOf(HotfixException.class)
          .hasMessageContaining("sha256 mismatch");
    }
  }

  @Test
  void should_fail_validate_manifest_when_required_hotfix_missing() throws IOException {
    try (HotfixFixture f = HotfixFixture.create(tmp)) {
      Plan plan = f.ops().planApply(f.buildSecond("[\"JRS-8.2.0-HF-0007\"]"), SIGNED);
      RunOutcome outcome = f.run(plan, "r-requires");
      assertThat(outcome)
          .isInstanceOf(RunOutcome.PrecheckFailed.class)
          .extracting(o -> ((RunOutcome.PrecheckFailed) o).stepId())
          .isEqualTo("validate-manifest");
      assertThat(((RunOutcome.PrecheckFailed) outcome).message())
          .contains("requires JRS-8.2.0-HF-0007");
      assertThat(f.sha(HotfixFixture.FOO)).isEqualTo(f.sha(HotfixFixture.FOO));
      assertThat(f.fake.platform.controller.events).isEmpty();
    }
  }

  @Test
  void should_fail_validate_manifest_when_conflicting_hotfix_installed() throws IOException {
    try (HotfixFixture f = HotfixFixture.create(tmp)) {
      assertThat(f.run(f.ops().planApply(f.buildWebInf(), SIGNED), "r-first"))
          .isInstanceOf(RunOutcome.Succeeded.class);
      Path second =
          f.build(
              f.bundleDir(
                  "conflict",
                  HotfixFixture.NONE_MANIFEST
                      .replace(HotfixFixture.ID, HotfixFixture.ID2)
                      .replace(
                          "\"files\"", "\"conflicts\": [\"" + HotfixFixture.ID + "\"], \"files\""),
                  Map.of("payload/" + HotfixFixture.SCRIPT, HotfixFixture.SCRIPT_BYTES)));
      RunOutcome outcome = f.run(f.ops().planApply(second, SIGNED), "r-conflict");
      assertThat(outcome).isInstanceOf(RunOutcome.PrecheckFailed.class);
      assertThat(((RunOutcome.PrecheckFailed) outcome).message())
          .contains("conflicts with installed hotfix " + HotfixFixture.ID);
    }
  }

  @Test
  void should_fail_validate_manifest_when_file_owned_by_hotfix_not_in_requires()
      throws IOException {
    try (HotfixFixture f = HotfixFixture.create(tmp)) {
      assertThat(f.run(f.ops().planApply(f.buildWebInf(), SIGNED), "r-first"))
          .isInstanceOf(RunOutcome.Succeeded.class);
      RunOutcome outcome = f.run(f.ops().planApply(f.buildSecond("[]"), SIGNED), "r-overlap");
      assertThat(outcome).isInstanceOf(RunOutcome.PrecheckFailed.class);
      assertThat(((RunOutcome.PrecheckFailed) outcome).message())
          .contains("is owned by installed hotfix " + HotfixFixture.ID);
    }
  }

  @Test
  void should_fail_validate_manifest_when_server_not_applicable() throws IOException {
    try (HotfixFixture f = HotfixFixture.create(tmp)) {
      Path zip = f.buildWebInf();
      f.fake.adapter.identity = FakeJrsAdapter.identity("10.0.0");
      Plan plan = f.ops().planApply(zip, SIGNED);
      assertThat(plan.summary().warnings()).anyMatch(w -> w.contains("10.0.0"));
      RunOutcome outcome = f.run(plan, "r-version");
      assertThat(outcome).isInstanceOf(RunOutcome.PrecheckFailed.class);
      assertThat(((RunOutcome.PrecheckFailed) outcome).message()).contains("10.0.0");
    }
  }

  @Test
  void should_land_files_stop_and_start_service_and_record_installed_when_run_succeeds()
      throws IOException {
    try (HotfixFixture f = HotfixFixture.create(tmp)) {
      Plan plan = f.ops().planApply(f.buildWebInf(), SIGNED);
      String oldFoo = f.sha(HotfixFixture.FOO);
      RunOutcome outcome = f.run(plan, "r-apply");
      assertThat(outcome).isInstanceOf(RunOutcome.Succeeded.class);

      assertThat(Files.readString(f.target(HotfixFixture.FOO))).isEqualTo(HotfixFixture.NEW_FOO);
      assertThat(Files.readString(f.target(HotfixFixture.FIX))).isEqualTo(HotfixFixture.FIX_BYTES);
      assertThat(f.target(HotfixFixture.BAR)).doesNotExist();
      assertThat(f.target(HotfixFixture.FOO_OLDER)).doesNotExist();
      assertThat(f.fake.platform.controller.events).containsExactly("stop", "start");
      assertThat(f.fake.home.runDir("r-apply").resolve("bundle").resolve("manifest.json")).exists();

      List<HotfixInstalled> list = f.ops().list();
      assertThat(list).hasSize(1);
      HotfixInstalled installed = list.get(0);
      assertThat(installed.id()).isEqualTo(HotfixFixture.ID);
      assertThat(installed.state()).isEqualTo(HotfixState.INSTALLED);
      assertThat(installed.installedRunId()).isEqualTo("r-apply");
      assertThat(installed.snapshotRef()).contains("r-apply/snapshot");

      List<HotfixFile> rows = f.store().hotfixFiles(HotfixFixture.ID);
      assertThat(rows).hasSize(4);
      HotfixFile foo =
          rows.stream()
              .filter(r -> r.path().equals(f.target(HotfixFixture.FOO)))
              .findFirst()
              .orElseThrow();
      assertThat(foo.action()).isEqualTo("replace");
      assertThat(foo.beforeSha256()).contains(oldFoo);
      assertThat(foo.afterSha256()).contains(f.sha(HotfixFixture.FOO));
      HotfixFile fix =
          rows.stream()
              .filter(r -> r.path().equals(f.target(HotfixFixture.FIX)))
              .findFirst()
              .orElseThrow();
      assertThat(fix.action()).isEqualTo("add");
      assertThat(fix.beforeSha256()).isEmpty();
      assertThat(rows.stream().filter(r -> r.action().equals("delete")).count()).isEqualTo(2);
      assertThat(f.store().auditRows(10).stream().map(AuditEntry::action))
          .contains(ApplySteps.AUDIT_APPLIED);
    }
  }

  @Test
  void should_probe_the_server_uncached_when_waiting_for_a_restart() throws IOException {
    // Regression: WaitForServer used to call identity(), which RestJrsAdapter memoises. After a
    // service stop it returned the pre-stop value, so the step passed without reaching the server
    // and the next command ran against a server that was still starting.
    try (HotfixFixture f = HotfixFixture.create(tmp)) {
      f.fake.adapter.calls.clear();
      RunOutcome outcome = f.run(f.ops().planApply(f.buildWebInf(), SIGNED), "r-wait");
      assertThat(outcome).isInstanceOf(RunOutcome.Succeeded.class);
      assertThat(f.fake.platform.controller.events).containsExactly("stop", "start");
      assertThat(f.fake.adapter.calls).contains("refreshIdentity");
    }
  }

  @Test
  void should_never_touch_service_when_restart_none() throws IOException {
    try (HotfixFixture f = HotfixFixture.create(tmp)) {
      RunOutcome outcome = f.run(f.ops().planApply(f.buildNone(), SIGNED), "r-none");
      assertThat(outcome).isInstanceOf(RunOutcome.Succeeded.class);
      assertThat(Files.readString(f.target(HotfixFixture.SCRIPT)))
          .isEqualTo(HotfixFixture.SCRIPT_BYTES);
      assertThat(f.fake.platform.controller.events).isEmpty();
    }
  }

  @Test
  void should_roll_back_swap_and_leave_no_installed_row_when_postcheck_fails() throws IOException {
    try (HotfixFixture f = HotfixFixture.create(tmp)) {
      Path zip =
          f.build(
              f.bundleDir(
                  "badpost",
                  HotfixFixture.WEBINF_MANIFEST.replace(
                      "\"postchecks\": [ { \"type\": \"fileExists\", \"path\": \""
                          + HotfixFixture.FIX
                          + "\" } ]",
                      "\"postchecks\": [ { \"type\": \"fileExists\", \"path\": \"webapps/jasperserver-pro/nope.txt\" } ]"),
                  HotfixFixture.WEBINF_FILES));
      String oldFoo = f.sha(HotfixFixture.FOO);
      String olderFoo = f.sha(HotfixFixture.FOO_OLDER);
      String bar = f.sha(HotfixFixture.BAR);
      RunOutcome outcome = f.run(f.ops().planApply(zip, SIGNED), "r-badpost");
      assertThat(outcome).isInstanceOf(RunOutcome.RolledBack.class);
      assertThat(((RunOutcome.RolledBack) outcome).cause()).contains("postchecks failed");

      assertThat(f.sha(HotfixFixture.FOO)).isEqualTo(oldFoo);
      assertThat(f.sha(HotfixFixture.FOO_OLDER)).isEqualTo(olderFoo);
      assertThat(f.sha(HotfixFixture.BAR)).isEqualTo(bar);
      assertThat(f.target(HotfixFixture.FIX)).doesNotExist();
      assertThat(f.ops().list()).isEmpty();
      assertThat(f.fake.platform.controller.events)
          .containsExactly("stop", "start", "stop", "start");
      assertThat(f.fake.platform.serviceState)
          .isEqualTo(com.jaspersoft.jrsctl.core.platform.ServiceController.State.RUNNING);
    }
  }

  @Test
  void should_converge_when_atomic_swap_executes_twice() throws IOException {
    try (HotfixFixture f = HotfixFixture.create(tmp)) {
      Plan plan = f.ops().planApply(f.buildWebInf(), SIGNED);
      assertThat(f.run(plan, "r-twice")).isInstanceOf(RunOutcome.Succeeded.class);
      String foo = f.sha(HotfixFixture.FOO);
      String fix = f.sha(HotfixFixture.FIX);
      Context ctx = f.ctx("r-twice");
      StepResult again = HotfixFixture.step(plan, "atomic-swap").execute(ctx, EventSink.discard());
      assertThat(again).isInstanceOf(StepResult.Ok.class);
      assertThat(f.sha(HotfixFixture.FOO)).isEqualTo(foo);
      assertThat(f.sha(HotfixFixture.FIX)).isEqualTo(fix);
      assertThat(f.target(HotfixFixture.BAR)).doesNotExist();
      assertThat(f.target(HotfixFixture.FOO_OLDER)).doesNotExist();
    }
  }

  @Test
  void should_refuse_run_when_target_changed_since_plan() throws IOException {
    try (HotfixFixture f = HotfixFixture.create(tmp)) {
      Path zip = f.buildWebInf();
      Plan plan = f.ops().planApply(zip, SIGNED);
      HotfixFixture.write(f.target(HotfixFixture.FOO), "someone edited this");
      Plan recomputed = f.ops().planApply(zip, SIGNED);
      assertThat(plan.fingerprint().matches(recomputed.fingerprint())).isFalse();
      assertThat(plan.fingerprint().changedKeys(recomputed.fingerprint()))
          .containsExactly("target:" + HotfixFixture.FOO);
    }
  }

  private static Path unsigned(HotfixFixture f) throws IOException {
    return Zips.rewrite(
        f.buildWebInf(),
        f.root.resolve("unsigned.zip"),
        Map.of("SIGNATURE", Optional.empty()),
        Map.of());
  }
}
