package com.jaspersoft.jrsctl.app.console;

import com.fasterxml.jackson.databind.JsonNode;
import com.jaspersoft.jrsctl.app.RunService;
import com.jaspersoft.jrsctl.core.config.ConfigException;
import com.jaspersoft.jrsctl.core.engine.Plan;
import com.jaspersoft.jrsctl.core.engine.RunOptions;
import com.jaspersoft.jrsctl.core.event.Event;
import com.jaspersoft.jrsctl.core.event.EventBus;
import com.jaspersoft.jrsctl.core.json.Json;
import com.jaspersoft.jrsctl.core.redact.Redactor;
import com.jaspersoft.jrsctl.core.state.HotfixInstalled;
import com.jaspersoft.jrsctl.core.state.RunRecord;
import com.jaspersoft.jrsctl.core.state.StateStore;
import com.jaspersoft.jrsctl.core.state.StoredPlan;
import com.jaspersoft.jrsctl.core.state.Transition;
import com.jaspersoft.jrsctl.jrs.api.JrsUnreachableException;
import com.jaspersoft.jrsctl.ops.Services;
import io.javalin.config.RoutesConfig;
import io.javalin.http.Context;
import io.javalin.http.sse.SseClient;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The console's HTTP routes (spec §13.1, {@code web/README.md}). Invariants: every JSON body is
 * built by {@link ConsoleViews} and redacted on its way out; {@code POST /api/run} rebuilds the
 * stored plan through the same builder that created it, refuses with 409 when the fingerprint
 * differs and 410 when the plan expired or was already run, and never executes a plan whose
 * arguments it cannot rebuild; the event stream replays the journal (and, after the run, its
 * terminal event) before it goes live, sends a heartbeat comment every 15 s and closes itself after
 * the terminal event; cancel, rollback and resume act through the same {@link RunManager} the run
 * came from; console actions leave audit rows.
 */
final class ConsoleApi {

  static final long HEARTBEAT_SECONDS = 15;
  private static final Logger LOG = LoggerFactory.getLogger(ConsoleApi.class);
  private static final String JSON = "application/json";
  private static final String ACTOR = "console";

  private final ConsoleServer server;
  private final Services services;
  private final RunService runs;
  private final RunManager manager;
  private final OperationCatalog catalog;
  private final ConsoleViews views;
  private final DoctorCache doctor;
  private final SupportBundle bundle;
  private final ScheduledExecutorService heartbeats;
  private final Redactor redactor;

  ConsoleApi(
      ConsoleServer server,
      Services services,
      RunService runs,
      RunManager manager,
      OperationCatalog catalog,
      ConsoleViews views,
      DoctorCache doctor,
      SupportBundle bundle,
      ScheduledExecutorService heartbeats) {
    this.server = Objects.requireNonNull(server, "server");
    this.services = Objects.requireNonNull(services, "services");
    this.runs = Objects.requireNonNull(runs, "runs");
    this.manager = Objects.requireNonNull(manager, "manager");
    this.catalog = Objects.requireNonNull(catalog, "catalog");
    this.views = Objects.requireNonNull(views, "views");
    this.doctor = Objects.requireNonNull(doctor, "doctor");
    this.bundle = Objects.requireNonNull(bundle, "bundle");
    this.heartbeats = Objects.requireNonNull(heartbeats, "heartbeats");
    this.redactor = services.redactor();
  }

  void register(RoutesConfig app) {
    app.get("/", this::index);
    app.post("/api/auth/launch", this::exchangeLaunchCode);
    app.get("/api/health", ctx -> json(ctx, 200, views.health()));
    app.get("/api/server", ctx -> json(ctx, 200, views.server()));
    app.post("/api/plan", this::plan);
    app.post("/api/run", this::run);
    app.get("/api/runs", ctx -> json(ctx, 200, views.runList()));
    app.get("/api/runs/{id}", ctx -> json(ctx, 200, views.runDetail(runOf(ctx))));
    app.sse("/api/runs/{id}/events", this::events);
    app.post("/api/runs/{id}/cancel", this::cancel);
    app.post("/api/runs/{id}/rollback", this::rollback);
    app.post("/api/runs/{id}/resume", this::resume);
    app.get("/api/runs/{id}/support-bundle", this::supportBundle);
    app.get("/api/doctor", ctx -> json(ctx, 200, ConsoleViews.doctor(doctor.current())));
    app.get("/api/hotfixes", ctx -> json(ctx, 200, views.hotfixes()));
    app.exception(
        ConsoleHttpException.class, (e, ctx) -> json(ctx, e.status(), Map.of("error", message(e))));
    app.exception(ConfigException.class, (e, ctx) -> json(ctx, 400, Map.of("error", message(e))));
    app.exception(
        JrsUnreachableException.class, (e, ctx) -> json(ctx, 503, Map.of("error", message(e))));
    app.exception(
        Exception.class,
        (e, ctx) -> {
          LOG.warn("console request {} {} failed", ctx.method(), ctx.path(), e);
          json(ctx, 500, Map.of("error", message(e)));
        });
    app.error(
        404,
        ctx -> {
          if (ctx.path().startsWith("/api/")) {
            json(ctx, 404, Map.of("error", "not found"));
          }
        });
  }

