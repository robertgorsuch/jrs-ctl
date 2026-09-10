package com.jaspersoft.jrsctl.jrs.vendor;

import java.time.Duration;
import java.util.List;

/**
 * Outcome of one vendor-tool invocation (spec §7.4). Invariant: {@code tail} holds the last few
 * already-redacted output lines so a failure message can quote the tool without re-reading a log;
 * {@link NotStarted} means the process was never launched (missing script, missing {@code
 * vendor.javaHome}) and therefore nothing on disk changed; {@link Completed#reported} carries what
 * the tool's own output said, because a buildomatic wrapper's exit code cannot be trusted on its
 * own.
 */
public sealed interface VendorRun
    permits VendorRun.Completed, VendorRun.TimedOut, VendorRun.NotStarted {

  /**
   * The process exited on its own. A zero exit code is not enough on its own: {@code js-import.sh}
   * in 10.0.0 runs {@code js-ant validate-database validate-keystore} and guards the import with a
   * bare {@code if [ $? -eq 0 ]} that has no else branch, so a validation failure imports nothing
   * and still exits 0. {@link #ok()} therefore also requires that the transcript did not report a
   * failure.
   */
  record Completed(int exitCode, Duration elapsed, List<String> tail, Reported reported)
      implements VendorRun {
    public Completed {
      tail = List.copyOf(tail);
    }

    public boolean ok() {
      return exitCode == 0 && reported != Reported.FAILED;
    }
  }

  /**
   * What the tool said about itself, read from its output. {@link #SUCCEEDED} is Ant's {@code BUILD
   * SUCCESSFUL} or the {@code VALIDATION COMPLETED} that buildomatic's {@code ImportExportLogger}
   * prints in its place; {@link #FAILED} is Ant's {@code BUILD FAILED} or the {@code Checking Ant
   * return code: BAD} of the Windows wrappers; {@link #SILENT} means neither appeared, which a
   * caller that needs positive evidence must not read as success.
   */
  enum Reported {
    SUCCEEDED,
    FAILED,
    SILENT
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
