package com.jaspersoft.jrsctl.app;

import com.jaspersoft.jrsctl.core.engine.Plan;
import com.jaspersoft.jrsctl.ops.Services;
import com.jaspersoft.jrsctl.ops.upgrade.DefaultUpgradeOperations;
import com.jaspersoft.jrsctl.ops.upgrade.UpgradeException;
import com.jaspersoft.jrsctl.ops.upgrade.UpgradeOperations;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Locale;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/**
 * {@code jrsctl upgrade} (spec §10.4): plans and runs a vendor upgrade, and {@code upgrade rollback
 * <runId> --to-point B|C} restores the backups of an earlier upgrade run. Invariants: {@code --mode
 * newdb} is the default; {@code --mode samedb} without {@code --db-backup-confirmed} exits 2 before
 * anything is planned and prints the spec §10.1 gate; the plan is always shown and confirmed
 * through {@link PlanExecutor} like every mutating command; an unsupported upgrade path exits 6.
 */
@Command(
    name = "upgrade",
    mixinStandardHelpOptions = true,
    exitCodeOnInvalidInput = ExitCodes.USAGE,
    description =
        "Upgrade JasperReports Server with the vendor scripts of a target package: doctor, full"
            + " backup (rollback point B), vendor upgrade, hotfix and customization reconcile,"
            + " smoke test.",
    subcommands = {UpgradeCommand.Rollback.class})
final class UpgradeCommand implements Callable<Integer> {

  @Spec CommandSpec spec;
  @Mixin GlobalOptions global;

  // Not `required = true`: picocli enforces a parent's required options even when the `rollback`
  // subcommand runs, so the check happens in call() instead.
  @Option(
      names = "--to",
      paramLabel = "<version>",
      description = "Target JasperReports Server version, e.g. 9.0.0 (required).")
  String to;

  @Option(
      names = "--package",
      paramLabel = "<dir>",
      description =
          "Unpacked target distribution (contains buildomatic/ and the webapp; required).")
  Path packageDir;

  @Option(
      names = "--mode",
      paramLabel = "newdb|samedb",
      defaultValue = "newdb",
      description = "newdb (default) creates a new repository database; samedb migrates in place.")
  String mode;

  @Option(
      names = "--db-backup-confirmed",
      description =
          "samedb only: confirm that the repository database has been backed up (audited).")
  boolean dbBackupConfirmed;

  @Option(
      names = "--reapply-hotfixes",
      description =
          "Re-apply every installed hotfix classified REAPPLICABLE after the vendor upgrade.")
  boolean reapplyHotfixes;

  @Option(names = "--plan", description = "Show the plan and exit without running it.")
  boolean plan;

  @Option(
      names = "--rollback-all",
      description =
          "On failure compensate every step of the plan (back to point B), not just the failing"
              + " phase.")
  boolean rollbackAll;

  @Override
  public Integer call() {
    PrintWriter out = spec.commandLine().getOut();
    PrintWriter err = spec.commandLine().getErr();
    if (to == null || to.isBlank() || packageDir == null) {
      err.println("error: --to <version> and --package <dir> are required");
      err.flush();
      return ExitCodes.USAGE;
    }
    UpgradeOperations.Mode parsed;
    try {
      parsed = UpgradeOperations.Mode.valueOf(mode.strip().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      err.println("error: --mode must be newdb or samedb");
      err.flush();
      return ExitCodes.USAGE;
    }
    if (parsed == UpgradeOperations.Mode.SAMEDB && !dbBackupConfirmed) {
      err.println("error: " + gateMessage());
      err.flush();
      return ExitCodes.PRECHECK_FAILED;
    }
    try (Bootstrap boot = Bootstrap.open(global, Env.vars(), Clock.systemUTC())) {
      Services services = boot.services();
      UpgradeOperations.UpgradeOptions options =
          new UpgradeOperations.UpgradeOptions(
              to, packageDir, parsed, dbBackupConfirmed, reapplyHotfixes);
      Plan planned;
      try {
        planned = new DefaultUpgradeOperations(services).planUpgrade(options);
      } catch (UpgradeException e) {
        return report(err, e);
      } catch (RuntimeException e) {
        return ExitCodes.reportPlanningFailure(err, e);
      }
      PlanExecutor executor = new PlanExecutor(services, global, out, err, Env.vars());
      return executor.execute(
          new PlanExecutor.Request(
              planned, PlanRegistry.UPGRADE, PlanRegistry.upgradeArgs(options), plan, rollbackAll));
    }
  }

  static String gateMessage() {
    return "--mode samedb migrates the repository database in place and jrsctl cannot undo that;"
        + " back up the database yourself and pass --db-backup-confirmed";
  }

  static int report(PrintWriter err, UpgradeException e) {
    err.println(
        com.jaspersoft.jrsctl.core.redact.Redactor.global()
            .redact("error: " + e.getMessage() + "; " + e.remediation()));
    err.flush();
    return e.exitCode();
  }

  @Command(
      name = "rollback",
      mixinStandardHelpOptions = true,
      exitCodeOnInvalidInput = ExitCodes.USAGE,
      description =
          "Restore the point-B backups of an upgrade run: webapp, buildomatic, configuration,"
              + " keystore. Files only; the database is the operator's responsibility.")
  static final class Rollback implements Callable<Integer> {
    @Spec CommandSpec spec;
    @Mixin GlobalOptions global;

    @Parameters(index = "0", paramLabel = "<runId>", description = "Id of the upgrade run.")
    String runId;

    @Option(
        names = "--to-point",
        required = true,
        paramLabel = "B|C",
        description = "Rollback point; C restores the same point-B artefacts (spec §10.2).")
    String point;

    @Option(names = "--plan", description = "Show the plan and exit without running it.")
    boolean plan;

    @Override
    public Integer call() {
      PrintWriter out = spec.commandLine().getOut();
      PrintWriter err = spec.commandLine().getErr();
      UpgradeOperations.RollbackPoint parsed;
      try {
        parsed = UpgradeOperations.RollbackPoint.valueOf(point.strip().toUpperCase(Locale.ROOT));
      } catch (IllegalArgumentException e) {
        err.println("error: --to-point must be B or C");
        err.flush();
        return ExitCodes.USAGE;
      }
      try (Bootstrap boot = Bootstrap.open(global, Env.vars(), Clock.systemUTC())) {
        Services services = boot.services();
        Plan planned;
        try {
          planned = new DefaultUpgradeOperations(services).planRollback(runId, parsed);
        } catch (UpgradeException e) {
          return report(err, e);
        } catch (RuntimeException e) {
          return ExitCodes.reportPlanningFailure(err, e);
        }
        PlanExecutor executor = new PlanExecutor(services, global, out, err, Env.vars());
        return executor.execute(
            new PlanExecutor.Request(
                planned,
                PlanRegistry.UPGRADE_ROLLBACK,
                PlanRegistry.upgradeRollbackArgs(runId, parsed),
                plan,
                false));
      }
    }
  }
}
