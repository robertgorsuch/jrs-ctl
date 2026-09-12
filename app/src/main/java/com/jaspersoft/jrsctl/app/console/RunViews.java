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

  PlanDoc planResponse(StoredPlan stored, Plan plan) {
    PlanSummary s = plan.summary();
    PlanDoc.Summary summary =
        new PlanDoc.Summary(
            files(s.filesTouched()),
            s.resourcesTouched().isEmpty()
                ? Optional.empty()
                : Optional.of(String.join(", ", s.resourcesTouched())),
            s.serviceRestart()
                ? "Stop and start the service; the plan changes files that require it"
                : "No service restart",
            s.backupLocations().isEmpty()
                ? "none"
                : String.join(", ", s.backupLocations().stream().map(Path::toString).toList()),
            rollbackPoints(s),
            s.strategy().isBlank() ? "-" : s.strategy(),
            s.serviceRestart()
                ? "Running stops the server while the files are swapped."
                : "No downtime expected.",
            s.warnings());
    List<PlanDoc.PlanStep> steps = new ArrayList<>();
    for (Step step : plan.steps()) {
      steps.add(
          new PlanDoc.PlanStep(
              step.id(),
              step.phase(),
              step.irreversible() ? step.title() + " (irreversible)" : step.title(),
              step.detail()));
    }
    return new PlanDoc(
        stored.planId(),
        new PlanDoc.PlanBody(
            stored.operation(),
            "Plan: " + label(s.operation()) + " " + s.target(),
            plan.fingerprint().value(),
            stored.expiresAt(),
            summary,
            steps));
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

  RunsDoc.RunList runList() {
    List<RunsDoc.RunItem> items = new ArrayList<>();
    for (RunRecord run : store().runs(RUN_LIMIT)) {
      items.add(runItem(run));
    }
    return new RunsDoc.RunList(items);
  }

  RunsDoc.RunItem runItem(RunRecord run) {
    Optional<JsonNode> plan = planTree(run);
    return new RunsDoc.RunItem(
        run.runId(),
        run.operation(),
        plan.map(t -> t.path("summary").path("target").asText("")).orElse(""),
        run.startedAt(),
        run.endedAt(),
        Duration.between(run.startedAt(), run.endedAt().orElseGet(services.clock()::instant))
            .toMillis(),
        outcome(run),
        rollbackAvailable(run),
        true);
  }

  RunsDoc.RunDetail runDetail(RunRecord run) {
    StateStore store = store();
    Optional<JsonNode> plan = planTree(run);
    List<Transition> transitions = store.transitions(run.runId());
    List<SnapshotRecord> snapshots = store.snapshots(run.runId());
    String target = plan.map(t -> t.path("summary").path("target").asText("")).orElse("");
    String strategy = plan.map(t -> t.path("summary").path("strategy").asText("")).orElse("");
    return RunsDoc.RunDetail.of(
        runItem(run),
        label(run.operation()) + (target.isEmpty() ? "" : " " + target),
        strategy.isEmpty() ? run.runId() : run.runId() + " · " + strategy,
        backups(plan, snapshots),
        steps(plan, transitions),
        failure(run, transitions, snapshots));
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

  static List<RunsDoc.StepRow> steps(Optional<JsonNode> plan, List<Transition> transitions) {
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
    List<RunsDoc.StepRow> out = new ArrayList<>();
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

  static RunsDoc.StepRow stepRow(
      String id, String phase, String title, String why, String status, Long durationMs) {
    return new RunsDoc.StepRow(id, phase, title, why, status, Optional.ofNullable(durationMs));
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

  Optional<RunsDoc.Failure> failure(
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
    return Optional.of(new RunsDoc.Failure(failedStep.orElse(""), cause, backups, nextAction));
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
