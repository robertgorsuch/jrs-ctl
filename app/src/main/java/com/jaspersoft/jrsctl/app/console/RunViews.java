package com.jaspersoft.jrsctl.app.console;

import com.fasterxml.jackson.databind.JsonNode;
import com.jaspersoft.jrsctl.core.engine.Plan;
import com.jaspersoft.jrsctl.core.engine.PlanSummary;
import com.jaspersoft.jrsctl.core.engine.RunRecord;
import com.jaspersoft.jrsctl.core.engine.Step;
import com.jaspersoft.jrsctl.core.engine.StepState;
import com.jaspersoft.jrsctl.core.engine.TerminalState;
import com.jaspersoft.jrsctl.core.engine.Transition;
import com.jaspersoft.jrsctl.core.event.Event;
import com.jaspersoft.jrsctl.core.json.Json;
import com.jaspersoft.jrsctl.core.state.HotfixInstalled;
import com.jaspersoft.jrsctl.core.state.HotfixState;
import com.jaspersoft.jrsctl.core.state.SnapshotRecord;
import com.jaspersoft.jrsctl.core.state.StateStore;
import com.jaspersoft.jrsctl.core.state.StoredPlan;
import com.jaspersoft.jrsctl.ops.Services;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.IntSupplier;

/**
 * What the console shows about plans and runs: the plan response, the run list, one run with its
 * steps, and how a finished run reads (spec §13.1).
 *
 * <p>One area of the console per file (roadmap item 17). The shared state-store access and the
 * dashboard live in {@link ConsoleViews}, which owns this object and is the only caller.
 */
final class RunViews {

  private static final int RUN_LIMIT = 200;

  private final Services services;
  private final RunManager runs;

  RunViews(Services services, RunManager runs, DoctorCache doctor, String bind, IntSupplier port) {
    this.services = services;
    this.runs = runs;
  }

  private StateStore store() {
    return services.stateStore().get();
  }

  Map<String, Object> planResponse(StoredPlan stored, Plan plan) {
    PlanSummary s = plan.summary();
    Map<String, Object> root = new LinkedHashMap<>();
    root.put("planId", stored.planId());
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("op", stored.operation());
    p.put("title", "Plan: " + label(s.operation()) + " " + s.target());
    p.put("fingerprint", plan.fingerprint().value());
    p.put("validUntil", stored.expiresAt());
    Map<String, Object> summary = new LinkedHashMap<>();
    summary.put("filesTouched", files(s.filesTouched()));
    if (!s.resourcesTouched().isEmpty()) {
      summary.put("resourcesTouched", String.join(", ", s.resourcesTouched()));
    }
    summary.put(
        "service",
        s.serviceRestart()
            ? "Stop and start the service; the plan changes files that require it"
            : "No service restart");
    summary.put(
        "backups",
        s.backupLocations().isEmpty()
            ? "none"
            : String.join(", ", s.backupLocations().stream().map(Path::toString).toList()));
    summary.put("rollbackPoints", rollbackPoints(s));
    summary.put("strategy", s.strategy().isBlank() ? "-" : s.strategy());
    summary.put(
        "downtime",
        s.serviceRestart()
            ? "Running stops the server while the files are swapped."
            : "No downtime expected.");
    summary.put("warnings", s.warnings());
    p.put("summary", summary);
    List<Map<String, Object>> steps = new ArrayList<>();
    for (Step step : plan.steps()) {
      Map<String, Object> st = new LinkedHashMap<>();
      st.put("id", step.id());
      st.put("phase", step.phase());
      st.put("title", step.irreversible() ? step.title() + " (irreversible)" : step.title());
      st.put("why", step.detail());
      steps.add(st);
    }
    p.put("steps", steps);
    root.put("plan", p);
    return root;
  }

  static String files(List<Path> files) {
    if (files.isEmpty()) {
      return "none";
    }
    int shown = Math.min(files.size(), 5);
    String names = String.join(", ", files.subList(0, shown).stream().map(Path::toString).toList());
    String more = files.size() > shown ? " and " + (files.size() - shown) + " more" : "";
    return files.size() + (files.size() == 1 ? " file: " : " files: ") + names + more;
  }

  static String rollbackPoints(PlanSummary s) {
    if (s.rollbackPointsByPhase().isEmpty()) {
      return "every phase boundary";
    }
    List<String> parts = new ArrayList<>();
    for (Map.Entry<String, String> e : s.rollbackPointsByPhase().entrySet()) {
      parts.add(e.getKey() + ": " + e.getValue());
    }
    return String.join("; ", parts);
  }

