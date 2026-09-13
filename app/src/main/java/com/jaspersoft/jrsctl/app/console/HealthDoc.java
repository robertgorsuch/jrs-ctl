package com.jaspersoft.jrsctl.app.console;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The {@code GET /api/health} document (spec §13.1, {@code api-health.schema.json}). Invariants:
 * component order is the key order on the wire; {@code lastRun}, {@code finishedAt} and {@code
 * stepId} are emitted as {@code null} when absent because the front-end reads them unconditionally,
 * while {@code doctor}, {@code runId} and {@code pid} are omitted entirely, which is what {@link
 * JsonInclude.Include#NON_ABSENT} does to an empty {@code Optional}.
 */
record HealthDoc(
    Tool tool,
    String bind,
    String networkMode,
    Optional<LastRun> lastRun,
    Lock lock,
    List<PendingRun> pendingRuns,
    Snapshots snapshots,
    @JsonInclude(JsonInclude.Include.NON_ABSENT) Optional<DoctorSummary> doctor) {

  /** The tool's own version and the hotfix compatibility matrix version it targets. */
  record Tool(String version, String matrixVersion) {}

  /**
   * The most recently finished run; {@code finishedAt} stays an {@code Optional} serialised as null
   * when absent for the shape's sake, but {@code lastRun} only ever describes a run that has
   * already reached a terminal state, so it is set in practice.
   */
  record LastRun(String id, String op, String outcome, Optional<Instant> finishedAt) {}

  /** Whether the run lock is held; {@code runId} and {@code pid} are omitted when it is not. */
  record Lock(
      boolean held,
      @JsonInclude(JsonInclude.Include.NON_ABSENT) Optional<String> runId,
      @JsonInclude(JsonInclude.Include.NON_ABSENT) Optional<String> pid) {}

  /**
   * A run without a terminal state; {@code stepId} is null until its first transition is
   * journalled.
   */
  record PendingRun(String id, String op, Instant startedAt, Optional<String> stepId) {}

  /** The snapshot store's size and retention policy as of this call. */
  record Snapshots(int count, long bytes, int retentionDays) {}

  /**
   * The cached doctor report's counts, when it ran, and its warn/fail items; present only once a
   * doctor run has been cached.
   */
  record DoctorSummary(int pass, int warn, int fail, Instant ranAt, List<Attention> attention) {}

  /** One doctor item needing attention: its warn or fail status, title and detail. */
  record Attention(String status, String title, String detail) {}
}
