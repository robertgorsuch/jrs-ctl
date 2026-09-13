package com.jaspersoft.jrsctl.app.console;

import com.jaspersoft.jrsctl.ops.ReportItem;
import com.jaspersoft.jrsctl.ops.Services;
import com.jaspersoft.jrsctl.ops.smoke.SmokeOperation;
import com.jaspersoft.jrsctl.ops.smoke.SmokeOptions;
import com.jaspersoft.jrsctl.ops.smoke.SmokeReport;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.IntSupplier;

/** Builds the {@link SmokeDoc} returned by {@code GET /api/smoke} and {@code POST /api/smoke}. */
final class SmokeViews {

  private final Services services;

  SmokeViews(
      Services services, RunManager runs, DoctorCache doctor, String bind, IntSupplier port) {
    this.services = Objects.requireNonNull(services, "services");
  }

  SmokeDoc smoke(boolean mutating) {
    SmokeOperation op = new SmokeOperation(services);
    Instant start = Instant.now(services.clock());
    SmokeReport report = op.run(new SmokeOptions(mutating));
    List<SmokeDoc.Item> items = new ArrayList<>();
    for (ReportItem item : report.items()) {
      items.add(
          new SmokeDoc.Item(
              item.name(),
              item.name(),
              item.status().name(),
              item.name(),
              item.detail(),
              item.remediation(),
              0L));
    }
    SmokeDoc.Counts counts =
        new SmokeDoc.Counts(
            report.counts().pass(),
            report.counts().warn(),
            report.counts().fail(),
            report.counts().skip());
    return new SmokeDoc(start, mutating, report.runId(), counts, items, report.exitCode());
  }
}