  // ---- static entry point ---------------------------------------------------------------------

  private void exchangeLaunchCode(Context ctx) {
    JsonNode body = body(ctx);
    String code = body.path("code").asText("");
    if (code.isBlank()) {
      throw ConsoleHttpException.badRequest("code is required");
    }
    String token =
        server
            .exchangeLaunchCode(code)
            .orElseThrow(() -> new ConsoleHttpException(401, "invalid or expired launch code"));
    ctx.status(200).contentType(JSON).result(Json.write(Map.of("token", token)));
  }

  private void index(Context ctx) {
    InputStream in = ConsoleApi.class.getResourceAsStream("/web/index.html");
    if (in == null) {
      throw ConsoleHttpException.notFound("console UI is not bundled in this build");
    }
    ctx.contentType("text/html; charset=utf-8").result(in);
  }

  // ---- plans and runs -------------------------------------------------------------------------

  private void plan(Context ctx) {
    JsonNode body = body(ctx);
    String op = body.path("op").asText("");
    if (op.isBlank()) {
      throw ConsoleHttpException.badRequest("op is required");
    }
    if (!OperationCatalog.known(op)) {
      throw ConsoleHttpException.badRequest(
          "unknown operation '"
              + op
              + "'; one of "
              + String.join(", ", OperationCatalog.OPERATIONS));
    }
    OperationCatalog.Planned planned;
    try {
      planned = catalog.plan(op, body.path("args"));
    } catch (UnsupportedOperationException e) {
      throw ConsoleHttpException.notImplemented("operation not available in this build");
    } catch (IllegalArgumentException e) {
      throw ConsoleHttpException.badRequest(message(e));
    }
    StoredPlan stored = runs.storePlan(planned.plan(), op, planned.argsJson());
    audit("console.plan", op + " " + stored.planId());
    json(ctx, 200, views.planResponse(stored, planned.plan()));
  }

  private void run(Context ctx) {
    JsonNode body = body(ctx);
    String planId = body.path("planId").asText("");
    if (planId.isBlank()) {
      throw ConsoleHttpException.badRequest("planId is required");
    }
    if (!body.path("confirm").asBoolean(false)) {
      throw ConsoleHttpException.badRequest("confirm must be true to run a plan");
    }
    List<RunRecord> pending = runs.pendingRuns();
    if (!pending.isEmpty()) {
      throw ConsoleHttpException.conflict(
          "run "
              + pending.get(0).runId()
              + " needs recovery first; resume or roll it back from its run page");
    }
    StateStore store = runs.store();
    StoredPlan stored =
        store
            .loadPlan(planId)
            .orElseThrow(() -> ConsoleHttpException.notFound("unknown plan " + planId));
    Instant now = services.clock().instant();
    if (stored.consumedByRunId().isPresent()) {
      throw ConsoleHttpException.gone("plan " + planId + " was already run; build it again");
    }
    if (!stored.expiresAt().isAfter(now)) {
      throw ConsoleHttpException.gone("plan " + planId + " has expired; build it again");
    }
    Plan rebuilt;
    try {
      rebuilt = catalog.rebuild(stored.operation(), stored.argsJson());
    } catch (UnsupportedOperationException e) {
      throw ConsoleHttpException.notImplemented("operation not available in this build");
    } catch (RuntimeException e) {
      throw ConsoleHttpException.conflict("plan can no longer be rebuilt: " + message(e));
    }
    if (!rebuilt.fingerprint().value().equals(stored.fingerprint())) {
      throw ConsoleHttpException.conflict(
          "inputs changed since planning (fingerprint differs); build the plan again");
    }
    Plan plan =
        new Plan(stored.planId(), rebuilt.steps(), rebuilt.summary(), rebuilt.fingerprint());
    String runId =
        runs.claim(planId)
            .orElseThrow(
                () ->
                    ConsoleHttpException.gone(
                        "plan " + planId + " has expired or was already run; build it again"));
    audit("console.run.start", runId + " " + stored.operation() + " plan " + planId);
    launched(ctx, manager.start(plan, runId, RunOptions.DEFAULT));
  }

