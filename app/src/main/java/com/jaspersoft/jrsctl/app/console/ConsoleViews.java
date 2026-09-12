package com.jaspersoft.jrsctl.app.console;

import com.jaspersoft.jrsctl.core.Version;
import com.jaspersoft.jrsctl.core.engine.Plan;
import com.jaspersoft.jrsctl.core.engine.RunLock;
import com.jaspersoft.jrsctl.core.engine.RunRecord;
import com.jaspersoft.jrsctl.core.engine.StepState;
import com.jaspersoft.jrsctl.core.snapshot.SnapshotStore;
import com.jaspersoft.jrsctl.core.state.HotfixInstalled;
import com.jaspersoft.jrsctl.core.state.StateStore;
import com.jaspersoft.jrsctl.core.state.StoredPlan;
import com.jaspersoft.jrsctl.ops.ReportItem;
import com.jaspersoft.jrsctl.ops.Services;
import com.jaspersoft.jrsctl.ops.doctor.DoctorReport;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.IntSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Builds the JSON documents of the console API in exactly the shapes {@code web/README.md}
 * documents (spec §13.1). Invariants: every document is a plain tree of maps, lists, strings,
 * numbers, booleans and instants so the one JSON mapper renders it deterministically; nothing here
 * mutates the server or the journal (reading the doctor cache is the only slow path); the server
 * document degrades to the configuration-known fields with {@code reachable:false} instead of
 * failing when the server cannot be reached; run outcomes use the README's vocabulary ({@code
 * succeeded}, {@code failed}, {@code failed_rolled_back}, {@code rollback_incomplete}, {@code
 * cancelled}, {@code running}, {@code interrupted}).
 */
final class ConsoleViews {

  private static final Logger LOG = LoggerFactory.getLogger(ConsoleViews.class);
  private static final int RUN_LIMIT = 200;

  private final Services services;
  private final RunManager runs;
  private final DoctorCache doctor;
  private final String bind;
  private final IntSupplier port;
  private final ServerViews serverViews;
  private final RunViews runViews;
  private final HotfixViews hotfixViews;

  ConsoleViews(
      Services services, RunManager runs, DoctorCache doctor, String bind, IntSupplier port) {
    this.services = Objects.requireNonNull(services, "services");
    this.runs = Objects.requireNonNull(runs, "runs");
    this.doctor = Objects.requireNonNull(doctor, "doctor");
    this.bind = Objects.requireNonNull(bind, "bind");
    this.port = Objects.requireNonNull(port, "port");
    this.serverViews = new ServerViews(services, runs, doctor, bind, port);
    this.runViews = new RunViews(services, runs, doctor, bind, port);
    this.hotfixViews = new HotfixViews(services, runs, doctor, bind, port);
  }

  // ---- delegation to the per-area views (roadmap item 17) ------------------------------------

  Map<String, Object> server() {
    return serverViews.server();
  }

  Map<String, Object> planResponse(StoredPlan stored, Plan plan) {
    return runViews.planResponse(stored, plan);
  }

  Map<String, Object> runList() {
    return runViews.runList();
  }

  Map<String, Object> runItem(RunRecord run) {
    return runViews.runItem(run);
  }

  Map<String, Object> runDetail(RunRecord run) {
    return runViews.runDetail(run);
  }

  Map<String, String> stepTitles(RunRecord run) {
    return runViews.stepTitles(run);
  }

  String outcome(RunRecord run) {
    return runViews.outcome(run);
  }

  boolean rollbackAvailable(RunRecord run) {
    return runViews.rollbackAvailable(run);
  }

  Optional<HotfixInstalled> installedBy(String runId) {
    return runViews.installedBy(runId);
  }

  Map<String, Object> hotfixes() {
    return hotfixViews.hotfixes();
  }

  static String label(String operation) {
    return RunViews.label(operation);
  }

  static String stepStatus(StepState state) {
    return RunViews.stepStatus(state);
  }

  StateStore sharedStore() {
    return store();
  }

  private StateStore store() {
    return services.stateStore().get();
  }

  // ---- /api/health ----------------------------------------------------------------------------

