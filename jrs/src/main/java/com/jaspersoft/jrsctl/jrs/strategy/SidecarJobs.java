package com.jaspersoft.jrsctl.jrs.strategy;

import com.jaspersoft.jrsctl.jrs.api.JobSummary;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The {@code jobs} block of an export sidecar (issue #309, ADR-0059): every report job of the
 * exporting server with its state when the sidecar was written, the same data {@code jobs record}
 * writes (ADR-0056), or the reason there is none. Invariants: {@code recorded} is true exactly when
 * {@code reason} is empty; {@code jobs} is empty whenever {@code recorded} is false; {@code
 * recordedAt} and {@code serverUrl} are present whenever {@code recorded} is true; ids are unique.
 */
public record SidecarJobs(
    boolean recorded,
    Optional<Instant> recordedAt,
    Optional<String> serverUrl,
    List<JobSummary> jobs,
    Optional<String> reason) {

  public SidecarJobs {
    recordedAt = recordedAt == null ? Optional.empty() : recordedAt;
    serverUrl = serverUrl == null ? Optional.empty() : serverUrl;
    jobs = jobs == null ? List.of() : List.copyOf(jobs);
    reason =
        reason == null ? Optional.empty() : reason.map(String::strip).filter(r -> !r.isEmpty());
    if (recorded == reason.isPresent()) {
      throw new IllegalArgumentException(
          "a sidecar jobs block is either recorded or carries the reason it is not");
    }
    if (!recorded && !jobs.isEmpty()) {
      throw new IllegalArgumentException("a jobs block that is not recorded lists no jobs");
    }
    if (recorded && (recordedAt.isEmpty() || serverUrl.isEmpty())) {
      throw new IllegalArgumentException("a recorded jobs block names its time and server");
    }
    if (jobs.stream().map(JobSummary::id).distinct().count() != jobs.size()) {
      throw new IllegalArgumentException("a job id appears twice in the sidecar");
    }
  }

  /** The jobs listed at {@code at} on {@code serverUrl}. */
  public static SidecarJobs recorded(Instant at, String serverUrl, List<JobSummary> jobs) {
    Objects.requireNonNull(at, "at");
    Objects.requireNonNull(serverUrl, "serverUrl");
    return new SidecarJobs(true, Optional.of(at), Optional.of(serverUrl), jobs, Optional.empty());
  }

  /** No jobs were recorded, for {@code reason}. */
  public static SidecarJobs notRecorded(String reason) {
    Objects.requireNonNull(reason, "reason");
    return new SidecarJobs(
        false, Optional.empty(), Optional.empty(), List.of(), Optional.of(reason));
  }
}
