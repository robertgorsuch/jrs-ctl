package com.jaspersoft.jrsctl.core.engine;

import com.jaspersoft.jrsctl.core.event.Event;
import com.jaspersoft.jrsctl.core.event.EventSink;
import com.jaspersoft.jrsctl.core.state.RunLock;
import com.jaspersoft.jrsctl.core.state.StateStore;
import com.jaspersoft.jrsctl.core.state.TerminalState;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Executes a {@link Plan} step by step under the run lock (spec §6.3-§6.6). Invariants: a plan
 * whose recomputed fingerprint differs is refused before anything is touched; every step state
 * change is journalled to {@code step_transitions} in its own transaction <em>before</em> the
 * matching event is emitted, so the journal is always at least as advanced as what observers saw;
 * {@code Retryable} failures are retried per the step's {@link RetryPolicy} and become {@code
 * Recoverable} when exhausted; {@code Recoverable} failures compensate succeeded mutating steps in
 * reverse back to the failing step's phase boundary (or the whole plan with {@code rollbackAll});
 * {@code Fatal} failures never compensate; cancellation compensates the in-flight step and then
 * every succeeded mutating step; irreversible steps are skipped during compensation; the run row
 * always receives a terminal state and exit code before the Runner returns. Steps that throw are
 * treated as {@code Recoverable} with the exception as the cause. {@link
 * com.jaspersoft.jrsctl.core.state.LockHeldException} propagates untouched so the CLI can map it to
 * exit code 9.
 */
public final class Runner {

  /** Phase name carried by run-level events. */
  public static final String RUN_PHASE = "run";

  private final StateStore store;
  private final EventSink sink;
  private final Clock clock;
  private final Sleeper sleeper;

  public Runner(StateStore store, EventSink sink, Clock clock, Sleeper sleeper) {
    this.store = Objects.requireNonNull(store, "store");
    this.sink = Objects.requireNonNull(sink, "sink");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
  }

  /** Runs a fresh plan; {@code ctx.runId()} names the run. */
  public RunOutcome run(Plan plan, Context ctx, PlanFingerprint recomputed, RunOptions opts) {
    if (!plan.fingerprint().matches(recomputed)) {
      return new RunOutcome.FingerprintMismatch(plan.fingerprint().changedKeys(recomputed));
    }
    try (RunLock unusedLock = new RunLock(ctx.home(), ctx.runId(), clock.instant())) {
      store.recordRunStart(
          ctx.runId(), plan.summary().operation(), Optional.of(plan.planId()), clock.instant());
      return new Execution(plan, ctx, opts, Map.of()).proceed(0);
    }
  }

  /**
   * Continues a pending run from {@code startIndex}. {@code journal} holds the last recorded state
   * of every step seen so far; steps recorded {@code SUCCEEDED} take part in any later rollback.
   */
  public RunOutcome resume(
      Plan plan, Context ctx, RunOptions opts, int startIndex, Map<String, StepState> journal) {
    if (startIndex < 0 || startIndex > plan.steps().size()) {
      throw new IllegalArgumentException("startIndex out of range: " + startIndex);
    }
    try (RunLock unusedLock = new RunLock(ctx.home(), ctx.runId(), clock.instant())) {
      return new Execution(plan, ctx, opts, journal).proceed(startIndex);
    }
  }

  /**
   * Compensates every step of a pending run that the journal records as {@code SUCCEEDED} (and a
   * mutating step still {@code RUNNING}), in reverse order, then ends the run.
   */
  public RunOutcome rollback(Plan plan, Context ctx, Map<String, StepState> journal, String cause) {
    try (RunLock unusedLock = new RunLock(ctx.home(), ctx.runId(), clock.instant())) {
      return new Execution(plan, ctx, RunOptions.DEFAULT, journal).rollbackRecorded(cause);
    }
  }

  private String describe(RuntimeException e) {
    String msg = e.getMessage();
    return msg == null || msg.isBlank()
        ? e.getClass().getSimpleName()
        : e.getClass().getSimpleName() + ": " + msg;
  }

  /** Why a compensation could not complete. */
  private record RollbackFailure(String stepId, String cause, List<Path> backups) {}

  /** What happened to one step, as seen by the main loop. */
  private sealed interface StepOutcome {
    record Done() implements StepOutcome {}

    record PrecheckFailed(String stepId, String message, String remediation)
        implements StepOutcome {}

    record Failure(StepFailure failure) implements StepOutcome {}

    record Cancelled(String reason, Optional<RollbackFailure> inFlight) implements StepOutcome {}
  }

  /** Mutable state of one execution; one instance per run/resume/rollback call. */
  private final class Execution {
    private final Plan plan;
    private final Context ctx;
    private final RunOptions opts;
    private final String runId;
    private final Instant runStart;
    private final Map<String, StepState> states = new HashMap<>();
    private final List<Integer> succeededMutating = new ArrayList<>();
    private boolean mutated;

