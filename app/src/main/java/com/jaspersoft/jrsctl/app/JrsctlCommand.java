package com.jaspersoft.jrsctl.app;

import com.jaspersoft.jrsctl.core.Version;
import picocli.CommandLine.Command;
import picocli.CommandLine.IVersionProvider;

/**
 * Root command. Subcommands are added phase by phase; each one is a thin adapter from flags to an
 * operation in {@code ops}. Invariant: no subcommand mutates the server or filesystem outside a
 * {@code Plan} (spec §0 rule 4).
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
    subcommands = {SelfCheckCommand.class})
public final class JrsctlCommand implements Runnable {

  @Override
  public void run() {
    // No subcommand given: picocli prints usage because of mixinStandardHelpOptions handling below.
    new picocli.CommandLine(this).usage(System.out);
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