  private void cancel(Context ctx) {
    RunRecord run = runOf(ctx);
    if (!manager.cancel(run.runId(), "cancelled from the console")) {
      throw ConsoleHttpException.conflict("run " + run.runId() + " is not running in this console");
    }
    audit("console.cancel", run.runId());
    json(ctx, 200, Map.of("runId", run.runId(), "cancelled", true));
  }

  private void rollback(Context ctx) {
    RunRecord run = runOf(ctx);
    boolean live = manager.find(run.runId()).map(RunManager.LiveRun::running).orElse(false);
    if (live) {
      throw ConsoleHttpException.conflict(
          "run " + run.runId() + " is still running; cancel it first");
    }
    if (run.pending()) {
      Plan plan = recoveryPlan(run);
      audit("console.rollback", run.runId() + " (pending run)");
      launched(ctx, manager.rollback(plan, run.runId()));
      return;
    }
    Optional<HotfixInstalled> hotfix = views.installedBy(run.runId());
    if (hotfix.isEmpty()) {
      throw ConsoleHttpException.conflict(
          "no rollback is available for run "
              + run.runId()
              + "; nothing it installed is still in place");
    }
    if (!runs.pendingRuns().isEmpty()) {
      throw ConsoleHttpException.conflict("another run needs recovery first");
    }
    String argsJson = OperationCatalog.rollbackArgs(hotfix.get().id());
    Plan plan;
    try {
      plan = catalog.rebuild(OperationCatalog.HOTFIX_ROLLBACK, argsJson);
    } catch (UnsupportedOperationException e) {
      throw ConsoleHttpException.notImplemented("operation not available in this build");
    } catch (RuntimeException e) {
      throw ConsoleHttpException.conflict("cannot plan the rollback: " + message(e));
    }
    StoredPlan stored = runs.storePlan(plan, OperationCatalog.HOTFIX_ROLLBACK, argsJson);
    String runId =
        runs.claim(stored.planId())
            .orElseThrow(() -> ConsoleHttpException.conflict("rollback plan could not be claimed"));
    audit("console.rollback", run.runId() + " -> " + runId + " hotfix " + hotfix.get().id());
    launched(ctx, manager.start(plan, runId, RunOptions.DEFAULT));
  }

  private void resume(Context ctx) {
    RunRecord run = runOf(ctx);
    if (!run.pending()) {
      throw ConsoleHttpException.conflict(
          "run " + run.runId() + " already ended; only an interrupted run can be resumed");
    }
    if (manager.find(run.runId()).map(RunManager.LiveRun::running).orElse(false)) {
      throw ConsoleHttpException.conflict("run " + run.runId() + " is still running");
    }
    Plan plan = recoveryPlan(run);
    audit("console.resume", run.runId());
    launched(ctx, manager.resume(plan, run.runId()));
  }

  private Plan recoveryPlan(RunRecord run) {
    StoredPlan stored =
        run.planId()
            .flatMap(id -> runs.store().loadPlan(id))
            .orElseThrow(
                () ->
                    ConsoleHttpException.conflict(
                        "run "
                            + run.runId()
                            + " has no stored plan; restore its backups manually (see `jrsctl runs"
                            + " show "
                            + run.runId()
                            + "`)"));
    try {
      Plan rebuilt = catalog.rebuild(stored.operation(), stored.argsJson());
      return new Plan(stored.planId(), rebuilt.steps(), rebuilt.summary(), rebuilt.fingerprint());
    } catch (UnsupportedOperationException e) {
      throw ConsoleHttpException.notImplemented("operation not available in this build");
    } catch (RuntimeException e) {
      throw ConsoleHttpException.conflict("plan can no longer be rebuilt: " + message(e));
    }
  }

  private void launched(Context ctx, RunManager.Launch launch) {
    switch (launch) {
      case RunManager.Launched l -> json(ctx, 200, Map.of("runId", l.runId()));
      case RunManager.LockHeld h ->
          throw ConsoleHttpException.conflict(
              "run lock is held by run " + h.holderRunId() + " (pid " + h.holderPid() + ")");
      case RunManager.Refused r -> throw ConsoleHttpException.conflict(r.reason());
    }
  }

  // ---- event stream ---------------------------------------------------------------------------

  private void events(SseClient client) {
    String runId = client.ctx().pathParam("id");
    StateStore store = runs.store();
    if (store.run(runId).isEmpty()) {
      throw ConsoleHttpException.notFound("unknown run " + runId);
    }
    client.keepAlive();
    SseSession session = new SseSession(client, redactor);
    ScheduledFuture<?> heartbeat =
        heartbeats.scheduleAtFixedRate(
            session::heartbeat, HEARTBEAT_SECONDS, HEARTBEAT_SECONDS, TimeUnit.SECONDS);
    Thread writer =
        new Thread(
            () -> {
              try {
                stream(runId, session);
              } catch (RuntimeException e) {
                LOG.debug("event stream for {} ended: {}", runId, e.getMessage());
              } finally {
                heartbeat.cancel(false);
                session.close();
              }
            },
            "jrsctl-sse-" + runId);
    writer.setDaemon(true);
    client.onClose(
        () -> {
          session.markClosed();
          heartbeat.cancel(false);
          writer.interrupt();
        });
    writer.start();
  }

