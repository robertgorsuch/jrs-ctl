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

  HealthDoc health() {
    StateStore store = store();
    HealthDoc.Tool tool =
        new HealthDoc.Tool(
            Version.current().version(), Integer.toString(services.matrix().matrixVersion()));
    Optional<RunRecord> last =
        store.runs(RUN_LIMIT).stream().filter(r -> r.terminalState().isPresent()).findFirst();
    List<HealthDoc.PendingRun> pending = new ArrayList<>();
    for (RunRecord run : store.pendingRuns()) {
      pending.add(
          new HealthDoc.PendingRun(
              run.runId(),
              run.operation(),
              run.startedAt(),
              RunViews.lastStep(store.transitions(run.runId()))));
    }
    return new HealthDoc(
        tool,
        bind + ":" + port.getAsInt(),
        services.config().network().mode().yamlValue(),
        last.map(this::lastRun),
        lock(store),
        pending,
        snapshots(),
        doctor.last().map(ConsoleViews::doctorSummary));
  }

  private HealthDoc.LastRun lastRun(RunRecord run) {
    return new HealthDoc.LastRun(run.runId(), run.operation(), outcome(run), run.endedAt());
  }

  private HealthDoc.Lock lock(StateStore store) {
    Optional<RunManager.LiveRun> live = runs.running();
    if (live.isPresent()) {
      return new HealthDoc.Lock(
          true,
          Optional.of(live.get().runId()),
          Optional.of(Long.toString(ProcessHandle.current().pid())));
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
    return held
        ? new HealthDoc.Lock(
            true, holder.map(RunLock.Holder::runId), holder.map(RunLock.Holder::pid))
        : new HealthDoc.Lock(false, Optional.empty(), Optional.empty());
  }

  private HealthDoc.Snapshots snapshots() {
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
    return new HealthDoc.Snapshots(count, bytes, services.config().backups().retentionDays());
  }

  private static HealthDoc.DoctorSummary doctorSummary(DoctorCache.Cached cached) {
    DoctorReport report = cached.report();
    List<HealthDoc.Attention> attention = new ArrayList<>();
    for (ReportItem item : report.items()) {
      if (item.status() == ReportItem.Status.WARN || item.status() == ReportItem.Status.FAIL) {
        attention.add(new HealthDoc.Attention(item.status().name(), item.name(), item.detail()));
      }
    }
    return new HealthDoc.DoctorSummary(
        report.counts().pass(),
        report.counts().warn(),
        report.counts().fail(),
        cached.ranAt(),
        attention);
  }

  // ---- /api/server ----------------------------------------------------------------------------

  // ---- /api/plan ------------------------------------------------------------------------------

  // ---- /api/runs ------------------------------------------------------------------------------

  // ---- /api/doctor ----------------------------------------------------------------------------

  static DoctorDoc doctor(DoctorCache.Cached cached) {
    DoctorReport report = cached.report();
    List<DoctorDoc.Item> items = new ArrayList<>();
    for (ReportItem item : report.items()) {
      items.add(
          new DoctorDoc.Item(
              item.name(),
              item.name(),
              item.status().name(),
              item.name(),
              item.detail(),
              item.remediation()));
    }
    return new DoctorDoc(
        cached.ranAt(),
        new DoctorDoc.Counts(
            report.counts().pass(),
            report.counts().warn(),
            report.counts().fail(),
            report.counts().skip()),
        items,
        report.exitCode());
  }

  // ---- /api/hotfixes --------------------------------------------------------------------------

}
