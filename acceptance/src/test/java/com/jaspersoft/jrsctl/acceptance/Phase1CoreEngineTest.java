package com.jaspersoft.jrsctl.acceptance;

import static org.assertj.core.api.Assertions.assertThat;

import com.jaspersoft.jrsctl.core.JrsctlHome;
import com.jaspersoft.jrsctl.core.engine.CancellationToken;
import com.jaspersoft.jrsctl.core.engine.CheckResult;
import com.jaspersoft.jrsctl.core.engine.Context;
import com.jaspersoft.jrsctl.core.engine.Plan;
import com.jaspersoft.jrsctl.core.engine.PlanFingerprint;
import com.jaspersoft.jrsctl.core.engine.PlanSummary;
import com.jaspersoft.jrsctl.core.engine.RunIds;
import com.jaspersoft.jrsctl.core.engine.RunOptions;
import com.jaspersoft.jrsctl.core.engine.RunOutcome;
import com.jaspersoft.jrsctl.core.engine.Runner;
import com.jaspersoft.jrsctl.core.engine.Sleeper;
import com.jaspersoft.jrsctl.core.engine.Step;
import com.jaspersoft.jrsctl.core.engine.StepFailure;
import com.jaspersoft.jrsctl.core.engine.StepResult;
import com.jaspersoft.jrsctl.core.event.Event;
import com.jaspersoft.jrsctl.core.event.EventSink;
import com.jaspersoft.jrsctl.core.platform.Platforms;
import com.jaspersoft.jrsctl.core.redact.Redactor;
import com.jaspersoft.jrsctl.core.state.LockHeldException;
import com.jaspersoft.jrsctl.core.state.Recovery;
import com.jaspersoft.jrsctl.core.state.RunLock;
import com.jaspersoft.jrsctl.core.state.StateStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Spec §14 Phase 1, exercised through the public engine API on a real state store and the real
 * platform layer, in a throw-away {@code JRSCTL_HOME}. Cross-process lock contention is covered by
 * Phase 3 once a mutating CLI command exists.
 */
@Tag("phase1")
class Phase1CoreEngineTest {

  @TempDir Path tmp;

  @Test
  void forced_failure_at_step_4_rolls_back_steps_4_3_2_1_and_exits_3() throws Exception {
    JrsctlHome home = new JrsctlHome(Files.createDirectories(tmp.resolve("home")));
    List<String> log = new ArrayList<>();
    List<Event> events = new ArrayList<>();
    Plan plan = plan(log, 4);
    try (StateStore store = StateStore.open(home)) {
      Runner runner = new Runner(store, events::add, Clock.systemUTC(), Sleeper.none());
      RunOutcome outcome = runner.run(plan, context(home), plan.fingerprint(), RunOptions.DEFAULT);
      assertThat(outcome).isInstanceOf(RunOutcome.RolledBack.class);
      assertThat(outcome.exitCode()).isEqualTo(3);
      assertThat(log)
          .containsExactly(
              "exec 1", "exec 2", "exec 3", "exec 4", "undo 4", "undo 3", "undo 2", "undo 1");
      assertThat(store.transitions(runIdOf(events))).isNotEmpty();
      assertThat(Recovery.pendingRuns(store)).isEmpty();
    }
  }

  @Test
  void fingerprint_mismatch_refuses_to_run_and_mutates_nothing() throws Exception {
    JrsctlHome home = new JrsctlHome(Files.createDirectories(tmp.resolve("home2")));
    List<String> log = new ArrayList<>();
    Plan plan = plan(log, 99);
    try (StateStore store = StateStore.open(home)) {
      Runner runner = new Runner(store, EventSink.discard(), Clock.systemUTC(), Sleeper.none());
      PlanFingerprint changed = PlanFingerprint.of(Map.of("target", "sha256:different"));
      RunOutcome outcome = runner.run(plan, context(home), changed, RunOptions.DEFAULT);
      assertThat(outcome).isInstanceOf(RunOutcome.FingerprintMismatch.class);
      assertThat(outcome.exitCode()).isEqualTo(2);
      assertThat(log).isEmpty();
    }
  }

  @Test
  void second_lock_on_the_same_home_is_refused_with_the_holder() throws Exception {
    JrsctlHome home = new JrsctlHome(Files.createDirectories(tmp.resolve("home3")));
    try (RunLock first = new RunLock(home, "r-first", Instant.now())) {
      assertThat(first.runId()).isEqualTo("r-first");
      try {
        new RunLock(home, "r-second", Instant.now()).close();
        throw new AssertionError("second lock should have been refused");
      } catch (LockHeldException e) {
        assertThat(e.getMessage()).contains("r-first");
      }
    }
  }

  @Test
  void redactor_hides_secrets_in_raw_base64_and_url_forms() {
    Redactor r = new Redactor();
    r.register("s3cr3t-p@ss");
    String base64 =
        java.util.Base64.getEncoder()
            .encodeToString("s3cr3t-p@ss".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    String url = java.net.URLEncoder.encode("s3cr3t-p@ss", java.nio.charset.StandardCharsets.UTF_8);
    String out = r.redact("a s3cr3t-p@ss b " + base64 + " c " + url);
    assertThat(out).doesNotContain("s3cr3t-p@ss").doesNotContain(base64).doesNotContain(url);
  }

  private Context context(JrsctlHome home) {
    return new Context(
        RunIds.next(Clock.systemUTC()),
        home,
        Platforms.detect(),
        new CancellationToken(),
        Map.of());
  }

  private static String runIdOf(List<Event> events) {
    return events.get(0).runId();
  }

  private static Plan plan(List<String> log, int failAt) {
    List<Step> steps = new ArrayList<>();
    for (int i = 1; i <= 5; i++) {
      steps.add(new FakeStep(i, i == failAt, log));
    }
    PlanSummary summary =
        new PlanSummary(
            "acceptance",
            "fake",
            List.of(),
            List.of(),
            false,
            List.of(),
            Map.of(),
            "none",
            List.of());
    return new Plan(
        "plan-" + failAt, steps, summary, PlanFingerprint.of(Map.of("target", "sha256:same")));
  }

  private record FakeStep(int n, boolean fail, List<String> log) implements Step {
    @Override
    public String id() {
      return "s" + n;
    }

    @Override
    public String title() {
      return "step " + n;
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
      log.add("exec " + n);
      return fail
          ? StepResult.failed(StepFailure.recoverable("forced failure", "none"))
          : StepResult.ok();
    }

    @Override
    public StepResult compensate(Context ctx, EventSink out) {
      log.add("undo " + n);
      return StepResult.ok();
    }
  }
}
