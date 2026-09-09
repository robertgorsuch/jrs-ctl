package com.jaspersoft.jrsctl.app.console;

import com.jaspersoft.jrsctl.core.engine.Runner;
import com.jaspersoft.jrsctl.core.engine.StepFailure;
import com.jaspersoft.jrsctl.core.engine.StepState;
import com.jaspersoft.jrsctl.core.event.Event;
import com.jaspersoft.jrsctl.core.json.Json;
import com.jaspersoft.jrsctl.core.state.RunRecord;
import com.jaspersoft.jrsctl.core.state.SnapshotRecord;
import com.jaspersoft.jrsctl.core.state.Transition;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The wire form of the run event stream (spec §5.9, §13.1, {@code web/README.md}): the SSE event
 * name is the {@link Event} type, the data is one JSON object with the field names the front-end
 * reads ({@code durationMs}, {@code of}, {@code delayMs}, lower-case {@code level}, ...). Also
 * synthesises the replay of a journal: every {@code step_transitions} row becomes the event the
 * runner emitted right after writing it, and a run's terminal state becomes its terminal event.
 * Invariants: the serialiser is a switch over the sealed hierarchy with no default, so a new event
 * type fails to compile here rather than silently reaching the browser as an empty object; replayed
 * events carry the journal's timestamps; the output is redacted by the caller, never here.
 */
final class SseEvents {

  private static final Pattern RETRY =
      Pattern.compile("^retry (\\d+)/(\\d+): (.*)$", Pattern.DOTALL);

  private SseEvents() {}

  static String name(Event event) {
    return event.type();
  }

