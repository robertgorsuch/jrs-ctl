package com.jaspersoft.jrsctl.ops;

import com.fasterxml.jackson.databind.JsonNode;
import com.jaspersoft.jrsctl.core.engine.LockHeldException;
import com.jaspersoft.jrsctl.core.engine.RunLock;
import com.jaspersoft.jrsctl.core.engine.RunRecord;
import com.jaspersoft.jrsctl.core.engine.StepState;
import com.jaspersoft.jrsctl.core.engine.Transition;
import com.jaspersoft.jrsctl.core.json.Json;
import com.jaspersoft.jrsctl.core.state.StateStore;
import com.jaspersoft.jrsctl.core.state.StoredPlan;
import com.jaspersoft.jrsctl.jrs.service.ServiceSteps;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * {@code runs abandon} (issue #350, ADR-0066): closes a pending run as it stands, for a server put
 * right another way or a run that can be neither resumed nor undone. Invariants: no step executes
 * or compensates; nothing on the server, no snapshot and no backup is touched; the run is ended as
 * {@code FAILED} with {@link #EXIT_CODE} and a {@code runs.abandon} audit row in one transaction,
 * under the run lock, so a run another process is executing is never closed under it.
 */
public final class RunAbandon {

  /** The exit code an abandoned run is stored with: ended, neither completed nor undone. */
  public static final int EXIT_CODE = 4;

  private RunAbandon() {}

  /**
   * What an operator should see before closing {@code run}: each step by the last state the journal
   * gives it, the plan's steps that never started, and whether the run leaves the service stopped
   * (its last service step to succeed stopped it, and no compensation started it again).
   */
  public record Preview(
      RunRecord run,
      List<String> completed,
      List<String> interrupted,
      List<String> rolledBack,
      List<String> notStarted,
      boolean serviceLeftStopped) {
    public Preview {
      Objects.requireNonNull(run, "run");
      completed = List.copyOf(completed);
      interrupted = List.copyOf(interrupted);
      rolledBack = List.copyOf(rolledBack);
      notStarted = List.copyOf(notStarted);
    }
  }

  /** The outcome of {@link #abandon}. */
  public sealed interface Outcome {
    /** The run is now ended as abandoned. */
    record Abandoned(String runId) implements Outcome {}

    /** The run ended (or was abandoned) before this call took the lock; nothing was written. */
    record NotPending(String runId) implements Outcome {}

    /** Another process holds the run lock; nothing was written. */
    record LockHeld(String heldByRunId, String pid) implements Outcome {}
  }

  /** Builds the preview from the journal and the stored plan; reads only. */
  public static Preview preview(StateStore store, RunRecord run) {
    Map<String, StepState> last = new LinkedHashMap<>();
    boolean stopped = false;
    for (Transition t : store.transitions(run.runId())) {
      StepState state;
      try {
        state = StepState.valueOf(t.toState());
      } catch (IllegalArgumentException e) {
        continue;
      }
      last.put(t.stepId(), state);
      // plans prefix their service steps (export.stop-service, full-export-start-service)
      boolean stop = t.stepId().endsWith(ServiceSteps.STOP);
      boolean start = t.stepId().endsWith(ServiceSteps.START);
      if (stop && state == StepState.SUCCEEDED) {
        stopped = true;
      } else if ((start && state == StepState.SUCCEEDED)
          || (stop && state == StepState.ROLLED_BACK)) {
        stopped = false;
      }
    }
    List<String> completed = new ArrayList<>();
    List<String> interrupted = new ArrayList<>();
    List<String> rolledBack = new ArrayList<>();
    for (Map.Entry<String, StepState> e : last.entrySet()) {
      switch (e.getValue()) {
        case SUCCEEDED, SKIPPED -> completed.add(e.getKey());
        case PENDING, RUNNING, FAILED -> interrupted.add(e.getKey());
        case ROLLED_BACK, ROLLBACK_FAILED -> rolledBack.add(e.getKey());
      }
    }
    List<String> notStarted = new ArrayList<>();
    for (String id : plannedSteps(store, run)) {
      if (!last.containsKey(id)) {
        notStarted.add(id);
      }
    }
    return new Preview(run, completed, interrupted, rolledBack, notStarted, stopped);
  }

  /**
   * Ends {@code runId} as abandoned by {@code actor}, under the run lock. {@link
   * Outcome.NotPending} when it is unknown or no longer pending once the lock is held.
   */
  public static Outcome abandon(Services services, String runId, String actor, Instant now) {
    StateStore store = services.stateStore().get();
    try (RunLock unusedLock = new RunLock(services.home(), runId, now)) {
      boolean ended = store.abandonRun(runId, now, EXIT_CODE, actor);
      return ended ? new Outcome.Abandoned(runId) : new Outcome.NotPending(runId);
    } catch (LockHeldException e) {
      return new Outcome.LockHeld(e.holderRunId(), e.holderPid());
    }
  }

  private static List<String> plannedSteps(StateStore store, RunRecord run) {
    Optional<StoredPlan> stored = run.planId().flatMap(store::loadPlan);
    if (stored.isEmpty()) {
      return List.of();
    }
    List<String> ids = new ArrayList<>();
    try {
      for (JsonNode step : Json.mapper().readTree(stored.get().planJson()).path("steps")) {
        String id = step.path("id").asText("");
        if (!id.isBlank()) {
          ids.add(id);
        }
      }
    } catch (IOException | RuntimeException e) {
      // an unreadable stored plan only loses the "not started" list
      return List.of();
    }
    return ids;
  }
}