  static String label(String operation) {
    return switch (operation) {
      case OperationCatalog.HOTFIX_APPLY -> "apply hotfix";
      case OperationCatalog.HOTFIX_ROLLBACK -> "roll back hotfix";
      case OperationCatalog.HOTFIX_VERIFY -> "verify hotfix";
      case OperationCatalog.EXPORT -> "export";
      case OperationCatalog.IMPORT -> "import";
      case OperationCatalog.UPGRADE -> "upgrade";
      default -> operation;
    };
  }

  Map<String, Object> runList() {
    List<Map<String, Object>> items = new ArrayList<>();
    for (RunRecord run : store().runs(RUN_LIMIT)) {
      items.add(runItem(run));
    }
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("runs", items);
    return m;
  }

  Map<String, Object> runItem(RunRecord run) {
    Map<String, Object> m = new LinkedHashMap<>();
    Optional<JsonNode> plan = planTree(run);
    m.put("id", run.runId());
    m.put("op", run.operation());
    m.put("target", plan.map(t -> t.path("summary").path("target").asText("")).orElse(""));
    m.put("startedAt", run.startedAt());
    m.put("finishedAt", run.endedAt());
    m.put(
        "durationMs",
        Duration.between(run.startedAt(), run.endedAt().orElseGet(services.clock()::instant))
            .toMillis());
    m.put("outcome", outcome(run));
    m.put("rollbackAvailable", rollbackAvailable(run));
    m.put("supportBundleAvailable", true);
    return m;
  }

  Map<String, Object> runDetail(RunRecord run) {
    StateStore store = store();
    Map<String, Object> m = runItem(run);
    Optional<JsonNode> plan = planTree(run);
    List<Transition> transitions = store.transitions(run.runId());
    List<SnapshotRecord> snapshots = store.snapshots(run.runId());
    String target = plan.map(t -> t.path("summary").path("target").asText("")).orElse("");
    m.put("title", label(run.operation()) + (target.isEmpty() ? "" : " " + target));
    String strategy = plan.map(t -> t.path("summary").path("strategy").asText("")).orElse("");
    m.put("subtitle", strategy.isEmpty() ? run.runId() : run.runId() + " · " + strategy);
    m.put("backups", backups(plan, snapshots));
    m.put("steps", steps(plan, transitions));
    failure(run, transitions, snapshots).ifPresent(f -> m.put("failure", f));
    return m;
  }

  /** Step titles from the stored plan, for replaying the journal with the operator's wording. */
  Map<String, String> stepTitles(RunRecord run) {
    Map<String, String> titles = new HashMap<>();
    planTree(run)
        .ifPresent(
            t -> {
              for (JsonNode step : t.path("steps")) {
                titles.put(step.path("id").asText(), step.path("title").asText());
              }
            });
    return titles;
  }

  Optional<JsonNode> planTree(RunRecord run) {
    return run.planId()
        .flatMap(id -> store().loadPlan(id))
        .flatMap(
            p -> {
              try {
                return Optional.of(Json.mapper().readTree(p.planJson()));
              } catch (IOException e) {
                return Optional.empty();
              }
            });
  }

  String backups(Optional<JsonNode> plan, List<SnapshotRecord> snapshots) {
    if (!snapshots.isEmpty()) {
      return String.join(", ", snapshots.stream().map(s -> s.path().toString()).toList());
    }
    List<String> locations = new ArrayList<>();
    plan.ifPresent(
        t -> t.path("summary").path("backupLocations").forEach(n -> locations.add(n.asText())));
    return String.join(", ", locations);
  }

  static List<Map<String, Object>> steps(Optional<JsonNode> plan, List<Transition> transitions) {
    Map<String, String> status = new LinkedHashMap<>();
    Map<String, Instant> running = new HashMap<>();
    Map<String, Long> durations = new HashMap<>();
    Map<String, String> phases = new HashMap<>();
    for (Transition t : transitions) {
      StepState to;
      try {
        to = StepState.valueOf(t.toState());
      } catch (IllegalArgumentException unknown) {
        continue;
      }
      phases.put(t.stepId(), t.phase());
      status.put(t.stepId(), stepStatus(to));
      switch (to) {
        case RUNNING -> running.putIfAbsent(t.stepId(), t.ts());
        case SUCCEEDED, FAILED -> {
          Instant from = running.get(t.stepId());
          if (from != null) {
            durations.put(t.stepId(), Math.max(0, Duration.between(from, t.ts()).toMillis()));
          }
        }
        case PENDING, SKIPPED, ROLLED_BACK, ROLLBACK_FAILED -> {}
      }
    }
    List<Map<String, Object>> out = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    if (plan.isPresent()) {
      for (JsonNode step : plan.get().path("steps")) {
        String id = step.path("id").asText();
        seen.add(id);
        out.add(
            stepRow(
                id,
                step.path("phase").asText(),
                step.path("title").asText(id),
                step.path("detail").asText(""),
                status.getOrDefault(id, "pending"),
                durations.get(id)));
      }
    }
    for (Map.Entry<String, String> e : status.entrySet()) {
      if (seen.add(e.getKey())) {
        out.add(
            stepRow(
                e.getKey(),
                phases.getOrDefault(e.getKey(), ""),
                e.getKey(),
                "",
                e.getValue(),
                durations.get(e.getKey())));
      }
    }
    return out;
  }

