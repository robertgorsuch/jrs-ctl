package com.jaspersoft.jrsctl.app.console;

/**
 * Raised before the listener opens when the requested bind is not allowed by spec §11.2 (a
 * non-loopback address without TLS and {@code console.auth.mode: local}) or the TLS material cannot
 * be loaded. Invariant: nothing has been bound, written or mutated when it is thrown, so the CLI
 * maps it to exit code 2.
 */
public final class ConsoleRefusedException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public ConsoleRefusedException(String message) {
    super(message);
  }

  public ConsoleRefusedException(String message, Throwable cause) {
    super(message, cause);
  }
}
