package com.jaspersoft.jrsctl.jrs.vendor;

import java.time.Duration;
import java.util.List;

/**
 * Outcome of one vendor-tool invocation (spec §7.4). Invariant: {@code tail} holds the last few
 * already-redacted output lines so a failure message can quote the tool without re-reading a log;
 * {@link NotStarted} means the process was never launched (missing script, missing {@code
 * vendor.javaHome}) and therefore nothing on disk changed.
 */
public sealed interface VendorRun
    permits VendorRun.Completed, VendorRun.TimedOut, VendorRun.NotStarted {

  /** The process exited on its own; {@code exitCode == 0} is success. */
  record Completed(int exitCode, Duration elapsed, List<String> tail) implements VendorRun {
    public Completed {
      tail = List.copyOf(tail);
    }

    public boolean ok() {
      return exitCode == 0;
    }
  }

  /** The process was killed after {@code timeout}. */
  record TimedOut(Duration timeout, List<String> tail) implements VendorRun {
    public TimedOut {
      tail = List.copyOf(tail);
    }
  }

  /** The process was never launched; {@code remediation} says what to fix. */
  record NotStarted(String reason, String remediation) implements VendorRun {}
}
