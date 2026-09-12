package com.jaspersoft.jrsctl.app.console;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The {@code POST /api/plan} response (spec §13.1, {@code api-plan.schema.json}). Invariants:
 * component order is the key order on the wire; every {@link Summary} value is a display string the
 * console renders verbatim, so objects and lists are flattened before they get here, with {@code
 * warnings} the one list because the front-end renders it as callouts; {@code resourcesTouched} is
 * omitted rather than empty when the plan touches no repository resources.
 */
record PlanDoc(String planId, PlanBody plan) {

  /** The plan's operation, human title, fingerprint, expiry, summary and ordered steps. */
  record PlanBody(
      String op,
      String title,
      String fingerprint,
      Instant validUntil,
      Summary summary,
      List<PlanStep> steps) {}

  /** The plan's effect on files and repository resources, backups, rollback and risk. */
  record Summary(
      String filesTouched,
      @JsonInclude(JsonInclude.Include.NON_ABSENT) Optional<String> resourcesTouched,
      String service,
      String backups,
      String rollbackPoints,
      String strategy,
      String downtime,
      List<String> warnings) {}

  /** One step of the plan, with its phase, display title and the reason it runs. */
  record PlanStep(String id, String phase, String title, String why) {}
}
