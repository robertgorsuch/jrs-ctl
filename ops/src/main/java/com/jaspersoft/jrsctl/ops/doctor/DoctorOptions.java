package com.jaspersoft.jrsctl.ops.doctor;

import java.util.Objects;
import java.util.Optional;

/**
 * Flags for {@code doctor} (spec §5.7). Invariants: {@code allowUnsupported} downgrades only the
 * compat check from FAIL to WARN and is always written to the audit table; it never changes any
 * other check. {@code ownRun} is set only when the doctor runs inside a run (a plan's doctor step):
 * the "runs" and "lock" checks then leave out exactly that run's pending row and lock, and still
 * fail on any other run.
 */
public record DoctorOptions(boolean allowUnsupported, Optional<String> ownRun) {
  public static final DoctorOptions DEFAULT = new DoctorOptions(false, Optional.empty());

  public DoctorOptions {
    Objects.requireNonNull(ownRun, "ownRun");
  }

  /** The command-line doctor: no run of its own. */
  public DoctorOptions(boolean allowUnsupported) {
    this(allowUnsupported, Optional.empty());
  }

  /** The doctor as a step of run {@code runId}. */
  public static DoctorOptions insideRun(String runId) {
    return new DoctorOptions(false, Optional.of(Objects.requireNonNull(runId, "runId")));
  }
}
