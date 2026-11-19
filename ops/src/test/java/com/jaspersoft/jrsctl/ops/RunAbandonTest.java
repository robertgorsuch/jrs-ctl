package com.jaspersoft.jrsctl.ops;

import static org.assertj.core.api.Assertions.assertThat;

import com.jaspersoft.jrsctl.core.engine.RunLock;
import com.jaspersoft.jrsctl.core.engine.TerminalState;
import com.jaspersoft.jrsctl.core.state.StateStore;
import com.jaspersoft.jrsctl.core.state.StoredPlan;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Issue #350, ADR-0066: the preview of {@code runs abandon} and the guarded end it writes. */
class RunAbandonTest {

  private static final Instant T0 = Instant.parse("2026-09-08T09:00:00Z");

  @TempDir Path tmp;

  private StateStore opened;

  @AfterEach
  void close() {
    if (opened != null) {
      opened.close();
    }
  }

  private static void step(StateStore store, String runId, String stepId, String... states) {
    Optional<String> from = Optional.empty();
    for (String state : states) {
      store.appendTransition(runId, stepId, "apply", from, state, Optional.empty());
      from = Optional.of(state);
    }
  }

  private static void pendingRun(StateStore store) {
    store.savePlan(
        new StoredPlan(
            "p-1",
            "upgrade",
            "{}",
            "{\"steps\":[{\"id\":\"backup\"},{\"id\":\"export.stop-service\"},"
                + "{\"id\":\"vendor-upgrade\"},{\"id\":\"export.start-service\"}]}",
            "sha256:x",
            T0,
            T0.plusSeconds(1800),
            Optional.of("r-1")));
    store.recordRunStart("r-1", "upgrade", Optional.of("p-1"), T0);
    step(store, "r-1", "backup", "PENDING", "RUNNING", "SUCCEEDED");
    step(store, "r-1", "export.stop-service", "PENDING", "RUNNING", "SUCCEEDED");
    step(store, "r-1", "vendor-upgrade", "PENDING", "RUNNING");
  }

  @Test
  void should_list_steps_by_last_state_and_flag_the_stopped_service_when_previewing()
      throws Exception {
    FakeServices fake = FakeServices.in(tmp.resolve("home"));
    Services services = fake.build();
    StateStore store = services.stateStore().get();
    opened = store;
    pendingRun(store);

    RunAbandon.Preview preview = RunAbandon.preview(store, store.run("r-1").orElseThrow());

    assertThat(preview.completed()).containsExactly("backup", "export.stop-service");
    assertThat(preview.interrupted()).containsExactly("vendor-upgrade");
    assertThat(preview.rolledBack()).isEmpty();
    assertThat(preview.notStarted()).containsExactly("export.start-service");
    assertThat(preview.serviceLeftStopped()).isTrue();
  }

  @Test
  void should_not_flag_the_service_when_a_compensation_started_it_again() throws Exception {
    FakeServices fake = FakeServices.in(tmp.resolve("home"));
    Services services = fake.build();
    StateStore store = services.stateStore().get();
    opened = store;
    pendingRun(store);
    store.appendTransition(
        "r-1",
        "export.stop-service",
        "apply",
        Optional.of("SUCCEEDED"),
        "ROLLED_BACK",
        Optional.empty());

    RunAbandon.Preview preview = RunAbandon.preview(store, store.run("r-1").orElseThrow());

    assertThat(preview.serviceLeftStopped()).isFalse();
    assertThat(preview.rolledBack()).containsExactly("export.stop-service");
  }

  @Test
  void should_end_the_run_once_and_report_it_not_pending_when_abandoned_again() throws Exception {
    FakeServices fake = FakeServices.in(tmp.resolve("home"));
    Services services = fake.build();
    StateStore store = services.stateStore().get();
    opened = store;
    pendingRun(store);

    RunAbandon.Outcome first = RunAbandon.abandon(services, "r-1", "operator", T0.plusSeconds(60));
    RunAbandon.Outcome again = RunAbandon.abandon(services, "r-1", "operator", T0.plusSeconds(61));

    assertThat(first).isEqualTo(new RunAbandon.Outcome.Abandoned("r-1"));
    assertThat(again).isEqualTo(new RunAbandon.Outcome.NotPending("r-1"));
    assertThat(store.run("r-1").orElseThrow().terminalState()).contains(TerminalState.FAILED);
    assertThat(store.run("r-1").orElseThrow().exitCode()).contains(RunAbandon.EXIT_CODE);
    assertThat(store.abandonedRunIds()).containsExactly("r-1");
  }

  @Test
  void should_write_nothing_when_another_run_holds_the_lock() throws Exception {
    FakeServices fake = FakeServices.in(tmp.resolve("home"));
    Services services = fake.build();
    StateStore store = services.stateStore().get();
    opened = store;
    pendingRun(store);

    RunAbandon.Outcome outcome;
    try (RunLock held = new RunLock(fake.home, "r-other", T0)) {
      outcome = RunAbandon.abandon(services, "r-1", "operator", T0.plusSeconds(60));
    }

    assertThat(outcome).isInstanceOf(RunAbandon.Outcome.LockHeld.class);
    assertThat(((RunAbandon.Outcome.LockHeld) outcome).heldByRunId()).isEqualTo("r-other");
    assertThat(store.run("r-1").orElseThrow().pending()).isTrue();
    assertThat(store.abandonedRunIds()).isEmpty();
  }
}
