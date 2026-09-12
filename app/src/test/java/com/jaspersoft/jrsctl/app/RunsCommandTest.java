package com.jaspersoft.jrsctl.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.jaspersoft.jrsctl.core.JrsctlHome;
import com.jaspersoft.jrsctl.core.engine.Plan;
import com.jaspersoft.jrsctl.core.engine.TerminalState;
import com.jaspersoft.jrsctl.core.json.Json;
import com.jaspersoft.jrsctl.core.state.StateStore;
import com.jaspersoft.jrsctl.core.state.StoredPlan;
import com.jaspersoft.jrsctl.ops.PlanJson;
import com.jaspersoft.jrsctl.ops.PlanRegistry;
import com.jaspersoft.jrsctl.ops.hotfix.HotfixOperations;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RunsCommandTest {

  private static final Instant T0 = Instant.parse("2026-09-01T10:00:00Z");

  @TempDir Path tmp;

  private FakeHotfixOperations fake;
  private Path home;
  private Path bundle;

  @BeforeEach
  void setUp() throws Exception {
    fake = new FakeHotfixOperations();
    HotfixOps.factory = services -> fake;
    home = Files.createDirectories(tmp.resolve("home"));
    bundle = tmp.resolve("hf.zip");
    Files.writeString(bundle, "zip");
  }

  @AfterEach
  void tearDown() {
    HotfixOps.factory = HotfixOps.DEFAULT_FACTORY;
  }

  private StateStore open() {
    return StateStore.open(new JrsctlHome(home), Clock.systemUTC());
  }

  /** Stores the fake apply plan under {@code planId} and returns it. */
  private Plan storePlan(StateStore store, String planId, Optional<String> consumedBy) {
    Plan plan = fake.planApply(bundle, new HotfixOperations.ApplyOptions(false));
    store.savePlan(
        new StoredPlan(
            planId,
            PlanRegistry.HOTFIX_APPLY,
            PlanRegistry.applyArgs(bundle, false),
            PlanJson.toJson(plan),
            plan.fingerprint().value(),
            T0,
            T0.plus(Duration.ofMinutes(30)),
            consumedBy));
    return plan;
  }

  private void seedFinishedRun(StateStore store) {
    storePlan(store, "plan-1", Optional.of("r-1"));
    store.recordRunStart("r-1", "hotfix.apply", Optional.of("plan-1"), T0);
    store.appendTransition(
        "r-1", "verify-signature", "verify", Optional.empty(), "PENDING", Optional.empty());
    store.appendTransition(
        "r-1", "verify-signature", "verify", Optional.of("PENDING"), "RUNNING", Optional.empty());
    store.appendTransition(
        "r-1",
        "verify-signature",
        "verify",
        Optional.of("RUNNING"),
        "SUCCEEDED",
        Optional.of("key customer"));
    store.recordRunEnd("r-1", T0.plusSeconds(42), TerminalState.SUCCEEDED, 0);
  }

  private void seedPendingRun(StateStore store, String runId, String planId) {
    storePlan(store, planId, Optional.of(runId));
    store.recordRunStart(runId, "hotfix.apply", Optional.of(planId), T0);
    store.appendTransition(
        runId, "verify-signature", "verify", Optional.empty(), "PENDING", Optional.empty());
    store.appendTransition(
        runId, "verify-signature", "verify", Optional.of("PENDING"), "RUNNING", Optional.empty());
    store.appendTransition(
        runId, "verify-signature", "verify", Optional.of("RUNNING"), "SUCCEEDED", Optional.empty());
    store.appendTransition(
        runId, "validate-manifest", "verify", Optional.empty(), "PENDING", Optional.empty());
    store.appendTransition(
        runId, "validate-manifest", "verify", Optional.of("PENDING"), "RUNNING", Optional.empty());
  }

  @Test
  void should_list_runs_with_duration_and_outcome_when_runs_recorded() throws Exception {
    try (StateStore store = open()) {
      seedFinishedRun(store);
    }

    InitCommandTest.Run text =
        InitCommandTest.run("runs", "list", "--home", home.toString(), "--no-color");
    InitCommandTest.Run json =
        InitCommandTest.run("runs", "list", "--json", "--home", home.toString());

    assertThat(text.code()).isZero();
    assertThat(text.out()).contains("r-1").contains("hotfix.apply").contains("SUCCEEDED (exit 0)");
    assertThat(text.out()).contains("42.0s");
    JsonNode rows = Json.mapper().readTree(json.out());
    assertThat(rows.get(0).get("runId").asText()).isEqualTo("r-1");
    assertThat(rows.get(0).get("terminalState").asText()).isEqualTo("SUCCEEDED");
    assertThat(rows.get(0).get("durationMillis").asLong()).isEqualTo(42_000L);
  }

  @Test
  void should_show_plan_summary_transitions_and_backups_when_run_exists() throws Exception {
    try (StateStore store = open()) {
      seedFinishedRun(store);
    }

    InitCommandTest.Run text =
        InitCommandTest.run("runs", "show", "r-1", "--home", home.toString(), "--no-color");
    InitCommandTest.Run json =
        InitCommandTest.run("runs", "show", "r-1", "--json", "--home", home.toString());

    assertThat(text.code()).as(text.err()).isZero();
    assertThat(text.out())
        .contains("Run  r-1  hotfix.apply  SUCCEEDED (exit 0)")
        .contains("Plan")
        .contains("target")
        .contains(FakeHotfixOperations.ID)
        .contains("Steps")
        .contains("verify-signature")
        .contains("RUNNING -> SUCCEEDED")
        .contains("key customer")
        .contains("Backups");
    JsonNode root = Json.mapper().readTree(json.out());
    assertThat(root.get("run").get("runId").asText()).isEqualTo("r-1");
    assertThat(root.get("plan").get("summary").get("target").asText())
        .isEqualTo(FakeHotfixOperations.ID);
    assertThat(root.get("transitions")).hasSize(3);
  }

  @Test
  void should_exit_2_when_run_is_unknown() {
    InitCommandTest.Run show =
        InitCommandTest.run("runs", "show", "r-nope", "--home", home.toString());
    InitCommandTest.Run recover =
        InitCommandTest.run("runs", "recover", "r-nope", "--resume", "--home", home.toString());

    assertThat(show.code()).isEqualTo(ExitCodes.PRECHECK_FAILED);
    assertThat(show.err()).contains("unknown run r-nope");
    assertThat(recover.code()).isEqualTo(ExitCodes.PRECHECK_FAILED);
    assertThat(recover.err()).contains("unknown run r-nope");
  }

  @Test
  void should_exit_1_when_neither_resume_nor_rollback_given() {
    InitCommandTest.Run run =
        InitCommandTest.run("runs", "recover", "r-1", "--home", home.toString());

    assertThat(run.code()).isEqualTo(ExitCodes.USAGE);
  }

  @Test
  void should_exit_2_when_run_already_ended() {
    try (StateStore store = open()) {
      seedFinishedRun(store);
    }

    InitCommandTest.Run run =
        InitCommandTest.run("runs", "recover", "r-1", "--rollback", "--home", home.toString());

    assertThat(run.code()).isEqualTo(ExitCodes.PRECHECK_FAILED);
    assertThat(run.err()).contains("already ended").contains("SUCCEEDED");
  }

  @Test
  void should_resume_from_interrupted_step_when_resume_flag_given() {
    try (StateStore store = open()) {
      seedPendingRun(store, "r-2", "plan-2");
    }

    InitCommandTest.Run run =
        InitCommandTest.run(
            "runs", "recover", "r-2", "--resume", "--yes", "--home", home.toString(), "--no-color");

    assertThat(run.code()).as(run.out() + run.err()).isZero();
    assertThat(fake.executed)
        .containsExactly(
            "validate-manifest", "snapshot", "stage-files", "atomic-swap", "record-installed");
    assertThat(run.out()).contains("run r-2").contains("OK    02  Validate manifest");
    try (StateStore store = open()) {
      assertThat(store.run("r-2").get().terminalState()).contains(TerminalState.SUCCEEDED);
      assertThat(store.auditRows(5)).anyMatch(a -> a.action().equals("runs.recover"));
    }
  }

  @Test
  void should_compensate_succeeded_steps_and_exit_3_when_rollback_flag_given() {
    try (StateStore store = open()) {
      seedPendingRun(store, "r-3", "plan-3");
    }

    InitCommandTest.Run run =
        InitCommandTest.run(
            "runs",
            "recover",
            "r-3",
            "--rollback",
            "--yes",
            "--home",
            home.toString(),
            "--no-color");

    assertThat(run.code()).as(run.out() + run.err()).isEqualTo(ExitCodes.FAILED_ROLLED_BACK);
    assertThat(fake.executed).isEmpty();
    assertThat(fake.compensated).containsExactly("validate-manifest", "verify-signature");
    assertThat(run.out()).contains("UNDO  01  Verify signature");
    try (StateStore store = open()) {
      assertThat(store.run("r-3").get().terminalState()).contains(TerminalState.ROLLED_BACK);
    }
  }

  @Test
  void should_exit_2_when_pending_run_has_no_stored_plan() {
    try (StateStore store = open()) {
      store.recordRunStart("r-4", "hotfix.apply", Optional.empty(), T0);
    }

    InitCommandTest.Run run =
        InitCommandTest.run("runs", "recover", "r-4", "--resume", "--home", home.toString());

    assertThat(run.code()).isEqualTo(ExitCodes.PRECHECK_FAILED);
    assertThat(run.err()).contains("no stored plan");
  }
}
