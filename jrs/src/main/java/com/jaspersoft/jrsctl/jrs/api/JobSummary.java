package com.jaspersoft.jrsctl.jrs.api;

import java.util.Locale;
import java.util.Objects;

/**
 * One report job as {@code GET /rest_v2/jobs} lists it (REST API reference 8.2 p.145, 10.0
 * pp.196-197; issue #304, ADR-0056). Invariants: no field is null; {@code id} is the server's
 * numeric id as text and never blank; a field the server omits is the empty string; {@code state}
 * is {@code state.value} in upper case ({@code NORMAL}, {@code EXECUTING}, {@code PAUSED}, {@code
 * WAITING} or an error state), empty when the server sent none.
 */
public record JobSummary(
    String id, String label, String reportUnitUri, String owner, String state) {

  public static final String PAUSED = "PAUSED";

  public JobSummary {
    Objects.requireNonNull(id, "id");
    if (id.isBlank()) {
      throw new IllegalArgumentException("a job id is never blank");
    }
    label = Objects.requireNonNullElse(label, "");
    reportUnitUri = Objects.requireNonNullElse(reportUnitUri, "");
    owner = Objects.requireNonNullElse(owner, "");
    state = Objects.requireNonNullElse(state, "").trim().toUpperCase(Locale.ROOT);
  }

  /** True when the server reports the job as paused. */
  public boolean paused() {
    return PAUSED.equals(state);
  }
}
