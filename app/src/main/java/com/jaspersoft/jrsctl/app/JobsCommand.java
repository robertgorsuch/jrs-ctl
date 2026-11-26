package com.jaspersoft.jrsctl.app;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jaspersoft.jrsctl.core.engine.Plan;
import com.jaspersoft.jrsctl.core.json.Json;
import com.jaspersoft.jrsctl.core.redact.Redactor;
import com.jaspersoft.jrsctl.jrs.api.JobSummary;
import com.jaspersoft.jrsctl.ops.PlanRegistry;
import com.jaspersoft.jrsctl.ops.Services;
import com.jaspersoft.jrsctl.ops.jobs.JobsException;
import com.jaspersoft.jrsctl.ops.jobs.JobsOperations;
import com.jaspersoft.jrsctl.ops.jobs.JobsRecordReport;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import picocli.CommandLine.ArgGroup;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/**
 * {@code jrsctl jobs} (spec §10.7, issue #304, ADR-0056): {@code record} writes every report job's
 * state to a file, read-only; {@code pause} and {@code resume} change job states as journaled plans
 * whose rollback undoes exactly what they changed. Invariants: all three use the server's REST API
 * only; {@code pause} needs exactly one of {@code --all} and {@code --from} (picocli refuses
 * neither or both, exit 1); a refusal while recording or planning exits 2 with nothing changed; the
 * group itself only prints its usage.
 */
@Command(
    name = "jobs",
    mixinStandardHelpOptions = true,
    exitCodeOnInvalidInput = ExitCodes.USAGE,
    description =
        "Report jobs across a migration: record each job's state, pause jobs, and resume exactly"
            + " the ones that were active, leaving the paused ones paused.",
    subcommands = {
      JobsCommand.RecordStates.class,
      JobsCommand.Pause.class,
      JobsCommand.Resume.class
    })
final class JobsCommand implements Runnable {

  @Spec CommandSpec spec;

  @Override
  public void run() {
    spec.commandLine().usage(spec.commandLine().getOut());
  }

  static int report(PrintWriter out, PrintWriter err, boolean json, JobsException e) {
    return ExitCodes.fail(
        out,
        err,
        json,
        e.exitCode(),
        e.getClass().getSimpleName(),
        e.getMessage(),
        Optional.of(e.remediation()),
        Map.of());
  }

  @Command(
      name = "record",
      mixinStandardHelpOptions = true,
      exitCodeOnInvalidInput = ExitCodes.USAGE,
      description =
          "Read-only: write every report job's id, label, report, owner and state"
              + " (GET /rest_v2/jobs) to a file for a later jobs resume --from. Never overwrites a"
              + " file.")
  static final class RecordStates implements Callable<Integer> {
    @Spec CommandSpec spec;
    @Mixin GlobalOptions global;

    @Option(
        names = "--out",
        paramLabel = "<file>",
        description =
            "The file to write; default: jobs/jobs-<UTC time>.json in the jrsctl home. Refused"
                + " when it exists.")
    Path out;

    @Override
    public Integer call() {
      PrintWriter stdout = spec.commandLine().getOut();
      PrintWriter err = spec.commandLine().getErr();
      Redactor redactor = Redactor.global();
      try (Bootstrap boot = Bootstrap.open(global, Env.vars(), Clock.systemUTC())) {
        JobsRecordReport report;
        try {
          report =
              JobsOps.open(boot.services())
                  .record(new JobsOperations.RecordOptions(Optional.ofNullable(out)));
        } catch (JobsException e) {
          return report(stdout, err, global.json(), e);
        } catch (RuntimeException e) {
          return ExitCodes.reportPlanningFailure(stdout, err, global.json(), e);
        }
        if (global.json()) {
          stdout.println(redactor.redactJson(Json.mapper(), json(report)));
        } else {
          stdout.println(
              redactor.redact(
                  "Recorded "
                      + report.jobs().size()
                      + " report job(s) of "
                      + report.serverUrl()
                      + " ("
                      + report.serverVersion()
                      + "): "
                      + report.active()
                      + " active, "
                      + report.paused()
                      + " paused, "
                      + report.other()
                      + " in another state."));
          stdout.println(redactor.redact("Record: " + report.file()));
          if (report.other() > 0) {
            stdout.println(
                "Jobs in another state (an error state) are not resumed by jobs resume; decide"
                    + " them in the scheduler.");
          }
        }
        stdout.flush();
        return ExitCodes.SUCCESS;
      }
    }

