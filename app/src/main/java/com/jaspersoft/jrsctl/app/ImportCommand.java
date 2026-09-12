package com.jaspersoft.jrsctl.app;

import com.jaspersoft.jrsctl.core.engine.Plan;
import com.jaspersoft.jrsctl.core.secrets.SecretRef;
import com.jaspersoft.jrsctl.jrs.api.ExportImportStrategy;
import com.jaspersoft.jrsctl.ops.PlanRegistry;
import com.jaspersoft.jrsctl.ops.Services;
import com.jaspersoft.jrsctl.ops.StrategyFlag;
import com.jaspersoft.jrsctl.ops.exim.ExportImportOperations;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Optional;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/**
 * {@code jrsctl import <archive> [--update] [--skip-user-update] [--access-events] [--audit-events]
 * [--monitoring] [--settings] [--skip-themes] [--source-keystore <path>]
 * [--source-keystore-password-ref <ref>] [--strategy rest|vendor] [--plan] [--yes] [--json]} (spec
 * §9.5). Invariants: the import only ever runs through {@link PlanExecutor}; the plan always takes
 * a pre-import snapshot first and its summary states that rollback is best effort (spec §9.4); an
 * unparseable secret reference is a usage error (exit 1) raised before any bootstrap; a planning
 * failure exits 2 because nothing has been touched yet.
 */
@Command(
    name = "import",
    mixinStandardHelpOptions = true,
    exitCodeOnInvalidInput = ExitCodes.USAGE,
    description =
        "Import an export archive after snapshotting the affected subtree; rollback re-imports"
            + " the snapshot (best effort).")
final class ImportCommand implements Callable<Integer> {

  @Spec CommandSpec spec;
  @Mixin GlobalOptions global;

  @Parameters(index = "0", paramLabel = "<archive>", description = "Export archive to import.")
  Path archive;

  @Option(names = "--update", description = "Overwrite resources that already exist.")
  boolean update;

  @Option(
      names = "--skip-user-update",
      description = "Do not update users that already exist (with --update).")
  boolean skipUserUpdate;

  @Option(names = "--access-events", description = "Include access events.")
  boolean accessEvents;

  @Option(names = "--audit-events", description = "Include audit events.")
  boolean auditEvents;

  @Option(names = "--monitoring", description = "Include monitoring events.")
  boolean monitoring;

  @Option(names = "--settings", description = "Include server settings.")
  boolean settings;

  @Option(names = "--skip-themes", description = "Do not import themes.")
  boolean skipThemes;

  @Option(
      names = "--source-keystore",
      paramLabel = "<path>",
      description = "The source server's .jrsks, imported first when the keystores differ.")
  Path sourceKeystore;

  @Option(
      names = "--source-keystore-password-ref",
      paramLabel = "<ref>",
      description = "Secret reference (env:NAME, file:/path, enc:NAME) of the keystore password.")
  String sourceKeystorePasswordRef;

  @Option(
      names = "--strategy",
      paramLabel = "rest|vendor",
      description = "Force the REST or vendor CLI strategy instead of selecting one.")
  String strategy;

  @Option(names = "--plan", description = "Show the plan and exit without running it.")
  boolean plan;

  @Override
  public Integer call() {
    PrintWriter out = spec.commandLine().getOut();
    PrintWriter err = spec.commandLine().getErr();
    Optional<ExportImportStrategy.Kind> kind;
    Optional<SecretRef> passwordRef;
    try {
      kind = StrategyFlag.parse(strategy);
      passwordRef =
          sourceKeystorePasswordRef == null
              ? Optional.empty()
              : Optional.of(SecretRef.parse(sourceKeystorePasswordRef));
    } catch (IllegalArgumentException e) {
      return ExitCodes.fail(out, err, global.json(), ExitCodes.USAGE, e.getMessage());
    }
    ExportImportOperations.ImportOptions options =
        new ExportImportOperations.ImportOptions(
            archive,
            update,
            skipUserUpdate,
            accessEvents,
            auditEvents,
            monitoring,
            settings,
            skipThemes,
            Optional.ofNullable(sourceKeystore),
            passwordRef,
            kind);
    try (Bootstrap boot = Bootstrap.open(global, Env.vars(), Clock.systemUTC())) {
      Services services = boot.services();
      Plan planned;
      try {
        planned = EximOps.open(services).planImport(options);
      } catch (RuntimeException e) {
        return ExitCodes.reportPlanningFailure(out, err, global.json(), e);
      }
      PlanExecutor executor = new PlanExecutor(services, global, out, err, Env.vars());
      return executor.execute(
          new PlanExecutor.Request(
              planned, PlanRegistry.IMPORT, PlanRegistry.importArgs(options), plan, false));
    }
  }
}
