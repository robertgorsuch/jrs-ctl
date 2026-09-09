package com.jaspersoft.jrsctl.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.jaspersoft.jrsctl.core.engine.Plan;
import com.jaspersoft.jrsctl.core.engine.RunIds;
import com.jaspersoft.jrsctl.core.json.Json;
import com.jaspersoft.jrsctl.core.redact.Redactor;
import com.jaspersoft.jrsctl.core.state.LockHeldException;
import com.jaspersoft.jrsctl.core.state.RunLock;
import com.jaspersoft.jrsctl.core.state.RunRecord;
import com.jaspersoft.jrsctl.core.state.SnapshotRecord;
import com.jaspersoft.jrsctl.core.state.StateStore;
import com.jaspersoft.jrsctl.core.state.StoredPlan;
import com.jaspersoft.jrsctl.core.state.Transition;
import com.jaspersoft.jrsctl.ops.Services;
import com.jaspersoft.jrsctl.ops.retention.RetentionPruner;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import picocli.CommandLine.ArgGroup;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/**
 * {@code jrsctl runs list|show|recover|prune} (spec §5.5, §5.6, §6.6). Invariants: {@code list} and
 * {@code show} read the journal only; {@code recover} rebuilds the pending run's plan from its
 * stored arguments through {@link PlanRegistry} (never from the serialised plan, which cannot carry
 * step code) and then resumes or rolls back through {@link PlanExecutor}, so it holds the run lock
 * and journals every transition; a run that is not pending, has no stored plan, or whose plan
 * cannot be rebuilt exits 2 without touching anything; {@code prune} holds the run lock while it
 * removes snapshots (exit 9 when a run holds it) and {@code --dry-run} changes nothing.
 */
@Command(
    name = "runs",
    mixinStandardHelpOptions = true,
    exitCodeOnInvalidInput = ExitCodes.USAGE,
    description = "Inspect past runs and recover interrupted ones.",
    subcommands = {
      RunsCommand.ListRuns.class,
      RunsCommand.Show.class,
      RunsCommand.Recover.class,
      RunsCommand.Prune.class
    })
final class RunsCommand implements Runnable {

  @Spec CommandSpec spec;

  @Override
  public void run() {
    spec.commandLine().usage(spec.commandLine().getOut());
  }

  static String duration(RunRecord run, Clock clock) {
    Instant end = run.endedAt().orElseGet(clock::instant);
    Duration d = Duration.between(run.startedAt(), end);
    if (d.isNegative()) {
      d = Duration.ZERO;
    }
    String text =
        d.toHours() > 0
            ? String.format(Locale.ROOT, "%dh%02dm", d.toHours(), d.toMinutesPart())
            : d.toMinutes() > 0
                ? String.format(Locale.ROOT, "%dm%02ds", d.toMinutes(), d.toSecondsPart())
                : String.format(Locale.ROOT, "%.1fs", d.toMillis() / 1000.0);
    return run.endedAt().isPresent() ? text : text + " (running)";
  }

  static String outcome(RunRecord run) {
    return run.terminalState()
        .map(s -> s.name() + run.exitCode().map(c -> " (exit " + c + ")").orElse(""))
        .orElse("PENDING");
  }

