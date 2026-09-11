package com.jaspersoft.jrsctl.ops.doctor;

import com.jaspersoft.jrsctl.core.platform.TomcatLayout;
import com.jaspersoft.jrsctl.ops.ReportItem;
import com.jaspersoft.jrsctl.ops.Services;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code jrsctl doctor} (spec §12.1): runs every check in a fixed order and returns a {@link
 * DoctorReport}. Invariants: the operation mutates nothing but the audit table (one row when {@code
 * --allow-unsupported} is given); the server is contacted through one {@link ServerProbe}, and when
 * it is unreachable every server-dependent check is a SKIP; a check that throws becomes a FAIL
 * naming the exception rather than aborting the report; the report always contains every check name
 * so consumers can key on it.
 */
public final class DoctorOperation {

  /** Check names in report order. */
  public static final List<String> CHECKS =
      List.of(
          "runtime",
          "config",
          "secrets",
          "server",
          "auth",
          "identity",
          "compat",
          "capabilities",
          "layout",
          "service",
          "permissions",
          "disk",
          "keystore",
          "vendor",
          "vendor-java",
          "database",
          "state",
          "runs",
          "lock",
          "snapshots",
          "network",
          "elevated");

  private static final Logger LOG = LoggerFactory.getLogger(DoctorOperation.class);

  private final Services services;

  public DoctorOperation(Services services) {
    this.services = Objects.requireNonNull(services, "services");
  }

  public DoctorReport run(DoctorOptions options) {
    Objects.requireNonNull(options, "options");
    if (options.allowUnsupported()) {
      audit("--allow-unsupported", "doctor run with the compat check downgraded to WARN");
    }
    List<ReportItem> items = new ArrayList<>();
    items.add(guard("runtime", LocalChecks::runtime));
    items.add(guard("config", LocalChecks::config));
    items.add(guard("secrets", LocalChecks::secrets));

    ServerProbe probe = ServerProbe.connect(services);
    items.add(probe.item());
    items.add(guard("auth", s -> probe.dependent("auth", c -> ServerChecks.auth(s, c))));
    items.add(guard("identity", s -> probe.dependent("identity", ServerChecks::identity)));
    items.add(
        guard(
            DoctorReport.COMPAT,
            s ->
                probe.dependent(
                    DoctorReport.COMPAT,
                    c -> ServerChecks.compat(s, c, options.allowUnsupported()))));
    items.add(
        guard(
            "capabilities",
            s -> probe.dependent("capabilities", c -> ServerChecks.capabilities(s, c))));

    Optional<TomcatLayout> layout = LocalChecks.layout(services);
    items.add(guard("layout", s -> LocalChecks.layout(s, layout)));
    items.add(guard("service", LocalChecks::service));
    items.add(guard("permissions", s -> LocalChecks.permissions(s, layout)));
    items.add(guard("disk", LocalChecks::disk));
    items.add(
        guard("keystore", s -> probe.dependent("keystore", c -> ServerChecks.keystore(s, c))));
    items.add(guard("vendor", s -> LocalChecks.vendor(s, layout)));
    items.add(
        guard(
            "vendor-java",
            s -> probe.dependent("vendor-java", c -> ServerChecks.vendorJava(s, c))));
    items.add(guard("database", DatabaseCheck::check));
    items.add(guard("state", LocalChecks::state));
    items.add(guard("runs", LocalChecks::runs));
    items.add(guard("lock", LocalChecks::lock));
    items.add(guard("snapshots", LocalChecks::snapshots));
    items.add(guard("network", LocalChecks::network));
    items.add(guard("elevated", LocalChecks::elevated));
    return DoctorReport.of(items);
  }

  private ReportItem guard(String name, DoctorCheck check) {
    try {
      return check.check(services);
    } catch (RuntimeException e) {
      LOG.debug("doctor check {} threw", name, e);
      return ReportItem.fail(
          name,
          "check threw " + e.getClass().getSimpleName() + ": " + e.getMessage(),
          "see the jrsctl log for the stack trace");
    }
  }

  private void audit(String action, String detail) {
    try {
      services.stateStore().get().audit("operator", action, detail);
    } catch (RuntimeException e) {
      LOG.warn("cannot write audit row for {}: {}", action, e.getMessage());
    }
  }
}
