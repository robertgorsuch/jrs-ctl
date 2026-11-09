package com.jaspersoft.jrsctl.app;

import com.jaspersoft.jrsctl.core.engine.Plan;
import com.jaspersoft.jrsctl.core.redact.Redactor;
import com.jaspersoft.jrsctl.ops.PlanRegistry;
import com.jaspersoft.jrsctl.ops.Services;
import com.jaspersoft.jrsctl.ops.passwords.PasswordException;
import com.jaspersoft.jrsctl.ops.passwords.PasswordOperations;
import com.jaspersoft.jrsctl.ops.passwords.PasswordStatusReport;
import java.io.PrintWriter;
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
 * {@code jrsctl passwords} (spec §10.6, ADR-0053): {@code status} reports the distribution of
 * modern and legacy stored passwords the vendor's utility sees, read-only; {@code migrate} runs the
 * vendor's migration as a journaled plan. Invariants: {@code migrate} without {@code
 * --db-backup-confirmed} exits 2 before anything is planned and prints the gate sentence, because
 * the migration is permanent and a database restore is the only way back (installation guide 10.1
 * p.195); both need the installation on this machine; the group itself only prints its usage.
 */
@Command(
    name = "passwords",
    mixinStandardHelpOptions = true,
    exitCodeOnInvalidInput = ExitCodes.USAGE,
    description =
        "Stored passwords: report how many are still in the legacy format, and migrate them to the"
            + " modern one with the vendor's utility (JasperReports Server 10.1 and later).",
    subcommands = {PasswordsCommand.Status.class, PasswordsCommand.Migrate.class})
final class PasswordsCommand implements Runnable {

  @Spec CommandSpec spec;

  @Override
  public void run() {
    spec.commandLine().usage(spec.commandLine().getOut());
  }

  static int report(PrintWriter out, PrintWriter err, boolean json, PasswordException e) {
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
      name = "status",
      mixinStandardHelpOptions = true,
      exitCodeOnInvalidInput = ExitCodes.USAGE,
      description =
          "Read-only: the deployed webapp's password-storage setting and the distribution of"
              + " modern and legacy passwords the vendor's js-ant detect-password-strategy"
              + " reports.")
  static final class Status implements Callable<Integer> {
    @Spec CommandSpec spec;
    @Mixin GlobalOptions global;

    @Override
    public Integer call() {
      PrintWriter out = spec.commandLine().getOut();
      PrintWriter err = spec.commandLine().getErr();
      Redactor redactor = Redactor.global();
      try (Bootstrap boot = Bootstrap.open(global, Env.vars(), Clock.systemUTC())) {
        PasswordStatusReport report;
        try {
          report = PasswordOps.open(boot.services()).status();
        } catch (PasswordException e) {
          return report(out, err, global.json(), e);
        } catch (RuntimeException e) {
          return ExitCodes.reportPlanningFailure(out, err, global.json(), e);
        }
        if (global.json()) {
          out.println(redactor.redact(JsonOut.write(report)));
          out.flush();
        } else {
          ReportPrinter.print(
              out, report.items(), report.counts(), Ansi.forStdout(global, Env.vars()), redactor);
        }
        return report.exitCode();
      }
    }
  }

  @Command(
      name = "migrate",
      mixinStandardHelpOptions = true,
      exitCodeOnInvalidInput = ExitCodes.USAGE,
      description =
          "Migrate the stored passwords to the modern format with the vendor's utility: doctor,"
              + " js-ant migrate-passwords-dry-run, migrate-passwords, detect-password-strategy,"
              + " and with --cleanup the removal of completed tracking rows. Permanent: back up"
              + " the repository database first and say so with --db-backup-confirmed.")
  static final class Migrate implements Callable<Integer> {
    @Spec CommandSpec spec;
    @Mixin GlobalOptions global;

    @Option(
        names = "--db-backup-confirmed",
        description =
            "Confirm that the repository database has been backed up (audited); a database"
                + " restore is the only way back from the migration.")
    boolean dbBackupConfirmed;

    @Option(
        names = "--cleanup",
        description =
            "After a successful migration, remove the completed rows from the vendor's migration"
                + " tracking table (js-ant cleanup-password-migration); no password changes.")
    boolean cleanup;

    @Option(names = "--plan", description = "Show the plan and exit without running it.")
    boolean plan;

    @Override
    public Integer call() {
      PrintWriter out = spec.commandLine().getOut();
      PrintWriter err = spec.commandLine().getErr();
      if (!dbBackupConfirmed) {
        return ExitCodes.fail(
            out,
            err,
            global.json(),
            ExitCodes.PRECHECK_FAILED,
            com.jaspersoft.jrsctl.ops.passwords.DefaultPasswordOperations.DB_BACKUP_GATE);
      }
      try (Bootstrap boot = Bootstrap.open(global, Env.vars(), Clock.systemUTC())) {
        Services services = boot.services();
        PasswordOperations.MigrateOptions options =
            new PasswordOperations.MigrateOptions(dbBackupConfirmed, cleanup);
        Plan planned;
        try {
          planned = PasswordOps.open(services).planMigrate(options);
        } catch (PasswordException e) {
          return report(out, err, global.json(), e);
        } catch (RuntimeException e) {
          return ExitCodes.reportPlanningFailure(out, err, global.json(), e);
        }
        PlanExecutor executor = new PlanExecutor(services, global, out, err, Env.vars());
        return executor.execute(
            new PlanExecutor.Request(
                planned,
                PlanRegistry.PASSWORDS_MIGRATE,
                PlanRegistry.passwordsMigrateArgs(options),
                plan,
                false));
      }
    }
  }
}
