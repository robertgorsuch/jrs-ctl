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
import com.jaspersoft.jrsctl.core.event.EventSink;
import com.jaspersoft.jrsctl.core.keys.KeyRing;
import com.jaspersoft.jrsctl.core.snapshot.SnapshotStore;
import com.jaspersoft.jrsctl.core.state.Recovery;
import com.jaspersoft.jrsctl.core.state.RunRecord;
import com.jaspersoft.jrsctl.core.state.StateStore;
import com.jaspersoft.jrsctl.core.state.StoredPlan;
import com.jaspersoft.jrsctl.ops.Services;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The parts of "plan to run" that the CLI ({@link PlanExecutor}) and the console share (spec §5.5,
 * §6.2, §13.1): the pending-run gate, plan storage with its 30-minute TTL, the one-shot claim of a
 * plan for a run id, the run {@link Context} with every service a step may need, and the {@link
 * Runner} construction. Invariants: a plan is claimed at most once ({@link #claim} returns empty
 * when it is unknown, expired or already consumed); every context carries {@link Services}, {@link
 * StateStore}, {@link Config}, {@link SnapshotStore} and {@link KeyRing}; nothing here prints,
 * prompts or decides exit codes, so the two front ends keep their own presentation.
 */
public final class RunService {

  /** How long a stored plan may be run after it was shown (spec §6.2). */
  public static final Duration PLAN_TTL = Duration.ofMinutes(30);

  private final Services services;

  public RunService(Services services) {
    this.services = Objects.requireNonNull(services, "services");
  }

  public Services services() {
    return services;
  }

  public StateStore store() {
    return services.stateStore().get();
  }

  /** Runs without a terminal state, oldest first; non-empty blocks every new run (spec §5.5). */
  public List<RunRecord> pendingRuns() {
    return Recovery.pendingRuns(store());
  }

  /** Expires stale plans, stores {@code plan} with the TTL and returns the stored row. */
  public StoredPlan storePlan(Plan plan, String operation, String argsJson) {
    StateStore store = store();
    Instant now = services.clock().instant();
    store.expirePlans(now);
    StoredPlan stored =
        new StoredPlan(
            plan.planId(),
            operation,
            argsJson,
            PlanPrinter.toJson(plan),
            plan.fingerprint().value(),
            now,
            now.plus(PLAN_TTL),
            Optional.empty());
    store.savePlan(stored);
    return stored;
  }

  /** Claims {@code planId} for a fresh run id; empty when it cannot be run (spec §6.2). */
  public Optional<String> claim(String planId) {
    String runId = RunIds.next(services.clock());
    return store().consumePlan(planId, runId, services.clock().instant())
        ? Optional.of(runId)
        : Optional.empty();
  }

  /** The context every step of {@code runId} executes in. */
  public Context context(String runId) {
    return new Context(
        runId,
        services.home(),
        services.platform(),
        new CancellationToken(),
        Map.of(
            Services.class,
            services,
            StateStore.class,
            store(),
            Config.class,
            services.config(),
            SnapshotStore.class,
            new SnapshotStore(services.home(), services.platform().files(), services.clock()),
            KeyRing.class,
            new KeyRing(services.home())));
  }

  /** A runner that journals to the state store and reports to {@code sink}. */
  public Runner runner(EventSink sink) {
    return new Runner(store(), sink, services.clock(), Sleeper.system());
  }

  /** Executes a fresh, claimed plan; the fingerprint check is the runner's. */
  public RunOutcome run(Runner runner, Plan plan, Context ctx, RunOptions options) {
    return runner.run(plan, ctx, plan.fingerprint(), options);
  }

  /** Continues a pending run from its interrupted step (spec §6.6). */
  public RunOutcome resume(Runner runner, Plan plan, String runId, Context ctx) {
    return new Recovery(store(), runner).resume(plan, runId, ctx, RunOptions.DEFAULT);
  }

  /** Compensates every succeeded step of a pending run (spec §6.6). */
  public RunOutcome rollback(Runner runner, Plan plan, String runId, Context ctx) {
    return new Recovery(store(), runner).rollback(plan, runId, ctx);
  }
}
