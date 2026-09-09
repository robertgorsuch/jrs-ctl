package com.jaspersoft.jrsctl.core.state;

import com.jaspersoft.jrsctl.core.engine.CheckResult;
import com.jaspersoft.jrsctl.core.engine.Context;
import com.jaspersoft.jrsctl.core.engine.Plan;
import com.jaspersoft.jrsctl.core.engine.RunOptions;
import com.jaspersoft.jrsctl.core.engine.RunOutcome;
import com.jaspersoft.jrsctl.core.engine.Runner;
import com.jaspersoft.jrsctl.core.engine.Step;
import com.jaspersoft.jrsctl.core.engine.StepState;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Crash recovery for pending runs (spec §5.5, §6.6): lists runs without a terminal state, rebuilds
 * a run's step states from the journal, and either resumes from the interrupted step or rolls the
 * run back. Invariants: resume re-runs the interrupted step's precheck first and, if it fails,
 * returns {@link RunOutcome.PrecheckFailed} without touching the run so rollback stays available; a
 * run that already began rolling back can only be rolled back; both operations execute through the
 * {@link Runner}, so they hold the run lock and journal every transition.
 */
public final class Recovery {

  private final StateStore store;
  private final Runner runner;

  public Recovery(StateStore store, Runner runner) {
    this.store = Objects.requireNonNull(store, "store");
    this.runner = Objects.requireNonNull(runner, "runner");
  }

  /** Runs without a terminal state, oldest first. */
  public static List<RunRecord> pendingRuns(StateStore store) {
    return store.pendingRuns();
  }

  public List<RunRecord> pendingRuns() {
    return store.pendingRuns();
  }

  /** Last recorded state of every step of {@code runId}, in first-seen order. */
  public Map<String, StepState> journal(String runId) {
    Map<String, StepState> last = new LinkedHashMap<>();
    for (Transition t : store.transitions(runId)) {
      last.put(t.stepId(), StepState.valueOf(t.toState()));
    }
    return Map.copyOf(last).isEmpty() ? Map.of() : java.util.Collections.unmodifiableMap(last);
  }

  /**
   * Index of the step a resume starts from: the last step recorded PENDING, RUNNING or FAILED, or
   * the step after the last SUCCEEDED one when the run stopped between steps.
   */
  public static int resumeIndex(Plan plan, Map<String, StepState> journal) {
    int interrupted = -1;
    int lastCompleted = -1;
    List<Step> steps = plan.steps();
    for (int i = 0; i < steps.size(); i++) {
      StepState st = journal.get(steps.get(i).id());
      if (st == null) {
        continue;
      }
      switch (st) {
        case PENDING, RUNNING, FAILED -> interrupted = i;
        case SUCCEEDED, SKIPPED -> lastCompleted = i;
        case ROLLED_BACK, ROLLBACK_FAILED -> {}
      }
    }
    return interrupted >= 0 ? interrupted : lastCompleted + 1;
  }

  /** Re-executes the interrupted step (idempotent) and continues the plan to the end. */
  public RunOutcome resume(Plan plan, String runId, Context ctx, RunOptions opts) {
    requirePending(runId);
    Map<String, StepState> journal = journal(runId);
    Context runCtx = forRun(ctx, runId);
    if (journal.containsValue(StepState.ROLLED_BACK)
        || journal.containsValue(StepState.ROLLBACK_FAILED)) {
      return new RunOutcome.PrecheckFailed(
          firstRolledBack(journal),
          "run " + runId + " already began rolling back; resume is not available",
          "run `jrsctl runs recover " + runId + " --rollback`");
    }
    int start = resumeIndex(plan, journal);
    if (start < plan.steps().size()) {
      Step step = plan.steps().get(start);
      CheckResult pre;
      try {
        pre = step.precheck(runCtx);
      } catch (RuntimeException e) {
        pre = CheckResult.fail(e.getClass().getSimpleName() + ": " + e.getMessage(), "");
      }
      if (pre instanceof CheckResult.Fail fail) {
        return new RunOutcome.PrecheckFailed(
            step.id(),
            "precheck of interrupted step " + step.id() + " failed: " + fail.message(),
            ("only rollback is available: run `jrsctl runs recover "
                    + runId
                    + " --rollback`. "
                    + fail.remediation())
                .strip());
      }
    }
    return runner.resume(plan, runCtx, opts, start, journal);
  }

  /** Compensates every succeeded step of the run in reverse and ends it as ROLLED_BACK. */
  public RunOutcome rollback(Plan plan, String runId, Context ctx) {
    requirePending(runId);
    Map<String, StepState> journal = journal(runId);
    return runner.rollback(
        plan, forRun(ctx, runId), journal, "operator requested rollback of run " + runId);
  }

  private void requirePending(String runId) {
    RunRecord run =
        store.run(runId).orElseThrow(() -> new IllegalArgumentException("unknown run " + runId));
    if (!run.pending()) {
      throw new IllegalStateException(
          "run " + runId + " already ended with state " + run.terminalState().orElseThrow());
    }
  }

  private static Context forRun(Context ctx, String runId) {
    return ctx.runId().equals(runId)
        ? ctx
        : new Context(runId, ctx.home(), ctx.platform(), ctx.cancel(), ctx.services());
  }

  private static String firstRolledBack(Map<String, StepState> journal) {
    return journal.entrySet().stream()
        .filter(
            e -> e.getValue() == StepState.ROLLED_BACK || e.getValue() == StepState.ROLLBACK_FAILED)
        .map(Map.Entry::getKey)
        .findFirst()
        .orElse("");
  }
}
