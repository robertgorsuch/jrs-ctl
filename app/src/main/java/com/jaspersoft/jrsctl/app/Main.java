package com.jaspersoft.jrsctl.app;

import picocli.CommandLine;

/**
 * Process entry point. Invariants: the JSON log location is fixed from the arguments and
 * environment before any class that owns a logger is loaded, so the very first log line lands under
 * {@code $JRSCTL_HOME/logs}; the fully configured command line (case-insensitive enums, the exit
 * code handler and {@code --explain} on every command) is built by exactly one factory, {@link
 * #commandLine()}, so tests exercise the same tree an operator gets; apart from that the class only
 * translates a picocli exit code into the process exit code.
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

  /** The complete command tree as the process runs it. */
  static CommandLine commandLine() {
    return Explain.install(
        new CommandLine(new JrsctlCommand())
            .setCaseInsensitiveEnumValuesAllowed(true)
            .setExecutionExceptionHandler(new ExitCodes.Handler()));
  }
}