    static ObjectNode json(JobsRecordReport report) {
      ObjectNode node = Json.mapper().createObjectNode();
      node.put("file", report.file().toString());
      ObjectNode server = node.putObject("server");
      server.put("url", report.serverUrl());
      server.put("version", report.serverVersion());
      node.put("recordedAt", report.recordedAt().toString());
      ObjectNode counts = node.putObject("counts");
      counts.put("total", report.jobs().size());
      counts.put("active", report.active());
      counts.put("paused", report.paused());
      counts.put("other", report.other());
      ArrayNode jobs = node.putArray("jobs");
      for (JobSummary job : report.jobs()) {
        ObjectNode j = jobs.addObject();
        j.put("id", job.id());
        j.put("label", job.label());
        j.put("reportUnitURI", job.reportUnitUri());
        j.put("owner", job.owner());
        j.put("state", job.state());
      }
      return node;
    }
  }

  @Command(
      name = "pause",
      mixinStandardHelpOptions = true,
      exitCodeOnInvalidInput = ExitCodes.USAGE,
      description =
          "Pause every report job (--all) or the jobs a record names (--from), except those"
              + " already paused. A rollback resumes exactly the jobs this run paused. With --all,"
              + " run jobs record first: afterwards every job looks paused.")
  static final class Pause implements Callable<Integer> {
    @Spec CommandSpec spec;
    @Mixin GlobalOptions global;

    @ArgGroup(exclusive = true, multiplicity = "1")
    Selection selection;

    static final class Selection {
      @Option(names = "--all", required = true, description = "Pause every report job.")
      boolean all;

      @Option(
          names = "--from",
          required = true,
          paramLabel = "<record>",
          description =
              "Pause the jobs this jobs record file (or export archive with a sidecar that"
                  + " records the job states) names, matched on the server.")
      Path from;
    }

    @Option(names = "--plan", description = "Show the plan and exit without running it.")
    boolean plan;

    @Override
    public Integer call() {
      JobsOperations.PauseOptions options =
          new JobsOperations.PauseOptions(Optional.ofNullable(selection.from));
      return run(
          spec,
          global,
          plan,
          ops -> ops.planPause(options),
          PlanRegistry.JOBS_PAUSE,
          PlanRegistry.jobsPauseArgs(options));
    }
  }

  @Command(
      name = "resume",
      mixinStandardHelpOptions = true,
      exitCodeOnInvalidInput = ExitCodes.USAGE,
      description =
          "Resume the report jobs a record shows as active that are paused now; jobs recorded as"
              + " paused stay paused, jobs recorded in an error state are left to you. A rollback"
              + " pauses again exactly the jobs this run resumed.")
  static final class Resume implements Callable<Integer> {
    @Spec CommandSpec spec;
    @Mixin GlobalOptions global;

    @Option(
        names = "--from",
        required = true,
        paramLabel = "<record>",
        description =
            "The jobs record file, usually taken on the source server before the move, or an"
                + " export archive whose .jrsctl.json sidecar records the job states.")
    Path from;

    @Option(names = "--plan", description = "Show the plan and exit without running it.")
    boolean plan;

    @Override
    public Integer call() {
      JobsOperations.ResumeOptions options = new JobsOperations.ResumeOptions(from);
      return run(
          spec,
          global,
          plan,
          ops -> ops.planResume(options),
          PlanRegistry.JOBS_RESUME,
          PlanRegistry.jobsResumeArgs(options));
    }
  }

  private static int run(
      CommandSpec spec,
      GlobalOptions global,
      boolean planOnly,
      java.util.function.Function<JobsOperations, Plan> planner,
      String operation,
      String args) {
    PrintWriter out = spec.commandLine().getOut();
    PrintWriter err = spec.commandLine().getErr();
    try (Bootstrap boot = Bootstrap.open(global, Env.vars(), Clock.systemUTC())) {
      Services services = boot.services();
      Plan planned;
      try {
        planned = planner.apply(JobsOps.open(services));
      } catch (JobsException e) {
        return report(out, err, global.json(), e);
      } catch (RuntimeException e) {
        return ExitCodes.reportPlanningFailure(out, err, global.json(), e);
      }
      PlanExecutor executor = new PlanExecutor(services, global, out, err, Env.vars());
      return executor.execute(new PlanExecutor.Request(planned, operation, args, planOnly, false));
    }
  }
}
