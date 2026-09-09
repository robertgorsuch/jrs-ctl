package com.jaspersoft.jrsctl.app.console;

/**
 * An API refusal with the HTTP status and the {@code {error}} message the front-end shows (see
 * {@code web/README.md}). Invariant: the message is operator-facing text that is redacted before it
 * is written, and throwing it from a handler ends the request without any partial body.
 */
public final class ConsoleHttpException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final int status;

  public ConsoleHttpException(int status, String message) {
    super(message);
    this.status = status;
  }

  public int status() {
    return status;
  }

  public static ConsoleHttpException badRequest(String message) {
    return new ConsoleHttpException(400, message);
  }

  public static ConsoleHttpException notFound(String message) {
    return new ConsoleHttpException(404, message);
  }

  public static ConsoleHttpException conflict(String message) {
    return new ConsoleHttpException(409, message);
  }

  public static ConsoleHttpException gone(String message) {
    return new ConsoleHttpException(410, message);
  }

  public static ConsoleHttpException notImplemented(String message) {
    return new ConsoleHttpException(501, message);
  }
}
