package com.jaspersoft.jrsctl.core.engine;

import java.util.Objects;

/**
 * Raised when {@code runs.lock} cannot be created or locked at all, as opposed to being held by
 * another run ({@link LockHeldException}): the jrsctl home is not a folder, the lock file is a
 * folder, or this user cannot write there (#351). The lock is the first thing a run takes, before
 * its journal row and before any step, so nothing has changed and the CLI maps this to exit 2.
 * Invariant: the message names the path and the remediation is never blank.
 */
public final class RunLockUnavailableException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  private final String remediation;

  public RunLockUnavailableException(String message, String remediation, Throwable cause) {
    super(Objects.requireNonNull(message, "message"), cause);
    this.remediation = Objects.requireNonNull(remediation, "remediation");
  }

  /** What the operator should do; always ends by saying nothing was changed. */
  public String remediation() {
    return remediation;
  }
}
