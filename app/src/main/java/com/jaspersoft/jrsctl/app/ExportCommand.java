package com.jaspersoft.jrsctl.app;

import com.jaspersoft.jrsctl.core.engine.Plan;
import com.jaspersoft.jrsctl.jrs.api.ExportImportStrategy;
import com.jaspersoft.jrsctl.ops.Services;
import com.jaspersoft.jrsctl.ops.exim.ExportImportOperations;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/**
 * {@code jrsctl export [--uri <uri>]... [--users-roles] [--access-events] [--audit-events]
 * [--monitoring] [--settings] [--full-server] [--strategy rest|vendor] --out <file> [--plan]
 * [--yes] [--json]} (spec §9.5). Invariants: the archive and its {@code .jrsctl.json} sidecar are
 * the only files written; the export only ever runs through {@link PlanExecutor}, so it is shown,
 * confirmed, journaled and locked like every other mutation; a planning failure (unreachable
 * server, bad configuration) exits 2 because nothing has been touched yet.
 */
@Command(
    name = "export",
    mixinStandardHelpOptions = true,
    exitCodeOnInvalidInput = ExitCodes.USAGE,
    description =
        "Export repository content, or the full server, to a ZIP archive with a .jrsctl.json"
            + " sidecar.")
final class ExportCommand implements Callable<Integer> {

  @Spec CommandSpec spec;
  @Mixin GlobalOptions global;

  @Option(
      names = "--uri",
      paramLabel = "<uri>",
      description = "Repository URI to export; repeatable. Default: the whole repository.")
  List<String> uris = new ArrayList<>();

  @Option(names = "--users-roles", description = "Include users and roles.")
  boolean usersRoles;

  @Option(names = "--access-events", description = "Include access events.")
  boolean accessEvents;

  @Option(names = "--audit-events", description = "Include audit events.")
  boolean auditEvents;

  @Option(names = "--monitoring", description = "Include monitoring events.")
  boolean monitoring;

  @Option(names = "--settings", description = "Include server settings.")
  boolean settings;

  @Option(
      names = "--full-server",
      description = "Export everything with the vendor tools (stops the service).")
  boolean fullServer;

  @Option(
      names = "--strategy",
      paramLabel = "rest|vendor",
      description = "Force the REST or vendor CLI strategy instead of selecting one.")
  String strategy;

  @Option(
      names = "--out",
      required = true,
      paramLabel = "<file>",
      description = "Archive to write; the sidecar goes next to it.")
  Path out;

  @Option(names = "--plan", description = "Show the plan and exit without running it.")
  boolean plan;

  @Override
  public Integer call() {
    PrintWriter outWriter = spec.commandLine().getOut();
    PrintWriter err = spec.commandLine().getErr();
    Optional<ExportImportStrategy.Kind> kind;
    try {
      kind = StrategyFlag.parse(strategy);
    } catch (IllegalArgumentException e) {
      err.println("error: " + e.getMessage());
      err.flush();
      return ExitCodes.USAGE;
    }
    ExportImportOperations.ExportOptions options =
        new ExportImportOperations.ExportOptions(
            new LinkedHashSet<>(uris),
            usersRoles,
            accessEvents,
            auditEvents,
            monitoring,
            settings,
            fullServer,
            out,
            kind);
    try (Bootstrap boot = Bootstrap.open(global, Env.vars(), Clock.systemUTC())) {
      Services services = boot.services();
      Plan planned;
      try {
        planned = EximOps.open(services).planExport(options);
      } catch (RuntimeException e) {
        return ExitCodes.reportPlanningFailure(err, e);
      }
      PlanExecutor executor = new PlanExecutor(services, global, outWriter, err, Env.vars());
      return executor.execute(
          new PlanExecutor.Request(
              planned, PlanRegistry.EXPORT, PlanRegistry.exportArgs(options), plan, false));
    }
  }
}
