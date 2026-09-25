package com.jaspersoft.jrsctl.app;

import com.jaspersoft.jrsctl.core.Version;
import com.jaspersoft.jrsctl.core.engine.RunRecord;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
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
    footer = {
      "Examples for each command: jrsctl <command> --help",
      "On Windows type bin\\jrsctl.cmd, on Linux bin/jrsctl, where the examples say jrsctl."
    },
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
      DocsCommand.class,
      picocli.CommandLine.HelpCommand.class
    })
public final class JrsctlCommand implements Callable<Integer> {

  @Spec CommandSpec spec;
  @Mixin GlobalOptions global;

  /**
   * No subcommand given. On a terminal, without {@code --json} or {@code --non-interactive}, the
   * guided menu runs (#71); otherwise it is a usage error (exit 1): text mode prints the usage on
   * standard error and {@code --json} prints the error document on standard output and nothing else
   * (review 4.5), so scripts see no change.
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
    if (!global.nonInteractive() && Terminal.present()) {
      // #71: an operator at a terminal gets the guided menu instead of a usage error
      return new GuidedMode(
              cmd.getOut(), passOn(global), this::runCommand, this::pendingRuns, this::snapshotsDir)
          .run();
    }
    cmd.usage(cmd.getErr());
    cmd.getErr().flush();
    return ExitCodes.USAGE;
  }

  private int runCommand(String[] args) {
    picocli.CommandLine root = Main.commandLine();
    root.setOut(spec.commandLine().getOut());
    root.setErr(spec.commandLine().getErr());
    return root.execute(args);
  }

  /** Ids of runs that need recovery; empty when the home or its journal cannot be read. */
  private List<String> pendingRuns() {
    try (Bootstrap boot = Bootstrap.open(global, Env.vars(), Clock.systemUTC())) {
      return boot.services().stateStore().get().pendingRuns().stream()
          .map(RunRecord::runId)
          .toList();
    } catch (RuntimeException e) {
      return List.of();
    }
  }

  /** Where the pre-import snapshot lands, for the menu's restore entry; empty when unknown. */
  private Optional<Path> snapshotsDir() {
    try (Bootstrap boot = Bootstrap.open(global, Env.vars(), Clock.systemUTC())) {
      return Optional.of(boot.services().home().snapshots());
    } catch (RuntimeException e) {
      return Optional.empty();
    }
  }

  /** The global options given with a bare {@code jrsctl}, to pass on to every guided command. */
  static List<String> passOn(GlobalOptions global) {
    List<String> args = new ArrayList<>();
    global.home().ifPresent(h -> args.addAll(List.of("--home", h.toString())));
    global.passphraseFile().ifPresent(f -> args.addAll(List.of("--passphrase-file", f.toString())));
    global.set().forEach((k, v) -> args.addAll(List.of("--set", k + "=" + v)));
    if (global.ascii()) {
      args.add("--ascii");
    }
    if (global.noColor()) {
      args.add("--no-color");
    }
    return args;
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