    Execution(Plan plan, Context ctx, RunOptions opts, Map<String, StepState> journal) {
      this.plan = plan;
      this.ctx = ctx;
      this.opts = opts;
      this.runId = ctx.runId();
      this.runStart = clock.instant();
      states.putAll(journal);
      List<Step> steps = plan.steps();
      for (int i = 0; i < steps.size(); i++) {
        Step s = steps.get(i);
        StepState st = journal.get(s.id());
        if (st == null || !s.mutating()) {
          continue;
        }
        switch (st) {
          case SUCCEEDED -> {
            succeededMutating.add(i);
            mutated = true;
          }
          case RUNNING, FAILED, ROLLBACK_FAILED -> mutated = true;
          case PENDING, ROLLED_BACK, SKIPPED -> {}
        }
      }
    }

    RunOutcome proceed(int startIndex) {
      emit(
          new Event.PlanCreated(
              now(), runId, RUN_PHASE, plan.planId(), plan.fingerprint().value()));
      List<Step> steps = plan.steps();
      for (int i = startIndex; i < steps.size(); i++) {
        if (ctx.cancel().isCancelled()) {
          return cancelled(ctx.cancel().reason(), Optional.empty());
        }
        StepOutcome outcome = runStep(i);
        int index = i;
        Optional<RunOutcome> terminal =
            switch (outcome) {
              case StepOutcome.Done d -> Optional.empty();
              case StepOutcome.PrecheckFailed p -> Optional.of(precheckFailed(p));
              case StepOutcome.Failure f -> Optional.of(failed(index, f.failure()));
              case StepOutcome.Cancelled c -> Optional.of(cancelled(c.reason(), c.inFlight()));
            };
        if (terminal.isPresent()) {
          return terminal.get();
        }
      }
      return succeeded();
    }

    RunOutcome rollbackRecorded(String cause) {
      List<Step> steps = plan.steps();
      List<Integer> targets = new ArrayList<>(succeededMutating);
      for (int i = 0; i < steps.size(); i++) {
        if (states.get(steps.get(i).id()) == StepState.RUNNING && steps.get(i).mutating()) {
          targets.add(i);
        }
      }
      targets.sort(null);
      Optional<RollbackFailure> failure = compensate(targets);
      if (failure.isPresent()) {
        RollbackFailure rf = failure.get();
        return finishFailed(
            new RunOutcome.Failed(
                "rollback of run "
                    + runId
                    + " incomplete at step "
                    + rf.stepId()
                    + ": "
                    + rf.cause(),
                true,
                "restore the listed backups manually, then run `jrsctl doctor`",
                rf.backups()));
      }
      String phase = steps.isEmpty() ? RUN_PHASE : steps.get(0).phase();
      return finishRolledBack(phase, cause);
    }

    private StepOutcome runStep(int index) {
      Step step = plan.steps().get(index);
      Optional<String> stepId = Optional.of(step.id());
      transition(step, StepState.PENDING, Optional.empty());
      emit(new Event.StepPending(now(), runId, stepId, step.phase(), step.title()));

      CheckResult pre = check(() -> step.precheck(ctx));
      switch (pre) {
        case CheckResult.Pass p -> {}
        case CheckResult.Warn w ->
            emit(
                new Event.Log(
                    now(), runId, stepId, step.phase(), Event.Log.Level.WARN, w.message()));
        case CheckResult.Fail f -> {
          String cause = "precheck failed: " + f.message();
          transition(step, StepState.FAILED, Optional.of(cause));
          StepFailure.Recoverable failure = StepFailure.recoverable(cause, f.remediation());
          emit(new Event.StepFailed(now(), runId, stepId, step.phase(), failure));
          if (!mutated) {
            return new StepOutcome.PrecheckFailed(step.id(), f.message(), f.remediation());
          }
          return new StepOutcome.Failure(failure);
        }
      }

      transition(step, StepState.RUNNING, Optional.empty());
      emit(new Event.StepRunning(now(), runId, stepId, step.phase(), step.title()));
      Instant started = now();
      if (step.mutating()) {
        mutated = true;
      }
      RetryPolicy policy = step.retryPolicy();
      int attempt = 1;
      while (true) {
        StepResult result;
        try {
          result = step.execute(ctx, sink);
        } catch (CancellationToken.CancelledException e) {
          return cancelInFlight(step, e.getMessage());
        } catch (RuntimeException e) {
          result =
              StepResult.failed(
                  StepFailure.recoverable(
                      describe(e), "inspect the log for step " + step.id() + " and retry the run"));
        }
        Optional<StepFailure> failure =
            switch (result) {
              case StepResult.Ok ok -> postcheckFailure(step);
              case StepResult.Failed f -> Optional.of(f.failure());
            };
        if (failure.isEmpty()) {
          transition(step, StepState.SUCCEEDED, Optional.empty());
          emit(
              new Event.StepSucceeded(
                  now(), runId, stepId, step.phase(), Duration.between(started, now()).toMillis()));
          if (step.mutating()) {
            succeededMutating.add(index);
          }
          return new StepOutcome.Done();
        }
        StepFailure sf = failure.get();
        if (sf instanceof StepFailure.Retryable retryable && attempt < policy.maxAttempts()) {
          attempt++;
          Duration delay = policy.delayBefore(attempt);
          transition(
              step,
              StepState.RUNNING,
              Optional.of(
                  "retry " + attempt + "/" + policy.maxAttempts() + ": " + retryable.cause()));
          emit(
              new Event.StepRetry(
                  now(),
                  runId,
                  stepId,
                  step.phase(),
                  attempt,
                  policy.maxAttempts(),
                  delay.toMillis(),
                  retryable.cause()));
          try {
            sleeper.sleep(delay);
          } catch (CancellationToken.CancelledException e) {
            return cancelInFlight(step, e.getMessage());
          }
          continue;
        }
        int attempts = attempt;
        StepFailure finalFailure =
            switch (sf) {
              case StepFailure.Retryable r ->
                  new StepFailure.Recoverable(
                      r.cause() + " (gave up after " + attempts + " attempts)",
                      r.affectedPaths(),
                      r.affectedUris(),
                      r.backups(),
                      r.nextAction());
              case StepFailure.Recoverable r -> r;
              case StepFailure.Fatal r -> r;
            };
        transition(step, StepState.FAILED, Optional.of(finalFailure.cause()));
        emit(new Event.StepFailed(now(), runId, stepId, step.phase(), finalFailure));
        return new StepOutcome.Failure(finalFailure);
      }
    }

