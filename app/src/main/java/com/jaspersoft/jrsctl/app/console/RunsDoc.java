package com.jaspersoft.jrsctl.app.console;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The {@code GET /api/runs} and {@code GET /api/runs/{id}} documents (spec §13.1, {@code
 * api-runs.schema.json} and {@code api-runs-show.schema.json}). Invariants: component order is the
 * key order on the wire; {@link RunDetail} repeats {@link RunItem}'s nine components in the same
 * order because a record cannot extend another and {@code @JsonUnwrapped} is unreliable on record
 * components, so {@link RunDetail#of} is the only way it should be built; {@code failure} is
 * omitted for a run that succeeded or is still running; {@code finishedAt} and a step's {@code
 * durationMs} are emitted as {@code null} while unknown, because the front-end reads them
 * unconditionally.
 */
final class RunsDoc {

  private RunsDoc() {}

  /** The {@code GET /api/runs} response: every run this console knows about, newest first. */
  record RunList(List<RunItem> runs) {}

  /** One run's row in the list: identity, timing, outcome and what actions are available. */
  record RunItem(
      String id,
      String op,
      String target,
      Instant startedAt,
      Optional<Instant> finishedAt,
      long durationMs,
      String outcome,
      boolean rollbackAvailable,
      boolean supportBundleAvailable) {}

  /** The {@code GET /api/runs/{id}} response: a run's list row plus its steps and any failure. */
  record RunDetail(
      String id,
      String op,
      String target,
      Instant startedAt,
      Optional<Instant> finishedAt,
      long durationMs,
      String outcome,
      boolean rollbackAvailable,
      boolean supportBundleAvailable,
      String title,
      String subtitle,
      String backups,
      List<StepRow> steps,
      @JsonInclude(JsonInclude.Include.NON_ABSENT) Optional<Failure> failure) {

    /** The list item plus the detail-only keys, in the order the console README documents. */
    static RunDetail of(
        RunItem base,
        String title,
        String subtitle,
        String backups,
        List<StepRow> steps,
        Optional<Failure> failure) {
      return new RunDetail(
          base.id(),
          base.op(),
          base.target(),
          base.startedAt(),
          base.finishedAt(),
          base.durationMs(),
          base.outcome(),
          base.rollbackAvailable(),
          base.supportBundleAvailable(),
          title,
          subtitle,
          backups,
          steps,
          failure);
    }
  }

  /** One plan step's replayed status: its phase, display title, reason and duration. */
  record StepRow(
      String id,
      String phase,
      String title,
      String why,
      String status,
      Optional<Long> durationMs) {}

  /** Why a run did not succeed: the failing step, the cause, backups made and what to do next. */
  record Failure(String stepId, String cause, List<String> backups, String nextAction) {}
}
