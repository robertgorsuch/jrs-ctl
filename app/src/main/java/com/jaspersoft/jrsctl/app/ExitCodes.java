package com.jaspersoft.jrsctl.app;

import picocli.CommandLine;
import picocli.CommandLine.IExecutionExceptionHandler;
import picocli.CommandLine.ParseResult;

/** Process exit codes from spec §18. Invariant: values never change once released. */
public final class ExitCodes {

  public static final int SUCCESS = 0;
  public static final int USAGE = 1;
  public static final int PRECHECK_FAILED = 2;
  public static final int FAILED_ROLLED_BACK = 3;
  public static final int FAILED_ROLLBACK_INCOMPLETE = 4;
  public static final int CANCELLED = 5;
  public static final int UNSUPPORTED = 6;
  public static final int SIGNATURE_FAILED = 7;
  public static final int RECOVERY_REQUIRED = 8;
  public static final int LOCK_HELD = 9;

  private ExitCodes() {}

  /**
   * Maps uncaught exceptions to an exit code. Until the engine lands (Phase 1) every unexpected
   * exception is a rollback-incomplete failure so that nothing is silently reported as success.
   */
  static final class Handler implements IExecutionExceptionHandler {
    @Override
    public int handleExecutionException(Exception ex, CommandLine cmd, ParseResult parseResult) {
      cmd.getErr().println(cmd.getColorScheme().errorText("error: " + ex.getMessage()));
      return FAILED_ROLLBACK_INCOMPLETE;
    }
  }
}
