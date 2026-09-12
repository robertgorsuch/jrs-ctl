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

  record Tool(String version, String matrixVersion) {}

  record LastRun(String id, String op, String outcome, Optional<Instant> finishedAt) {}

  record Lock(
      boolean held,
      @JsonInclude(JsonInclude.Include.NON_ABSENT) Optional<String> runId,
      @JsonInclude(JsonInclude.Include.NON_ABSENT) Optional<String> pid) {}

  record PendingRun(String id, String op, Instant startedAt, Optional<String> stepId) {}

  record Snapshots(int count, long bytes, int retentionDays) {}

  record DoctorSummary(int pass, int warn, int fail, Instant ranAt, List<Attention> attention) {}

  record Attention(String status, String title, String detail) {}
}
