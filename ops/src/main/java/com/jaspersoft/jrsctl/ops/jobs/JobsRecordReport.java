package com.jaspersoft.jrsctl.ops.jobs;

import com.jaspersoft.jrsctl.jrs.api.JobSummary;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * The result of {@code jobs record} (issue #304): where the record went and what it holds.
 * Invariants: {@code file} exists and holds exactly {@code jobs} when this is returned; the counts
 * add up to {@code jobs.size()}; {@code paused} counts jobs in state {@code PAUSED}, {@code active}
 * those in {@link JobSelection#ACTIVE}, {@code other} the rest (error states and none).
 */
public record JobsRecordReport(
    Path file,
    String serverUrl,
    String serverVersion,
    Instant recordedAt,
    List<JobSummary> jobs,
    int active,
    int paused,
    int other) {

  public JobsRecordReport {
    Objects.requireNonNull(file, "file");
    Objects.requireNonNull(serverUrl, "serverUrl");
    Objects.requireNonNull(serverVersion, "serverVersion");
    Objects.requireNonNull(recordedAt, "recordedAt");
    jobs = List.copyOf(jobs);
  }

  static JobsRecordReport of(Path file, JobsRecord record) {
    int active = 0;
    int paused = 0;
    int other = 0;
    for (JobSummary job : record.jobs()) {
      if (job.paused()) {
        paused++;
      } else if (JobSelection.ACTIVE.contains(job.state())) {
        active++;
      } else {
        other++;
      }
    }
    return new JobsRecordReport(
        file,
        record.serverUrl(),
        record.serverVersion(),
        record.recordedAt(),
        record.jobs(),
        active,
        paused,
        other);
  }
}
