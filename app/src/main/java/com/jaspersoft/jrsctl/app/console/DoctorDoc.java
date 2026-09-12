package com.jaspersoft.jrsctl.app.console;

import java.time.Instant;
import java.util.List;

/**
 * The {@code GET /api/doctor} document (spec §13.1, {@code api-doctor.schema.json}). Invariants:
 * component order is the key order on the wire; every component is always present, because a doctor
 * report always has counts, items and an exit code; {@code id}, {@code name} and {@code title} all
 * carry {@link com.jaspersoft.jrsctl.ops.ReportItem#name()}, which the front-end relies on and this
 * record preserves rather than corrects.
 */
record DoctorDoc(Instant ranAt, Counts counts, List<Item> items, int exitCode) {

  record Counts(int pass, int warn, int fail, int skip) {}

  record Item(
      String id, String name, String status, String title, String detail, String remediation) {}
}