  Map<String, Object> health() {
    StateStore store = store();
    Map<String, Object> m = new LinkedHashMap<>();
    Map<String, Object> tool = new LinkedHashMap<>();
    tool.put("version", Version.current().version());
    tool.put("matrixVersion", Integer.toString(services.matrix().matrixVersion()));
    m.put("tool", tool);
    m.put("bind", bind + ":" + port.getAsInt());
    m.put("networkMode", services.config().network().mode().yamlValue());
    Optional<RunRecord> last =
        store.runs(RUN_LIMIT).stream().filter(r -> r.terminalState().isPresent()).findFirst();
    m.put("lastRun", last.map(this::lastRun));
    m.put("lock", lock(store));
    List<Map<String, Object>> pending = new ArrayList<>();
    for (RunRecord run : store.pendingRuns()) {
      Map<String, Object> p = new LinkedHashMap<>();
      p.put("id", run.runId());
      p.put("op", run.operation());
      p.put("startedAt", run.startedAt());
      p.put("stepId", RunViews.lastStep(store.transitions(run.runId())));
      pending.add(p);
    }
    m.put("pendingRuns", pending);
    m.put("snapshots", snapshots());
    doctor.last().ifPresent(c -> m.put("doctor", doctorSummary(c)));
    return m;
  }

  private Map<String, Object> lastRun(RunRecord run) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("id", run.runId());
    m.put("op", run.operation());
    m.put("outcome", outcome(run));
    m.put("finishedAt", run.endedAt());
    return m;
  }

  private Map<String, Object> lock(StateStore store) {
    Map<String, Object> m = new LinkedHashMap<>();
    Optional<RunManager.LiveRun> live = runs.running();
    if (live.isPresent()) {
      m.put("held", true);
      m.put("runId", live.get().runId());
      m.put("pid", Long.toString(ProcessHandle.current().pid()));
      return m;
    }
    Optional<RunLock.Holder> holder = RunLock.readHolder(services.home().runLock());
    boolean alive =
        holder
            .flatMap(
                h -> {
                  try {
                    return ProcessHandle.of(Long.parseLong(h.pid())).map(ProcessHandle::isAlive);
                  } catch (NumberFormatException e) {
                    return Optional.<Boolean>empty();
                  }
                })
            .orElse(false);
    boolean held =
        alive
            && holder
                .map(h -> store.run(h.runId()).map(RunRecord::pending).orElse(true))
                .orElse(false);
    m.put("held", held);
    if (held) {
      m.put("runId", holder.get().runId());
      m.put("pid", holder.get().pid());
    }
    return m;
  }

  private Map<String, Object> snapshots() {
    Map<String, Object> m = new LinkedHashMap<>();
    SnapshotStore snapshots =
        new SnapshotStore(services.home(), services.platform().files(), services.clock());
    int count = 0;
    long bytes = 0;
    try {
      count = snapshots.list().size();
      bytes = snapshots.totalBytes();
    } catch (IOException | RuntimeException e) {
      LOG.debug("cannot size the snapshot store: {}", e.getMessage());
    }
    m.put("count", count);
    m.put("bytes", bytes);
    m.put("retentionDays", services.config().backups().retentionDays());
    return m;
  }

  private static Map<String, Object> doctorSummary(DoctorCache.Cached cached) {
    DoctorReport report = cached.report();
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("pass", report.counts().pass());
    m.put("warn", report.counts().warn());
    m.put("fail", report.counts().fail());
    m.put("ranAt", cached.ranAt());
    List<Map<String, Object>> attention = new ArrayList<>();
    for (ReportItem item : report.items()) {
      if (item.status() == ReportItem.Status.WARN || item.status() == ReportItem.Status.FAIL) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("status", item.status().name());
        a.put("title", item.name());
        a.put("detail", item.detail());
        attention.add(a);
      }
    }
    m.put("attention", attention);
    return m;
  }

  // ---- /api/server ----------------------------------------------------------------------------

  // ---- /api/plan ------------------------------------------------------------------------------

  // ---- /api/runs ------------------------------------------------------------------------------

  // ---- /api/doctor ----------------------------------------------------------------------------

  static Map<String, Object> doctor(DoctorCache.Cached cached) {
    DoctorReport report = cached.report();
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("ranAt", cached.ranAt());
    Map<String, Object> counts = new LinkedHashMap<>();
    counts.put("pass", report.counts().pass());
    counts.put("warn", report.counts().warn());
    counts.put("fail", report.counts().fail());
    counts.put("skip", report.counts().skip());
    m.put("counts", counts);
    List<Map<String, Object>> items = new ArrayList<>();
    for (ReportItem item : report.items()) {
      Map<String, Object> i = new LinkedHashMap<>();
      i.put("id", item.name());
      i.put("name", item.name());
      i.put("status", item.status().name());
      i.put("title", item.name());
      i.put("detail", item.detail());
      i.put("remediation", item.remediation());
      items.add(i);
    }
    m.put("items", items);
    m.put("exitCode", report.exitCode());
    return m;
  }

  // ---- /api/hotfixes --------------------------------------------------------------------------

}