  static Map<String, Object> runTree(RunRecord run, Clock clock) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("runId", run.runId());
    m.put("operation", run.operation());
    m.put("planId", run.planId());
    m.put("startedAt", run.startedAt());
    m.put("endedAt", run.endedAt());
    m.put(
        "durationMillis",
        Duration.between(run.startedAt(), run.endedAt().orElseGet(clock::instant)).toMillis());
    m.put("terminalState", run.terminalState());
    m.put("exitCode", run.exitCode());
    m.put("pending", run.pending());
    return m;
  }

  /** {@code jrsctl runs list [--json] [--limit N]}. */
  @Command(
      name = "list",
      mixinStandardHelpOptions = true,
      exitCodeOnInvalidInput = ExitCodes.USAGE,
      description = "List runs, most recent first.")
  static final class ListRuns implements Callable<Integer> {

    @Spec CommandSpec spec;
    @Mixin GlobalOptions global;

    @Option(names = "--limit", paramLabel = "<n>", description = "Maximum rows (default 50).")
    int limit = 50;

    @Override
    public Integer call() {
      PrintWriter out = spec.commandLine().getOut();
      Redactor redactor = Redactor.global();
      try (Bootstrap boot = Bootstrap.open(global, Env.vars(), Clock.systemUTC())) {
        Services services = boot.services();
        List<RunRecord> runs = services.stateStore().get().runs(Math.max(1, limit));
        if (global.json()) {
          List<Map<String, Object>> rows = new ArrayList<>();
          for (RunRecord r : runs) {
            rows.add(runTree(r, services.clock()));
          }
          out.println(redactor.redact(JsonOut.write(rows)));
          out.flush();
          return ExitCodes.SUCCESS;
        }
        if (runs.isEmpty()) {
          out.println("no runs recorded");
          out.flush();
          return ExitCodes.SUCCESS;
        }
        Ansi ansi = Ansi.forStdout(global.noColor(), Env.vars());
        TextTable table = new TextTable();
        table.row(
            ansi.dim("RUN"),
            ansi.dim("OPERATION"),
            ansi.dim("STARTED"),
            ansi.dim("DURATION"),
            ansi.dim("OUTCOME"));
        for (RunRecord r : runs) {
          table.row(
              r.runId(),
              r.operation(),
              r.startedAt().truncatedTo(ChronoUnit.SECONDS).toString(),
              duration(r, services.clock()),
              outcome(r));
        }
        for (String line : table.lines()) {
          out.println(redactor.redact(line));
        }
        out.flush();
        return ExitCodes.SUCCESS;
      }
    }
  }

  /** {@code jrsctl runs show <id> [--json]}: plan summary, step transitions, backups. */
  @Command(
      name = "show",
      mixinStandardHelpOptions = true,
      exitCodeOnInvalidInput = ExitCodes.USAGE,
      description = "Show one run: its plan summary, every step transition and its backups.")
  static final class Show implements Callable<Integer> {

    @Spec CommandSpec spec;
    @Mixin GlobalOptions global;

    @Parameters(index = "0", paramLabel = "<id>", description = "Run id from `runs list`.")
    String runId;

    @Override
    public Integer call() {
      PrintWriter out = spec.commandLine().getOut();
      PrintWriter err = spec.commandLine().getErr();
      Redactor redactor = Redactor.global();
      try (Bootstrap boot = Bootstrap.open(global, Env.vars(), Clock.systemUTC())) {
        Services services = boot.services();
        StateStore store = services.stateStore().get();
        Optional<RunRecord> found = store.run(runId);
        if (found.isEmpty()) {
          err.println("error: unknown run " + runId + "; see `jrsctl runs list`");
          err.flush();
          return ExitCodes.PRECHECK_FAILED;
        }
        RunRecord run = found.get();
        Optional<StoredPlan> plan = run.planId().flatMap(store::loadPlan);
        Optional<JsonNode> planTree = plan.map(p -> parse(p.planJson()));
        List<Transition> transitions = store.transitions(runId);
        List<SnapshotRecord> snapshots = store.snapshots(runId);
        if (global.json()) {
          Map<String, Object> root = new LinkedHashMap<>();
          root.put("run", runTree(run, services.clock()));
          root.put("plan", planTree);
          root.put("transitions", transitions);
          root.put("snapshots", snapshots);
          out.println(redactor.redact(JsonOut.write(root)));
          out.flush();
          return ExitCodes.SUCCESS;
        }
        List<String> lines = new ArrayList<>();
        lines.add("Run  " + run.runId() + "  " + run.operation() + "  " + outcome(run));
        lines.add(
            "  started  " + run.startedAt() + "  duration  " + duration(run, services.clock()));
        lines.add("Plan");
        if (planTree.isPresent()) {
          JsonNode summary = planTree.get().path("summary");
          TextTable t = new TextTable();
          t.row("  id", plan.get().planId());
          t.row("  operation", summary.path("operation").asText());
          t.row("  target", summary.path("target").asText());
          t.row("  files", Integer.toString(summary.path("filesTouched").size()));
          t.row(
              "  service",
              summary.path("serviceRestart").asBoolean(false) ? "restart required" : "no restart");
          t.row("  strategy", summary.path("strategy").asText());
          t.row("  fingerprint", planTree.get().path("fingerprint").path("value").asText());
          lines.addAll(t.lines());
          for (JsonNode w : summary.path("warnings")) {
            lines.add("  ! " + w.asText());
          }
        } else {
          lines.add("  (no stored plan)");
        }
        lines.add("Steps");
        if (transitions.isEmpty()) {
          lines.add("  (no transitions recorded)");
        } else {
          TextTable t = new TextTable();
          for (Transition tr : transitions) {
            t.row(
                "  " + tr.ts(),
                tr.phase(),
                tr.stepId(),
                tr.fromState().orElse("-") + " -> " + tr.toState(),
                tr.detail().orElse(""));
          }
          lines.addAll(t.lines());
        }
        lines.add("Backups");
        if (snapshots.isEmpty()) {
          lines.add("  (none)");
        } else {
          for (SnapshotRecord s : snapshots) {
            lines.add("  " + s.id() + "  " + s.path() + "  (step " + s.stepId() + ")");
          }
        }
        for (String line : lines) {
          out.println(redactor.redact(line.stripTrailing()));
        }
        out.flush();
        return ExitCodes.SUCCESS;
      }
    }

    private static JsonNode parse(String json) {
      try {
        return Json.mapper().readTree(json);
      } catch (java.io.IOException e) {
        return Json.mapper().createObjectNode();
      }
    }
  }

  /** {@code jrsctl runs recover <id> --resume|--rollback}. */
  @Command(
      name = "recover",
      mixinStandardHelpOptions = true,
      exitCodeOnInvalidInput = ExitCodes.USAGE,
      description = "Continue an interrupted run from its interrupted step, or undo it.")
  static final class Recover implements Callable<Integer> {

    /** Exactly one of the two modes. */
    static final class Mode {
      @Option(names = "--resume", description = "Re-run the interrupted step and continue.")
      boolean resume;

      @Option(names = "--rollback", description = "Compensate every succeeded step in reverse.")
      boolean rollback;
    }

    @Spec CommandSpec spec;
    @Mixin GlobalOptions global;

    @Parameters(index = "0", paramLabel = "<id>", description = "Pending run id.")
    String runId;

    @ArgGroup(exclusive = true, multiplicity = "1")
    Mode mode;

    @Override
    public Integer call() {
      PrintWriter out = spec.commandLine().getOut();
      PrintWriter err = spec.commandLine().getErr();
      try (Bootstrap boot = Bootstrap.open(global, Env.vars(), Clock.systemUTC())) {
        Services services = boot.services();
        StateStore store = services.stateStore().get();
        Optional<RunRecord> found = store.run(runId);
        if (found.isEmpty()) {
          err.println("error: unknown run " + runId + "; see `jrsctl runs list`");
          err.flush();
          return ExitCodes.PRECHECK_FAILED;
        }
        RunRecord run = found.get();
        if (!run.pending()) {
          err.println(
              "error: run "
                  + runId
                  + " already ended with state "
                  + run.terminalState().map(Enum::name).orElse("?")
                  + "; nothing to recover");
          err.flush();
          return ExitCodes.PRECHECK_FAILED;
        }
        Optional<StoredPlan> stored = run.planId().flatMap(store::loadPlan);
        if (stored.isEmpty()) {
          err.println(
              "error: run "
                  + runId
                  + " has no stored plan; restore the backups listed by `jrsctl runs show "
                  + runId
                  + "` manually");
          err.flush();
          return ExitCodes.PRECHECK_FAILED;
        }
        Plan plan;
        try {
          PlanRegistry registry =
              new PlanRegistry(
                  () -> HotfixOps.open(services),
                  () -> EximOps.open(services),
                  () -> new com.jaspersoft.jrsctl.ops.upgrade.DefaultUpgradeOperations(services));
          plan = registry.rebuild(stored.get().operation(), stored.get().argsJson());
        } catch (RuntimeException e) {
          return ExitCodes.reportPlanningFailure(err, e);
        }
        store.audit(
            "operator", "runs.recover", runId + (mode.resume ? " --resume" : " --rollback"));
        PlanExecutor executor = new PlanExecutor(services, global, out, err, Env.vars());
        return executor.recover(runId, plan, mode.resume);
      }
    }
  }

  /**
   * {@code jrsctl runs prune [--dry-run] [--json]} (spec §5.6): retention pruning per {@code
   * backups.retentionDays} and {@code backups.maxSnapshots}; snapshots of an installed hotfix, a
   * registered customization, the most recent successful upgrade or a pending run are never
   * removed. JSON shape: {@code {"dryRun":bool,"removed":[{"id","runId","stepId","path"}],"kept":n,
   * "protected":n}}.
   */
  @Command(
      name = "prune",
      mixinStandardHelpOptions = true,
      exitCodeOnInvalidInput = ExitCodes.USAGE,
      description =
          "Remove snapshots beyond backups.retentionDays / backups.maxSnapshots;"
              + " referenced snapshots are always kept.")
  static final class Prune implements Callable<Integer> {

    @Spec CommandSpec spec;
    @Mixin GlobalOptions global;

    @Option(names = "--dry-run", description = "List what would be removed and change nothing.")
    boolean dryRun;

    @Override
    public Integer call() throws IOException {
      PrintWriter out = spec.commandLine().getOut();
      PrintWriter err = spec.commandLine().getErr();
      Redactor redactor = Redactor.global();
      try (Bootstrap boot = Bootstrap.open(global, Env.vars(), Clock.systemUTC())) {
        Services services = boot.services();
        RetentionPruner pruner = RetentionPruner.of(services);
        RetentionPruner.Result result;
        if (dryRun) {
          result = pruner.prune(true);
        } else {
          String lockId = "prune-" + RunIds.next(services.clock());
          try (RunLock unusedLock =
              new RunLock(services.home(), lockId, services.clock().instant())) {
            result = pruner.prune(false);
          } catch (LockHeldException held) {
            err.println(
                redactor.redact(
                    "error: run lock is held by run "
                        + held.holderRunId()
                        + " (pid "
                        + held.holderPid()
                        + "); wait for it to finish or check `jrsctl runs list`"));
            err.flush();
            return ExitCodes.LOCK_HELD;
          }
        }
        if (global.json()) {
          out.println(redactor.redact(JsonOut.write(tree(result))));
          out.flush();
          return ExitCodes.SUCCESS;
        }
        Ansi ansi = Ansi.forStdout(global.noColor(), Env.vars());
        List<String> lines = new ArrayList<>();
        if (!result.removed().isEmpty()) {
          TextTable table = new TextTable();
          table.row(ansi.dim("SNAPSHOT"), ansi.dim("RUN"), ansi.dim("STEP"), ansi.dim("PATH"));
          for (RetentionPruner.Removed r : result.removed()) {
            table.row(r.id(), r.runId(), r.stepId(), r.path().toString());
          }
          lines.addAll(table.lines());
        }
        String verb = result.dryRun() ? "would remove " : "removed ";
        lines.add(
            (result.removed().isEmpty() ? "nothing to prune" : verb + result.removed().size())
                + (result.removed().isEmpty() ? "" : " snapshot(s)")
                + "; "
                + (result.dryRun() ? "keeping " : "kept ")
                + result.kept()
                + " ("
                + result.protectedCount()
                + " protected)"
                + (result.dryRun() ? "  [dry run, nothing has changed]" : ""));
        for (String line : lines) {
          out.println(redactor.redact(line));
        }
        out.flush();
        return ExitCodes.SUCCESS;
      }
    }

    static Map<String, Object> tree(RetentionPruner.Result result) {
      Map<String, Object> root = new LinkedHashMap<>();
      root.put("dryRun", result.dryRun());
      List<Map<String, Object>> removed = new ArrayList<>();
      for (RetentionPruner.Removed r : result.removed()) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.id());
        m.put("runId", r.runId());
        m.put("stepId", r.stepId());
        m.put("path", r.path().toString());
        removed.add(m);
      }
      root.put("removed", removed);
      root.put("kept", result.kept());
      root.put("protected", result.protectedCount());
      return root;
    }
  }
}