    private Optional<StepFailure> postcheckFailure(Step step) {
      CheckResult post = check(() -> step.postcheck(ctx));
      return switch (post) {
        case CheckResult.Pass p -> Optional.empty();
        case CheckResult.Warn w -> {
          emit(
              new Event.Log(
                  now(),
                  runId,
                  Optional.of(step.id()),
                  step.phase(),
                  Event.Log.Level.WARN,
                  w.message()));
          yield Optional.empty();
        }
        case CheckResult.Fail f ->
            Optional.of(
                StepFailure.recoverable("postcheck failed: " + f.message(), f.remediation()));
      };
    }

    private StepOutcome cancelInFlight(Step step, String reason) {
      String why = reason == null || reason.isBlank() ? "cancelled" : reason;
      transition(step, StepState.FAILED, Optional.of("cancelled: " + why));
      emit(
          new Event.StepFailed(
              now(),
              runId,
              Optional.of(step.id()),
              step.phase(),
              StepFailure.recoverable(
                  "cancelled: " + why, "none; jrsctl compensates the step automatically")));
      Optional<RollbackFailure> inFlight = step.mutating() ? compensateOne(step) : Optional.empty();
      return new StepOutcome.Cancelled(why, inFlight);
    }

    private RunOutcome failed(int index, StepFailure failure) {
      return switch (failure) {
        case StepFailure.Recoverable r -> {
          int from = opts.rollbackAll() ? 0 : phaseStart(index);
          List<Integer> targets = succeededMutating.stream().filter(i -> i >= from).toList();
          Optional<RollbackFailure> rf = compensate(targets);
          if (rf.isPresent()) {
            List<Path> backups = new ArrayList<>(r.backups());
            backups.addAll(rf.get().backups());
            yield finishFailed(
                new RunOutcome.Failed(
                    r.cause()
                        + "; rollback incomplete at step "
                        + rf.get().stepId()
                        + ": "
                        + rf.get().cause(),
                    true,
                    r.nextAction(),
                    backups));
          }
          yield finishRolledBack(plan.steps().get(from).phase(), r.cause());
        }
        case StepFailure.Fatal f ->
            finishFailed(new RunOutcome.Failed(f.cause(), mutated, f.nextAction(), f.backups()));
        case StepFailure.Retryable r ->
            throw new IllegalStateException("retryable failures are converted before this point");
      };
    }

    private RunOutcome cancelled(String reason, Optional<RollbackFailure> inFlight) {
      Optional<RollbackFailure> rf =
          inFlight.isPresent() ? inFlight : compensate(List.copyOf(succeededMutating));
      if (rf.isPresent()) {
        return finishFailed(
            new RunOutcome.Failed(
                "cancelled: "
                    + reason
                    + "; rollback incomplete at step "
                    + rf.get().stepId()
                    + ": "
                    + rf.get().cause(),
                true,
                "restore the listed backups manually, then run `jrsctl doctor`",
                rf.get().backups()));
      }
      return finishCancelled(reason);
    }

