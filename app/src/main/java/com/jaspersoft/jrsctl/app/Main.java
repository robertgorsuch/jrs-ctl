package com.jaspersoft.jrsctl.app;

import picocli.CommandLine;

/**
 * Process entry point. Invariants: the JSON log location is fixed from the arguments and
 * environment before any class that owns a logger is loaded, so the very first log line lands under
 * {@code $JRSCTL_HOME/logs}; apart from that the class only translates a picocli exit code into the
 * process exit code, so it stays trivially testable through {@link JrsctlCommand}; every {@link
 * CommandLine} the tool runs comes from {@link #commandLine()} so usage errors and uncaught
 * exceptions honour {@code --json} the same way in production and in tests.
 */
public final class Main {

  private Main() {}

  public static void main(String[] args) {
    LogFile.configure(args, System.getenv());
    System.exit(run(args));
  }

  static int run(String... args) {
    LogFile.configure(args, System.getenv());
    return commandLine().execute(args);
  }

  /** The fully configured root command line: exception handlers and enum parsing. */
  static CommandLine commandLine() {
    return new CommandLine(new JrsctlCommand())
        .setCaseInsensitiveEnumValuesAllowed(true)
        .setExecutionExceptionHandler(new ExitCodes.Handler())
        .setParameterExceptionHandler(new ExitCodes.ParameterHandler());
  }
}