  static Map<String, Object> stepRow(
      String id, String phase, String title, String why, String status, Long durationMs) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("id", id);
    m.put("phase", phase);
    m.put("title", title);
    m.put("why", why);
    m.put("status", status);
    m.put("durationMs", durationMs);
    return m;
  }

  static String stepStatus(StepState state) {
    return switch (state) {
      case PENDING -> "pending";
      case RUNNING -> "running";
      case SUCCEEDED -> "succeeded";
      case FAILED -> "failed";
      case SKIPPED -> "skipped";
      case ROLLED_BACK -> "rolled_back";
      case ROLLBACK_FAILED -> "rollback_failed";
    };
  }

  Optional<Map<String, Object>> failure(
      RunRecord run, List<Transition> transitions, List<SnapshotRecord> snapshots) {
    String outcome = outcome(run);
    if (outcome.equals("succeeded") || outcome.equals("running")) {
      return Optional.empty();
    }
    Optional<String> failedStep = Optional.empty();
    for (Transition t : transitions) {
      if (t.toState().equals(StepState.FAILED.name())
          || t.toState().equals(StepState.ROLLBACK_FAILED.name())) {
        failedStep = Optional.of(t.stepId());
      }
    }
    Optional<Event> terminal = runs.find(run.runId()).flatMap(RunManager.LiveRun::terminal);
    String cause = SseEvents.lastFailure(transitions).orElse("");
    String nextAction = "see `jrsctl runs show " + run.runId() + "`";
    List<String> backups =
        new ArrayList<>(snapshots.stream().map(s -> s.path().toString()).toList());
    if (terminal.isPresent()) {
      switch (terminal.get()) {
        case Event.RunFailed e -> {
          cause = e.cause();
          nextAction = e.nextAction();
          e.backups().forEach(b -> backups.add(b.toString()));
        }
        case Event.RunRolledBack e -> {
          cause = e.cause();
          nextAction =
              "fix the cause and plan again; rolled back to phase " + e.rolledBackToPhase();
        }
        case Event.RunCancelled e -> {
          cause = "cancelled: " + e.detail();
          nextAction = "every mutating step was compensated; plan again when ready";
        }
        case Event.RunSucceeded e -> {}
        case Event.PlanCreated e -> {}
        case Event.StepPending e -> {}
        case Event.StepRunning e -> {}
        case Event.StepRetry e -> {}
        case Event.StepSucceeded e -> {}
        case Event.StepFailed e -> {}
        case Event.StepSkipped e -> {}
        case Event.StepRolledBack e -> {}
        case Event.StepRollbackFailed e -> {}
        case Event.Log e -> {}
      }
    } else if (outcome.equals("interrupted")) {
      cause = "the run was interrupted before it reached a terminal state";
      nextAction = "resume or roll back this run";
    }
    if (cause.isEmpty() && failedStep.isEmpty()) {
      return Optional.empty();
    }
    Map<String, Object> f = new LinkedHashMap<>();
    f.put("stepId", failedStep.orElse(""));
    f.put("cause", cause);
    f.put("backups", backups);
    f.put("nextAction", nextAction);
    return Optional.of(f);
  }

  String outcome(RunRecord run) {
    if (run.terminalState().isEmpty()) {
      boolean live = runs.find(run.runId()).map(RunManager.LiveRun::running).orElse(false);
      return live ? "running" : "interrupted";
    }
    TerminalState state = run.terminalState().get();
    return switch (state) {
      case SUCCEEDED -> "succeeded";
      case ROLLED_BACK -> "failed_rolled_back";
      case FAILED ->
          run.exitCode().map(c -> c == 4).orElse(false) ? "rollback_incomplete" : "failed";
      case CANCELLED -> "cancelled";
      case PRECHECK_FAILED -> "failed";
    };
  }

  boolean rollbackAvailable(RunRecord run) {
    if (run.pending()) {
      return !runs.find(run.runId()).map(RunManager.LiveRun::running).orElse(false);
    }
    return installedBy(run.runId()).isPresent();
  }

  /** The hotfix this run installed, while it is still installed. */
  Optional<HotfixInstalled> installedBy(String runId) {
    return store().hotfixes().stream()
        .filter(h -> h.installedRunId().equals(runId) && h.state() == HotfixState.INSTALLED)
        .findFirst();
  }

  static Optional<String> lastStep(List<Transition> transitions) {
    return transitions.isEmpty()
        ? Optional.empty()
        : Optional.of(transitions.get(transitions.size() - 1).stepId());
  }
}