    private int phaseStart(int index) {
      List<Step> steps = plan.steps();
      String phase = steps.get(index).phase();
      int start = index;
      while (start > 0 && steps.get(start - 1).phase().equals(phase)) {
        start--;
      }
      return start;
    }

    /** Compensates the given step indices in reverse order; stops at the first failure. */
    private Optional<RollbackFailure> compensate(List<Integer> indices) {
      List<Step> steps = plan.steps();
      for (int k = indices.size() - 1; k >= 0; k--) {
        Optional<RollbackFailure> rf = compensateOne(steps.get(indices.get(k)));
        if (rf.isPresent()) {
          return rf;
        }
      }
      return Optional.empty();
    }

    private Optional<RollbackFailure> compensateOne(Step step) {
      Optional<String> stepId = Optional.of(step.id());
      if (step.irreversible()) {
        transition(step, StepState.SKIPPED, Optional.of("irreversible"));
        emit(new Event.StepSkipped(now(), runId, stepId, step.phase(), "irreversible"));
        return Optional.empty();
      }
      Instant started = now();
      StepResult result;
      try {
        result = step.compensate(ctx, sink);
      } catch (RuntimeException e) {
        result =
            StepResult.failed(
                StepFailure.recoverable(describe(e), "restore step " + step.id() + " manually"));
      }
      return switch (result) {
        case StepResult.Ok ok -> {
          transition(step, StepState.ROLLED_BACK, Optional.empty());
          emit(
              new Event.StepRolledBack(
                  now(), runId, stepId, step.phase(), Duration.between(started, now()).toMillis()));
          yield Optional.empty();
        }
        case StepResult.Failed f -> {
          transition(step, StepState.ROLLBACK_FAILED, Optional.of(f.failure().cause()));
          emit(
              new Event.StepRollbackFailed(
                  now(), runId, stepId, step.phase(), f.failure().cause(), f.failure().backups()));
          yield Optional.of(
              new RollbackFailure(step.id(), f.failure().cause(), f.failure().backups()));
        }
      };
    }

    private RunOutcome succeeded() {
      store.recordRunEnd(runId, now(), TerminalState.SUCCEEDED, 0);
      emit(
          new Event.RunSucceeded(
              now(), runId, RUN_PHASE, Duration.between(runStart, now()).toMillis()));
      return new RunOutcome.Succeeded();
    }

    private RunOutcome precheckFailed(StepOutcome.PrecheckFailed p) {
      RunOutcome.PrecheckFailed outcome =
          new RunOutcome.PrecheckFailed(p.stepId(), p.message(), p.remediation());
      store.recordRunEnd(runId, now(), TerminalState.PRECHECK_FAILED, outcome.exitCode());
      emit(
          new Event.RunFailed(
              now(),
              runId,
              RUN_PHASE,
              "precheck of step " + p.stepId() + " failed: " + p.message(),
              List.of(),
              p.remediation(),
              false));
      return outcome;
    }

    private RunOutcome finishRolledBack(String phase, String cause) {
      RunOutcome.RolledBack outcome = new RunOutcome.RolledBack(phase, cause);
      store.recordRunEnd(runId, now(), TerminalState.ROLLED_BACK, outcome.exitCode());
      emit(new Event.RunRolledBack(now(), runId, RUN_PHASE, phase, cause));
      return outcome;
    }

    private RunOutcome finishFailed(RunOutcome.Failed outcome) {
      store.recordRunEnd(runId, now(), TerminalState.FAILED, outcome.exitCode());
      emit(
          new Event.RunFailed(
              now(),
              runId,
              RUN_PHASE,
              outcome.cause(),
              outcome.backups(),
              outcome.nextAction(),
              outcome.rollbackIncomplete()));
      return outcome;
    }

    private RunOutcome finishCancelled(String reason) {
      RunOutcome.Cancelled outcome = new RunOutcome.Cancelled(reason);
      store.recordRunEnd(runId, now(), TerminalState.CANCELLED, outcome.exitCode());
      emit(new Event.RunCancelled(now(), runId, RUN_PHASE, reason));
      return outcome;
    }

    private void transition(Step step, StepState to, Optional<String> detail) {
      Optional<String> from = Optional.ofNullable(states.get(step.id())).map(StepState::name);
      store.appendTransition(runId, step.id(), step.phase(), from, to.name(), detail);
      states.put(step.id(), to);
    }

    private CheckResult check(Supplier<CheckResult> check) {
      try {
        return check.get();
      } catch (RuntimeException e) {
        return CheckResult.fail(describe(e), "inspect the log and fix the reported condition");
      }
    }

    private void emit(Event event) {
      sink.emit(event);
    }

    private Instant now() {
      return clock.instant();
    }
  }
}
