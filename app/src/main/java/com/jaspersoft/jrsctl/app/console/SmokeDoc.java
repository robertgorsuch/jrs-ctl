package com.jaspersoft.jrsctl.app.console;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

record SmokeDoc(
    Instant ranAt,
    boolean mutating,
    Optional<String> runId,
    Counts counts,
    List<Item> items,
    int exitCode) {

  record Counts(int pass, int warn, int fail, int skip) {}

  record Item(
      String id,
      String name,
      String status,
      String title,
      String detail,
      String remediation,
      long durationMs) {}
}
