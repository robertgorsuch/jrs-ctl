package com.jaspersoft.jrsctl.core.engine;

import java.time.Duration;

/**
 * How the Runner waits between retry attempts (spec §6.5), abstracted so tests never sleep.
 * Invariant: an interrupted wait restores the interrupt flag and surfaces as {@link
 * CancellationToken.CancelledException}, which the Runner treats like any other cancellation.
 */
@FunctionalInterface
public interface Sleeper {

  void sleep(Duration duration);

  /** Real wall-clock sleeping. */
  static Sleeper system() {
    return duration -> {
      if (duration.isZero() || duration.isNegative()) {
        return;
      }
      try {
        Thread.sleep(duration);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new CancellationToken.CancelledException("interrupted while waiting to retry");
      }
    };
  }

  /** Never waits; for tests. */
  static Sleeper none() {
    return duration -> {};
  }
}
