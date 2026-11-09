package com.jaspersoft.jrsctl.app;

import com.jaspersoft.jrsctl.core.engine.Plan;
import com.jaspersoft.jrsctl.ops.PlanRegistry;
import com.jaspersoft.jrsctl.ops.Services;
import com.jaspersoft.jrsctl.ops.repository.RepositoryException;
import com.jaspersoft.jrsctl.ops.repository.RepositoryOperations;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/**
 * {@code jrsctl repository} (spec §10.5, ADR-0052): {@code repository load} creates a new
 * repository schema with the vendor tools of a tools-only configuration, initialises it, loads the
 * minimal content and imports an export into it, as one journaled plan. Invariants: the plan is
 * shown and confirmed through {@link PlanExecutor} like every mutating command; a refusal while
 * planning exits with the code the operation names (1 usage, 2 precheck, 6 unsupported) and nothing
 * is created; the group itself only prints its usage.
 */
@Command(
    name = "repository",
    mixinStandardHelpOptions = true,
    exitCodeOnInvalidInput = ExitCodes.USAGE,
    description =
        "Repository schemas: load a new one from an export with the vendor tools, leaving the"
            + " old server and its database untouched (ADR-0052).",
    subcommands = {RepositoryCommand.Load.class})
final class RepositoryCommand implements Runnable {

  @Spec CommandSpec spec;

  @Override
  public void run() {
    spec.commandLine().usage(spec.commandLine().getOut());
  }

  static int report(PrintWriter out, PrintWriter err, boolean json, RepositoryException e) {
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
      name = "load",
      mixinStandardHelpOptions = true,
      exitCodeOnInvalidInput = ExitCodes.USAGE,
      description =
          "From a tools-only configuration (jrsctl init --tools-only): create the empty schema"
              + " that js.dbName names (js-ant create-js-db), initialise it (init-js-db), load"
              + " the minimal content (import-minimal) and import the export (js-import"
              + " --include-server-settings). The schema must not exist yet; a failure drops only"
              + " the schema this run created. The server that will use it is deployed by you"
              + " afterwards.")
  static final class Load implements Callable<Integer> {
    @Spec CommandSpec spec;
    @Mixin GlobalOptions global;

    @Option(
        names = "--schema",
        paramLabel = "<name>",
        description =
            "The new schema (database) to create; must equal js.dbName in the buildomatic's"
                + " default_master.properties (required).")
    String schema;

    @Option(
        names = "--from",
        paramLabel = "<export>",
        description =
            "The export to load: a js-export --everything archive or a jrsctl export"
                + " --full-server archive, with its sidecar beside it when there is one"
                + " (required).")
    Path export;

    @Option(
        names = "--include-events",
        description =
            "Also import the access, audit and monitoring events the export holds; without it"
                + " they are left behind and the plan says so.")
    boolean includeEvents;

    @Option(
        names = "--key-alias",
        paramLabel = "<alias>",
        description =
            "Alias the export was encrypted with (the Legacy key is"
                + " deprecatedImportExportEncSecret); taken from the sidecar or the archive's"
                + " index.xml when not given.")
    String keyAlias;

    @Option(names = "--plan", description = "Show the plan and exit without running it.")
    boolean plan;

    @Override
    public Integer call() {
      PrintWriter out = spec.commandLine().getOut();
      PrintWriter err = spec.commandLine().getErr();
      if (schema == null || schema.isBlank() || export == null) {
        return ExitCodes.fail(
            out,
            err,
            global.json(),
            ExitCodes.USAGE,
            "--schema <name> and --from <export> are required");
      }
      try (Bootstrap boot = Bootstrap.open(global, Env.vars(), Clock.systemUTC())) {
        Services services = boot.services();
        RepositoryOperations.LoadOptions options =
            new RepositoryOperations.LoadOptions(
                schema, export, includeEvents, Optional.ofNullable(keyAlias));
        Plan planned;
        try {
          planned = RepositoryOps.open(services).planLoad(options);
        } catch (RepositoryException e) {
          return report(out, err, global.json(), e);
        } catch (RuntimeException e) {
          return ExitCodes.reportPlanningFailure(out, err, global.json(), e);
        }
        PlanExecutor executor = new PlanExecutor(services, global, out, err, Env.vars());
        return executor.execute(
            new PlanExecutor.Request(
                planned,
                PlanRegistry.REPOSITORY_LOAD,
                PlanRegistry.repositoryLoadArgs(options),
                plan,
                false));
      }
    }
  }
}
