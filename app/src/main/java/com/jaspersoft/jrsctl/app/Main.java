package com.jaspersoft.jrsctl.app;

import picocli.CommandLine;

/**
 * Process entry point. Invariant: the only thing this class does is translate a picocli exit code
 * into the process exit code, so it stays trivially testable through {@link JrsctlCommand}.
 */
public final class Main {

  private Main() {}

  public static void main(String[] args) {
    System.exit(run(args));
  }

  static int run(String... args) {
    return new CommandLine(new JrsctlCommand())
        .setCaseInsensitiveEnumValuesAllowed(true)
        .setExecutionExceptionHandler(new ExitCodes.Handler())
        .execute(args);
  }
}
