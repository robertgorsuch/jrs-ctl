package com.jaspersoft.jrsctl.app;

import com.jaspersoft.jrsctl.core.compat.UnsupportedVersionException;
import com.jaspersoft.jrsctl.core.config.ConfigException;
import com.jaspersoft.jrsctl.core.redact.Redactor;
import com.jaspersoft.jrsctl.core.secrets.SecretException;
import com.jaspersoft.jrsctl.core.state.LockHeldException;
import com.jaspersoft.jrsctl.jrs.api.JrsUnreachableException;
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
   * Maps uncaught exceptions to an exit code: configuration, secret and reachability problems are
   * precheck failures (2, nothing mutated), a held run lock is 9, an unsupported version 6, and
   * every other unexpected exception is reported as rollback-incomplete (4) so nothing is silently
   * reported as success. The message is redacted before it is printed.
   */
  static final class Handler implements IExecutionExceptionHandler {
    @Override
    public int handleExecutionException(Exception ex, CommandLine cmd, ParseResult parseResult) {
      String message = Redactor.global().redact("error: " + ex.getMessage());
      cmd.getErr().println(cmd.getColorScheme().errorText(message));
      cmd.getErr().flush();
      return codeFor(ex);
    }

    static int codeFor(Throwable ex) {
      if (ex instanceof ConfigException) {
        return PRECHECK_FAILED;
      }
      if (ex instanceof JrsUnreachableException) {
        return PRECHECK_FAILED;
      }
      if (ex instanceof SecretException) {
        return PRECHECK_FAILED;
      }
      if (ex instanceof LockHeldException) {
        return LOCK_HELD;
      }
      if (ex instanceof UnsupportedVersionException) {
        return UNSUPPORTED;
      }
      return FAILED_ROLLBACK_INCOMPLETE;
    }
  }
}
