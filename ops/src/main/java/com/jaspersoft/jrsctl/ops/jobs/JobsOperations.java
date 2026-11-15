package com.jaspersoft.jrsctl.ops.jobs;

import com.jaspersoft.jrsctl.core.engine.Plan;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * Report jobs across a migration (spec §10.7, issue #304, ADR-0056): {@link #record} writes every
 * job's state to a file, read-only on the server; {@link #planPause} and {@link #planResume} are
 * journaled plans whose rollback undoes exactly what they changed. Invariants: all three talk to
 * the server over REST only, so they work from a remote-only home; a missing or invalid record, or
 * a listing the server refuses while planning, is a {@link JobsException} (exit 2, nothing
 * changed).
 */
public interface JobsOperations {

  /** Operation names of the two plans in the journal and the stored plan. */
  String PAUSE_OPERATION = "jobs.pause";

  String RESUME_OPERATION = "jobs.resume";

  /** {@code out} is the file to write; empty means a new file in the home's {@code jobs/}. */
  record RecordOptions(Optional<Path> out) {
    public RecordOptions {
      Objects.requireNonNull(out, "out");
    }
  }

  /** {@code from} is the record to pause; empty means every job ({@code --all}). */
  record PauseOptions(Optional<Path> from) {
    public PauseOptions {
      Objects.requireNonNull(from, "from");
    }
  }

  /** {@code from} is the record whose active jobs are resumed. */
  record ResumeOptions(Path from) {
    public ResumeOptions {
      Objects.requireNonNull(from, "from");
    }
  }

  JobsRecordReport record(RecordOptions options);

  Plan planPause(PauseOptions options);

  Plan planResume(ResumeOptions options);
}
