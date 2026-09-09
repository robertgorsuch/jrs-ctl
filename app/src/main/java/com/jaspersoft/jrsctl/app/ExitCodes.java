package com.jaspersoft.jrsctl.app;

import com.jaspersoft.jrsctl.core.compat.UnsupportedVersionException;
import com.jaspersoft.jrsctl.core.config.ConfigException;
import com.jaspersoft.jrsctl.core.redact.Redactor;
import com.jaspersoft.jrsctl.core.secrets.SecretException;
import com.jaspersoft.jrsctl.core.state.LockHeldException;
import com.jaspersoft.jrsctl.jrs.api.JrsUnreachableException;
import java.io.PrintWriter;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import picocli.CommandLine;
import picocli.CommandLine.IExecutionExceptionHandler;
import picocli.CommandLine.IParameterExceptionHandler;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.ParseResult;
import picocli.CommandLine.UnmatchedArgumentException;

/**
 * Process exit codes from spec §18 and the one way a command reports a refusal or failure.
 * Invariants: values never change once released; in text mode a failure is {@code error: message}
 * on standard error, in {@code --json} mode it is exactly one {@link JsonOut#error} document on
 * standard output (validated by {@code schema/json/error.schema.json}) and standard error stays
 * silent; every message is redacted before it is written.
 */
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

  /** The {@code error.class} used for an explicit refusal with this exit code. */
  public static String className(int code) {
    return switch (code) {
      case SUCCESS -> "Success";
      case USAGE -> "UsageError";
      case PRECHECK_FAILED -> "PrecheckFailed";
      case FAILED_ROLLED_BACK -> "FailedRolledBack";
      case FAILED_ROLLBACK_INCOMPLETE -> "FailedRollbackIncomplete";
      case CANCELLED -> "Cancelled";
      case UNSUPPORTED -> "Unsupported";
      case SIGNATURE_FAILED -> "SignatureFailed";
      case RECOVERY_REQUIRED -> "RecoveryRequired";
      case LOCK_HELD -> "LockHeld";
      default -> "Error";
    };
  }

  /**
   * Reports a refusal: {@code error: message} on {@code err} in text mode, an error document on
   * {@code out} in JSON mode. Returns {@code code} so callers can {@code return fail(...)}.
   */
  static int fail(PrintWriter out, PrintWriter err, boolean json, int code, String message) {
    return fail(out, err, json, code, className(code), message, Optional.empty(), Map.of());
  }

  /** As {@link #fail(PrintWriter, PrintWriter, boolean, int, String)} with a remediation. */
  static int fail(
      PrintWriter out,
      PrintWriter err,
      boolean json,
      int code,
      String message,
      Optional<String> remediation) {
    return fail(out, err, json, code, className(code), message, remediation, Map.of());
  }

  static int fail(
      PrintWriter out,
      PrintWriter err,
      boolean json,
      int code,
      String errorClass,
      String message,
      Optional<String> remediation,
      Map<String, Object> details) {
    if (json) {
      JsonOut.print(out, JsonOut.error(errorClass, message, code, remediation, details));
      return code;
    }
    String text = remediation.map(r -> message + "; " + r).orElse(message);
    err.println(Redactor.global().redact("error: " + text));
    err.flush();
    return code;
  }

  /**
   * Reports a failure raised while planning or verifying, before anything was mutated: the message
   * is redacted and printed to {@code err}, and the code is {@link Handler#codeFor} except that the
   * catch-all 4 becomes 2 because nothing has changed yet.
   */
  static int reportPlanningFailure(PrintWriter err, RuntimeException e) {
    return reportPlanningFailure(err, err, false, e);
  }

  /** JSON-aware {@link #reportPlanningFailure(PrintWriter, RuntimeException)}. */
  static int reportPlanningFailure(
      PrintWriter out, PrintWriter err, boolean json, RuntimeException e) {
    int code = Handler.codeFor(e);
    code = code == FAILED_ROLLBACK_INCOMPLETE ? PRECHECK_FAILED : code;
    return fail(
        out,
        err,
        json,
        code,
        e.getClass().getSimpleName(),
        messageOf(e),
        Optional.empty(),
        Map.of());
  }

  static String messageOf(Throwable e) {
    return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
  }

  /** True when {@code --json} was given anywhere on the command line that parsed to this result. */
  static boolean jsonRequested(ParseResult parseResult) {
    for (ParseResult r = parseResult; r != null; r = r.subcommand()) {
      if (r.hasMatchedOption("--json")) {
        return true;
      }
    }
    return false;
  }

  /**
   * Maps uncaught exceptions to an exit code: configuration, secret and reachability problems are
   * precheck failures (2, nothing mutated), a held run lock is 9, an unsupported version 6, and
   * every other unexpected exception is reported as rollback-incomplete (4) so nothing is silently
   * reported as success. The message is redacted before it is printed; with {@code --json} it is
   * the error document on standard output.
   */
  static final class Handler implements IExecutionExceptionHandler {
    @Override
    public int handleExecutionException(Exception ex, CommandLine cmd, ParseResult parseResult) {
      int code = codeFor(ex);
      if (jsonRequested(parseResult)) {
        JsonOut.print(
            cmd.getOut(), JsonOut.error(ex.getClass().getSimpleName(), messageOf(ex), code));
        return code;
      }
      String message = Redactor.global().redact("error: " + ex.getMessage());
      cmd.getErr().println(cmd.getColorScheme().errorText(message));
      cmd.getErr().flush();
      return code;
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

  /**
   * Usage errors (exit 1): picocli's default text on standard error, or, when {@code --json} is
   * among the raw arguments, the error document on standard output with the usage line as the
   * remediation.
   */
  static final class ParameterHandler implements IParameterExceptionHandler {
    @Override
    public int handleParseException(ParameterException ex, String[] args) {
      CommandLine cmd = ex.getCommandLine();
      int code = cmd.getCommandSpec().exitCodeOnInvalidInput();
      if (Arrays.asList(args).contains("--json")) {
        JsonOut.print(
            cmd.getOut(),
            JsonOut.error(
                ex.getClass().getSimpleName(),
                messageOf(ex),
                code,
                Optional.of("see: " + cmd.getCommandSpec().qualifiedName() + " --help"),
                Map.of()));
        return code;
      }
      PrintWriter err = cmd.getErr();
      err.println(cmd.getColorScheme().errorText(messageOf(ex)));
      if (!UnmatchedArgumentException.printSuggestions(ex, err)) {
        cmd.usage(err);
      }
      err.flush();
      return code;
    }
  }
}