  static String json(Event event) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("type", event.type());
    m.put("ts", event.ts());
    m.put("runId", event.runId());
    m.put("stepId", event.stepId());
    m.put("phase", event.phase());
    switch (event) {
      case Event.PlanCreated e -> {
        m.put("planId", e.planId());
        m.put("fingerprint", e.fingerprint());
      }
      case Event.StepPending e -> m.put("title", e.title());
      case Event.StepRunning e -> m.put("title", e.title());
      case Event.StepRetry e -> {
        m.put("attempt", e.attempt());
        m.put("of", e.maxAttempts());
        m.put("delayMs", e.delayMillis());
        m.put("cause", e.cause());
      }
      case Event.StepSucceeded e -> m.put("durationMs", e.elapsedMillis());
      case Event.StepFailed e -> {
        m.put("cause", e.failure().cause());
        m.put("failure", failure(e.failure()));
      }
      case Event.StepSkipped e -> m.put("reason", e.reason());
      case Event.StepRolledBack e -> m.put("durationMs", e.elapsedMillis());
      case Event.StepRollbackFailed e -> {
        m.put("cause", e.cause());
        m.put("backups", paths(e.backups()));
      }
      case Event.Log e -> {
        m.put("level", e.level().name().toLowerCase(Locale.ROOT));
        m.put("message", e.message());
      }
      case Event.RunSucceeded e -> m.put("durationMs", e.elapsedMillis());
      case Event.RunFailed e -> {
        m.put("cause", e.cause());
        m.put("backups", paths(e.backups()));
        m.put("nextAction", e.nextAction());
        m.put("rollbackIncomplete", e.rollbackIncomplete());
      }
      case Event.RunCancelled e -> {
        m.put("cause", "cancelled: " + e.detail());
        m.put("detail", e.detail());
        m.put("backups", List.of());
        m.put("nextAction", "every mutating step was compensated; plan again when ready");
      }
      case Event.RunRolledBack e -> {
        m.put("cause", e.cause());
        m.put("rolledBackToPhase", e.rolledBackToPhase());
        m.put("backups", List.of());
        m.put(
            "nextAction",
            "fix the cause and plan again; the server is back at " + e.rolledBackToPhase());
      }
    }
    return Json.write(m);
  }

  /** True for the four events after which the server closes the stream. */
  static boolean terminal(Event event) {
    return switch (event) {
      case Event.RunSucceeded e -> true;
      case Event.RunFailed e -> true;
      case Event.RunCancelled e -> true;
      case Event.RunRolledBack e -> true;
      case Event.PlanCreated e -> false;
      case Event.StepPending e -> false;
      case Event.StepRunning e -> false;
      case Event.StepRetry e -> false;
      case Event.StepSucceeded e -> false;
      case Event.StepFailed e -> false;
      case Event.StepSkipped e -> false;
      case Event.StepRolledBack e -> false;
      case Event.StepRollbackFailed e -> false;
      case Event.Log e -> false;
    };
  }

  /** The events the runner emitted for these journal rows, in journal order. */
  static List<Event> replay(
      String runId, List<Transition> transitions, Map<String, String> titles) {
    List<Event> out = new ArrayList<>();
    Map<String, Instant> running = new HashMap<>();
    for (Transition t : transitions) {
      Optional<String> stepId = Optional.of(t.stepId());
      String title = titles.getOrDefault(t.stepId(), t.stepId());
      StepState to;
      try {
        to = StepState.valueOf(t.toState());
      } catch (IllegalArgumentException unknown) {
        continue;
      }
      switch (to) {
        case PENDING -> out.add(new Event.StepPending(t.ts(), runId, stepId, t.phase(), title));
        case RUNNING -> {
          Matcher retry = RETRY.matcher(t.detail().orElse(""));
          if (retry.matches()) {
            out.add(
                new Event.StepRetry(
                    t.ts(),
                    runId,
                    stepId,
                    t.phase(),
                    Integer.parseInt(retry.group(1)),
                    Integer.parseInt(retry.group(2)),
                    0,
                    retry.group(3)));
          } else {
            running.put(t.stepId(), t.ts());
            out.add(new Event.StepRunning(t.ts(), runId, stepId, t.phase(), title));
          }
        }
        case SUCCEEDED ->
            out.add(
                new Event.StepSucceeded(
                    t.ts(), runId, stepId, t.phase(), elapsed(running.get(t.stepId()), t.ts())));
        case FAILED ->
            out.add(
                new Event.StepFailed(
                    t.ts(),
                    runId,
                    stepId,
                    t.phase(),
                    StepFailure.recoverable(t.detail().orElse("failed"), "")));
        case SKIPPED ->
            out.add(new Event.StepSkipped(t.ts(), runId, stepId, t.phase(), t.detail().orElse("")));
        case ROLLED_BACK -> out.add(new Event.StepRolledBack(t.ts(), runId, stepId, t.phase(), 0));
        case ROLLBACK_FAILED ->
            out.add(
                new Event.StepRollbackFailed(
                    t.ts(), runId, stepId, t.phase(), t.detail().orElse(""), List.of()));
      }
    }
    return out;
  }

  /** The terminal event of a finished run, rebuilt from its record and journal. */
  static Optional<Event> terminal(
      RunRecord run, List<Transition> transitions, List<SnapshotRecord> snapshots) {
    if (run.terminalState().isEmpty()) {
      return Optional.empty();
    }
    Instant ended = run.endedAt().orElse(run.startedAt());
    long elapsed = Duration.between(run.startedAt(), ended).toMillis();
    String cause = lastFailure(transitions).orElse("");
    List<Path> backups = snapshots.stream().map(SnapshotRecord::path).toList();
    String id = run.runId();
    Event event =
        switch (run.terminalState().get()) {
          case SUCCEEDED -> new Event.RunSucceeded(ended, id, Runner.RUN_PHASE, elapsed);
          case ROLLED_BACK ->
              new Event.RunRolledBack(
                  ended, id, Runner.RUN_PHASE, firstRolledBackPhase(transitions), cause);
          case FAILED ->
              new Event.RunFailed(
                  ended,
                  id,
                  Runner.RUN_PHASE,
                  cause,
                  backups,
                  "see `jrsctl runs show " + id + "`",
                  run.exitCode().map(c -> c == 4).orElse(false));
          case CANCELLED ->
              new Event.RunCancelled(
                  ended, id, Runner.RUN_PHASE, cause.replaceFirst("^cancelled: ", ""));
          case PRECHECK_FAILED ->
              new Event.RunFailed(
                  ended,
                  id,
                  Runner.RUN_PHASE,
                  cause,
                  List.of(),
                  "fix the reported condition and plan again; nothing was changed",
                  false);
        };
    return Optional.of(event);
  }

  static Optional<String> lastFailure(List<Transition> transitions) {
    Optional<String> last = Optional.empty();
    for (Transition t : transitions) {
      if (t.toState().equals(StepState.FAILED.name())
          || t.toState().equals(StepState.ROLLBACK_FAILED.name())) {
        last = t.detail().or(() -> Optional.of("step " + t.stepId() + " failed"));
      }
    }
    return last;
  }

  private static String firstRolledBackPhase(List<Transition> transitions) {
    for (Transition t : transitions) {
      if (t.toState().equals(StepState.ROLLED_BACK.name())) {
        return t.phase();
      }
    }
    return Runner.RUN_PHASE;
  }

  private static long elapsed(Instant from, Instant to) {
    return from == null ? 0 : Math.max(0, Duration.between(from, to).toMillis());
  }

  private static Map<String, Object> failure(StepFailure failure) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("type", failure.getClass().getSimpleName());
    m.put("cause", failure.cause());
    m.put("affectedPaths", paths(failure.affectedPaths()));
    m.put("affectedUris", failure.affectedUris().stream().map(URI::toString).toList());
    m.put("backups", paths(failure.backups()));
    m.put("nextAction", failure.nextAction());
    return m;
  }

  private static List<String> paths(List<Path> paths) {
    return paths.stream().map(Path::toString).toList();
  }
}
