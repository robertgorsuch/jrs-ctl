package com.jaspersoft.jrsctl.app;

import picocli.CommandLine;

/**
 * Process entry point. Invariants: the JSON log location is fixed from the arguments and
 * environment before any class that owns a logger is loaded, so the very first log line lands under
 * {@code $JRSCTL_HOME/logs}; apart from that the class only translates a picocli exit code into the
 * process exit code, so it stays trivially testable through {@link JrsctlCommand}.
 */
public final class Main {

  private Main() {}

  public static void main(String[] args) {
    LogFile.configure(args, System.getenv());
    System.exit(run(args));
  }

  static int run(String... args) {
    LogFile.configure(args, System.getenv());
    return new CommandLine(new JrsctlCommand())
        .setCaseInsensitiveEnumValuesAllowed(true)
        .setExecutionExceptionHandler(new ExitCodes.Handler())
        .execute(args);
  }
}
