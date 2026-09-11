package com.jaspersoft.jrsctl.app;

import com.jaspersoft.jrsctl.core.Version;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.IVersionProvider;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

/**
 * Root command. Subcommands are added phase by phase; each one is a thin adapter from flags to an
 * operation in {@code ops}. Invariant: no subcommand mutates the server or filesystem outside a
 * {@code Plan} (spec §0 rule 4); {@code init} writes only {@code config.yaml} after confirmation.
 */
@Command(
    name = "jrsctl",
    mixinStandardHelpOptions = true,
    versionProvider = JrsctlCommand.VersionProvider.class,
    header = "jrsctl - JasperReports Server lifecycle tool (Actian Jaspersoft)",
    exitCodeOnInvalidInput = ExitCodes.USAGE,
    exitCodeOnExecutionException = ExitCodes.FAILED_ROLLBACK_INCOMPLETE,
    description =
        "Apply hotfixes, export and import repository content, and upgrade JasperReports Server safely.",
    subcommands = {
      SelfCheckCommand.class,
      InitCommand.class,
      DoctorCommand.class,
      SmokeCommand.class,
      ConfigCommand.class,
      HotfixCommand.class,
      ExportCommand.class,
      ImportCommand.class,
      UpgradeCommand.class,
      CustomizationsCommand.class,
      RunsCommand.class,
      KeysCommand.class,
      SecretsCommand.class,
      ConsoleCommand.class,
      DocsCommand.class,
      picocli.CommandLine.HelpCommand.class
    })
public final class JrsctlCommand implements Callable<Integer> {

  @Spec CommandSpec spec;
  @Mixin GlobalOptions global;

  /**
   * No subcommand given: a usage error (exit 1). Text mode prints the usage on standard error;
   * {@code --json} prints the error document on standard output and nothing else (review 4.5).
   */
  @Override
  public Integer call() {
    picocli.CommandLine cmd = spec.commandLine();
    if (global.json()) {
      JsonOut.print(
          cmd.getOut(),
          JsonOut.error(
              "UsageException",
              "no command given",
              ExitCodes.USAGE,
              Optional.of("see: jrsctl --help"),
              Map.of()));
      return ExitCodes.USAGE;
    }
    cmd.usage(cmd.getErr());
    cmd.getErr().flush();
    return ExitCodes.USAGE;
  }

  /** Supplies {@code --version} output from the build-time version resource. */
  public static final class VersionProvider implements IVersionProvider {
    @Override
    public String[] getVersion() {
      Version v = Version.current();
      return new String[] {
        v.banner(), "Java " + Runtime.version() + " (" + System.getProperty("java.vendor") + ")"
      };
    }
  }
}
