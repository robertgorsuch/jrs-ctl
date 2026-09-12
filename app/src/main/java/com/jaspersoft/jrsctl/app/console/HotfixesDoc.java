package com.jaspersoft.jrsctl.app.console;

import java.time.Instant;
import java.util.List;

/**
 * The {@code GET /api/hotfixes} document (spec §13.1, {@code api-hotfixes.schema.json}).
 * Invariants: component order is the key order on the wire; {@code files} is a count, not a list,
 * because the console only shows how many files a hotfix touched; {@code blockedBy} is empty rather
 * than absent, and lists the later installed hotfixes that share a file with this one, so a
 * non-empty list means this hotfix cannot be rolled back on its own.
 */
record HotfixesDoc(List<Row> hotfixes) {

  /**
   * One installed or rolled-back hotfix, and the later installed hotfixes blocking its rollback.
   */
  record Row(
      String id,
      String title,
      Instant installedAt,
      int files,
      String state,
      List<String> blockedBy) {}
}
