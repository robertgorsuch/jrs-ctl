package com.jaspersoft.jrsctl.app;

import com.jaspersoft.jrsctl.core.config.Config;
import com.jaspersoft.jrsctl.core.engine.CancellationToken;
import com.jaspersoft.jrsctl.core.engine.Context;
import com.jaspersoft.jrsctl.core.engine.Plan;
import com.jaspersoft.jrsctl.core.engine.RunIds;
import com.jaspersoft.jrsctl.core.engine.RunOptions;
import com.jaspersoft.jrsctl.core.engine.RunOutcome;
import com.jaspersoft.jrsctl.core.engine.Runner;
import com.jaspersoft.jrsctl.core.engine.Sleeper;
import com.jaspersoft.jrsctl.core.event.EventBus;
import com.jaspersoft.jrsctl.core.keys.KeyRing;
import com.jaspersoft.jrsctl.core.redact.Redactor;
import com.jaspersoft.jrsctl.core.secrets.SecretResolver;
import com.jaspersoft.jrsctl.core.snapshot.SnapshotStore;
import com.jaspersoft.jrsctl.core.state.LockHeldException;
import com.jaspersoft.jrsctl.core.state.Recovery;
import com.jaspersoft.jrsctl.core.state.RunRecord;
import com.jaspersoft.jrsctl.core.state.StateStore;
import com.jaspersoft.jrsctl.core.state.StoredPlan;
import com.jaspersoft.jrsctl.jrs.api.JrsAdapter;
import com.jaspersoft.jrsctl.ops.Services;
import com.jaspersoft.jrsctl.ops.exim.DeferredJrsAdapter;
import java.io.PrintWriter;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * The one path every mutating command takes from a {@link Plan} to a process exit code (spec §5.5,
 * §6). Invariants: a pending run blocks every new mutating command with exit 8 and the exact {@code
 * runs recover} command; the plan is stored with a 30-minute TTL before it is shown, so {@code
 * --plan} output can be rebuilt later; nothing runs without {@code --yes} or an explicit
 * confirmation, and a non-interactive caller without {@code --yes} exits 2; the plan is claimed for
 * its run id before the first step so it can never execute twice and survives plan expiry while the
 * run is pending; Ctrl-C cancels through the run's single {@link CancellationToken} and waits up to
 * 30 s for the in-flight step to finish or compensate; the exit code is {@link
 * RunOutcome#exitCode()}, or 9 when the run lock is held. The run context registers {@link
 * Services}, {@link StateStore}, {@link Config}, {@link SnapshotStore}, {@link KeyRing}, {@link
 * Redactor}, {@link SecretResolver} and a {@link JrsAdapter} that connects on first use, for the
 * steps.
 */
final class PlanExecutor {

  static final Duration PLAN_TTL = Duration.ofMinutes(30);
  static final Duration SHUTDOWN_GRACE = Duration.ofSeconds(30);

  /** What a command asks the executor to do with a plan. */
  record Request(
      Plan plan, String operation, String argsJson, boolean showOnly, boolean rollbackAll) {
    Request {
      Objects.requireNonNull(plan, "plan");
      Objects.requireNonNull(operation, "operation");
      Objects.requireNonNull(argsJson, "argsJson");
    }
  }

  private final Services services;
  private final GlobalOptions global;
  private final PrintWriter out;
  private final PrintWriter err;
  private final Ansi ansi;
  private final Redactor redactor;

  PlanExecutor(
      Services services,
      GlobalOptions global,
      PrintWriter out,
      PrintWriter err,
      Map<String, String> env) {
    this.services = Objects.requireNonNull(services, "services");
    this.global = Objects.requireNonNull(global, "global");
    this.out = Objects.requireNonNull(out, "out");
    this.err = Objects.requireNonNull(err, "err");
    this.ansi = Ansi.forStdout(global.noColor(), env);
    this.redactor = services.redactor();
  }

  /** Shows, confirms and runs a fresh plan; returns the process exit code. */
  int execute(Request request) {
    StateStore store = services.stateStore().get();
    List<RunRecord> pending = Recovery.pendingRuns(store);
    if (!pending.isEmpty()) {
      return pendingRuns(pending);
    }
    Plan plan = request.plan();
    Instant now = services.clock().instant();
    store.expirePlans(now);
    String planJson = PlanPrinter.toJson(plan);
    store.savePlan(
        new StoredPlan(
            plan.planId(),
            request.operation(),
            request.argsJson(),
            planJson,
            plan.fingerprint().value(),
            now,
            now.plus(PLAN_TTL),
            Optional.empty()));
    if (global.json()) {
      out.println(redactor.redact(planJson));
      out.flush();
    } else {
      PlanPrinter.print(out, plan, ansi, redactor);
    }
    if (request.showOnly()) {
      return ExitCodes.SUCCESS;
    }
    if (!global.yes()) {
      if (global.json() || !services.interactive()) {
        fail(
            "confirmation required: pass --yes to run this plan without asking,"
                + " or --plan to only show it");
        return ExitCodes.PRECHECK_FAILED;
      }
      out.println();
      if (!Confirm.ask(out, "Run this plan? [y/N] ")) {
        out.println("not run; nothing has changed");
        out.flush();
        return ExitCodes.SUCCESS;
      }
    }
    String runId = RunIds.next(services.clock());
    if (!store.consumePlan(plan.planId(), runId, services.clock().instant())) {
      fail("plan " + plan.planId() + " has expired or was already run; plan again");
      return ExitCodes.PRECHECK_FAILED;
    }
    Context ctx = context(runId, store);
    RunOptions opts = request.rollbackAll() ? RunOptions.withRollbackAll() : RunOptions.DEFAULT;
    return run(plan, ctx, runner -> runner.run(plan, ctx, plan.fingerprint(), opts));
  }