  private void stream(String runId, SseSession session) {
    StateStore store = runs.store();
    Optional<RunManager.LiveRun> live = manager.find(runId).filter(RunManager.LiveRun::running);
    Optional<EventBus.Subscription> subscription = live.map(l -> l.bus().subscribe(session));
    try {
      RunRecord run = store.run(runId).orElseThrow();
      Map<String, String> titles = views.stepTitles(run);
      List<Transition> transitions = store.transitions(runId);
      long lastSeq = transitions.isEmpty() ? 0 : transitions.get(transitions.size() - 1).seq();
      for (Event e : SseEvents.replay(runId, transitions, titles)) {
        session.send(e);
      }
      Optional<Event> terminal =
          manager
              .find(runId)
              .flatMap(RunManager.LiveRun::terminal)
              .or(() -> SseEvents.terminal(run, transitions, store.snapshots(runId)));
      if (terminal.isPresent()) {
        session.send(terminal.get());
        return;
      }
      while (!session.closed()) {
        if (live.isEmpty()) {
          Optional<RunManager.LiveRun> now =
              manager.find(runId).filter(RunManager.LiveRun::running);
          if (now.isPresent()) {
            live = now;
            subscription = Optional.of(now.get().bus().subscribe(session));
          }
        }
        Event next = session.poll(1, TimeUnit.SECONDS);
        if (next != null) {
          session.send(next);
          if (SseEvents.terminal(next)) {
            return;
          }
          continue;
        }
        if (live.isPresent()) {
          if (!live.get().running()) {
            drain(session);
            live.get().terminal().ifPresent(session::send);
            return;
          }
          continue;
        }
        // Nothing live in this console: another process may be driving the run; follow the journal.
        List<Transition> fresh = new ArrayList<>();
        for (Transition t : store.transitions(runId)) {
          if (t.seq() > lastSeq) {
            fresh.add(t);
          }
        }
        if (!fresh.isEmpty()) {
          lastSeq = fresh.get(fresh.size() - 1).seq();
          for (Event e : SseEvents.replay(runId, fresh, titles)) {
            session.send(e);
          }
        }
        RunRecord current = store.run(runId).orElseThrow();
        if (current.terminalState().isPresent()) {
          SseEvents.terminal(current, store.transitions(runId), store.snapshots(runId))
              .ifPresent(session::send);
          return;
        }
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } finally {
      subscription.ifPresent(EventBus.Subscription::close);
    }
  }

  private static void drain(SseSession session) throws InterruptedException {
    Event e;
    while ((e = session.poll(0, TimeUnit.MILLISECONDS)) != null) {
      session.send(e);
      if (SseEvents.terminal(e)) {
        return;
      }
    }
  }

  // ---- support bundle -------------------------------------------------------------------------

  private void supportBundle(Context ctx) throws IOException {
    RunRecord run = runOf(ctx);
    ctx.status(200);
    ctx.contentType("application/zip");
    ctx.header(
        "Content-Disposition", "attachment; filename=\"" + run.runId() + "-support-bundle.zip\"");
    bundle.write(run, ctx.outputStream());
  }

  // ---- helpers --------------------------------------------------------------------------------

  private RunRecord runOf(Context ctx) {
    String id = ctx.pathParam("id");
    return runs.store()
        .run(id)
        .orElseThrow(() -> ConsoleHttpException.notFound("unknown run " + id));
  }

  private static JsonNode body(Context ctx) {
    String text = ctx.body();
    if (text.isBlank()) {
      throw ConsoleHttpException.badRequest("a JSON body is required");
    }
    try {
      return Json.mapper().readTree(text);
    } catch (IOException e) {
      throw ConsoleHttpException.badRequest("body is not JSON");
    }
  }

  private void json(Context ctx, int status, Object tree) {
    ctx.status(status).contentType(JSON).result(redactor.redact(Json.write(tree)));
  }

  private String message(Throwable e) {
    String m = e.getMessage();
    return redactor.redact(m == null || m.isBlank() ? e.getClass().getSimpleName() : m);
  }

  private void audit(String action, String detail) {
    try {
      runs.store().audit(ACTOR, action, redactor.redact(detail));
    } catch (RuntimeException e) {
      LOG.warn("cannot write audit row {}: {}", action, e.getMessage());
    }
  }
}
