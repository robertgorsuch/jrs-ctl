package com.jaspersoft.jrsctl.core.state;

/** Unchecked wrapper for SQL failures; the state store never leaks {@code SQLException}. */
public final class StateStoreException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  public StateStoreException(String message, Throwable cause) {
    super(message, cause);
  }

  public StateStoreException(String message) {
    super(message);
  }
}