  /** Resumes or rolls back a pending run through {@link Recovery}; bypasses the pending check. */
  int recover(String runId, Plan plan, boolean resume) {
    StateStore store = services.stateStore().get();
    Context ctx = context(runId, store);
    return run(
        plan,
        ctx,
        runner -> {
          Recovery recovery = new Recovery(store, runner);
          return resume
              ? recovery.resume(plan, runId, ctx, RunOptions.DEFAULT)
              : recovery.rollback(plan, runId, ctx);
        });
  }

  private int pendingRuns(List<RunRecord> pending) {
    err.println(
        redactor.redact(
            "error: "
                + pending.size()
                + (pending.size() == 1 ? " run needs" : " runs need")
                + " recovery before anything else can run:"));
    for (RunRecord run : pending) {
      err.println(
          redactor.redact(
              "  " + run.runId() + "  " + run.operation() + "  started " + run.startedAt()));
    }
    err.println(
        "run `jrsctl runs recover "
            + pending.get(0).runId()
            + " --resume` to continue it, or `jrsctl runs recover "
            + pending.get(0).runId()
            + " --rollback` to undo it");
    err.flush();
    return ExitCodes.RECOVERY_REQUIRED;
  }

  private Context context(String runId, StateStore store) {
    return new Context(
        runId,
        services.home(),
        services.platform(),
        new CancellationToken(),
        Map.of(
            Services.class,
            services,
            StateStore.class,
            store,
            Config.class,
            services.config(),
            SnapshotStore.class,
            new SnapshotStore(services.home(), services.platform().files(), services.clock()),
            KeyRing.class,
            new KeyRing(services.home()),
            JrsAdapter.class,
            new DeferredJrsAdapter(services.adapter()),
            Redactor.class,
            services.redactor(),
            SecretResolver.class,
            services.secrets()));
  }

  private int run(Plan plan, Context ctx, Function<Runner, RunOutcome> body) {
    StateStore store = services.stateStore().get();
    EventBus bus = new EventBus();
    ProgressRenderer renderer = new ProgressRenderer(plan, out, ansi, redactor, global.json());
    bus.subscribe(renderer);
    Runner runner = new Runner(store, bus, services.clock(), Sleeper.system());
    if (!global.json()) {
      out.println();
      out.println("run " + ctx.runId());
      out.flush();
    }
    AtomicReference<RunOutcome> result = new AtomicReference<>();
    AtomicReference<RuntimeException> failure = new AtomicReference<>();
    Thread worker =
        new Thread(
            () -> {
              try {
                result.set(body.apply(runner));
              } catch (RuntimeException e) {
                failure.set(e);
              }
            },
            "jrsctl-run");
    Thread hook =
        new Thread(
            () -> {
              ctx.cancel().cancel("interrupted");
              try {
                worker.join(SHUTDOWN_GRACE.toMillis());
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
            },
            "jrsctl-shutdown");
    Runtime.getRuntime().addShutdownHook(hook);
    worker.start();
    try {
      worker.join();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      ctx.cancel().cancel("interrupted");
      try {
        worker.join(SHUTDOWN_GRACE.toMillis());
      } catch (InterruptedException again) {
        Thread.currentThread().interrupt();
      }
    } finally {
      try {
        Runtime.getRuntime().removeShutdownHook(hook);
      } catch (IllegalStateException shuttingDown) {
        // the JVM is already going down; the hook is doing the cancelling
      }
    }
    RuntimeException thrown = failure.get();
    if (thrown != null) {
      if (thrown instanceof LockHeldException held) {
        fail(
            "run lock is held by run "
                + held.holderRunId()
                + " (pid "
                + held.holderPid()
                + "); wait for it to finish or check `jrsctl runs list`");
        return ExitCodes.LOCK_HELD;
      }
      throw thrown;
    }
    RunOutcome outcome = result.get();
    if (outcome == null) {
      fail("run " + ctx.runId() + " was interrupted before it reported an outcome");
      return ExitCodes.CANCELLED;
    }
    renderer.outcome(ctx.runId(), outcome);
    return outcome.exitCode();
  }

  private void fail(String message) {
    err.println(redactor.redact("error: " + message));
    err.flush();
  }
}
