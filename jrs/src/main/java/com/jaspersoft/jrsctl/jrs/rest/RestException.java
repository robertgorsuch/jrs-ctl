package com.jaspersoft.jrsctl.jrs.rest;

import java.util.Objects;

/**
 * A response the caller could not use: a non-2xx status on a call that needed one, or a body that
 * did not parse. Invariants: the message names the method, the path without its query string and
 * the status, never a header; any body excerpt has passed through the client's {@code Redactor} and
 * is capped at {@link #EXCERPT_LIMIT} characters.
 */
public final class RestException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public static final int EXCERPT_LIMIT = 200;

  private final int status;
  private final String method;
  private final String path;

  public RestException(int status, String method, String path, String message) {
    super(message);
    this.status = status;
    this.method = Objects.requireNonNull(method, "method");
    this.path = Objects.requireNonNull(path, "path");
  }

  public RestException(int status, String method, String path, String message, Throwable cause) {
    super(message, cause);
    this.status = status;
    this.method = Objects.requireNonNull(method, "method");
    this.path = Objects.requireNonNull(path, "path");
  }

  /** HTTP status, or 0 when the failure was not a status. */
  public int status() {
    return status;
  }

  public String method() {
    return method;
  }

  /** Request path without query string. */
  public String path() {
    return path;
  }

  /** True for 401 and 403. */
  public boolean authenticationFailure() {
    return status == 401 || status == 403;
  }
}
