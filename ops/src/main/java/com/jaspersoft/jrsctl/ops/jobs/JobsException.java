package com.jaspersoft.jrsctl.ops.jobs;

import java.util.Objects;

/**
 * A refusal while recording or planning a change to report jobs (issue #304, ADR-0056), carrying
 * the exit code the CLI reports and the remediation. Invariants: {@link #exitCode()} is 2
 * (precheck, nothing mutated); the message never carries a password.
 */
public final class JobsException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public static final int PRECHECK = 2;

  private final int exitCode;
  private final String remediation;

  public JobsException(int exitCode, String message, String remediation) {
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
