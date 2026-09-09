package com.jaspersoft.jrsctl.app.console;

import com.jaspersoft.jrsctl.ops.Services;
import com.jaspersoft.jrsctl.ops.doctor.DoctorOperation;
import com.jaspersoft.jrsctl.ops.doctor.DoctorOptions;
import com.jaspersoft.jrsctl.ops.doctor.DoctorReport;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Runs {@code doctor} for the console and keeps the last report for 60 s (spec §12.1, §13.1) so a
 * dashboard refresh does not probe the server every time. Invariants: at most one doctor run is in
 * flight (callers queue behind it and receive its report); {@link #last()} never triggers a run, so
 * {@code /api/health} stays cheap; the cached report is exactly what a fresh {@code doctor --json}
 * would have produced at {@code ranAt}.
 */
final class DoctorCache {

  static final Duration TTL = Duration.ofSeconds(60);

  /** A report and when it was produced. */
  record Cached(DoctorReport report, Instant ranAt) {}

  private final Services services;
  private Optional<Cached> cached = Optional.empty();

  DoctorCache(Services services) {
    this.services = Objects.requireNonNull(services, "services");
  }

  synchronized Cached current() {
    Instant now = services.clock().instant();
    if (cached.isPresent() && Duration.between(cached.get().ranAt(), now).compareTo(TTL) < 0) {
      return cached.get();
    }
    DoctorReport report = new DoctorOperation(services).run(DoctorOptions.DEFAULT);
    Cached fresh = new Cached(report, services.clock().instant());
    cached = Optional.of(fresh);
    return fresh;
  }

  synchronized Optional<Cached> last() {
    return cached;
  }
}
