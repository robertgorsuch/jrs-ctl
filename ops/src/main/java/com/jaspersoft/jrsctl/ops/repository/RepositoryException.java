package com.jaspersoft.jrsctl.ops.repository;

import java.util.Objects;

/**
 * A refusal while planning a repository operation, carrying the exit code the CLI reports and the
 * remediation. Invariants: {@link #exitCode()} is 1 (usage), 2 (precheck, nothing mutated) or 6
 * (unsupported); the message never carries a password.
 */
public final class RepositoryException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public static final int USAGE = 1;
  public static final int PRECHECK = 2;
  public static final int UNSUPPORTED = 6;

  private final int exitCode;
  private final String remediation;

  public RepositoryException(int exitCode, String message, String remediation) {
    super(Objects.requireNonNull(message, "message"));
    this.exitCode = exitCode;
    this.remediation = Objects.requireNonNull(remediation, "remediation");
  }

  public int exitCode() {
    return exitCode;
  }

  public String remediation() {
    return remediation;
  }
}
