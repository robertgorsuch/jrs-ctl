package com.jaspersoft.jrsctl.ops.repository;

import com.jaspersoft.jrsctl.core.JrsctlHome;
import com.jaspersoft.jrsctl.core.config.Config;
import com.jaspersoft.jrsctl.core.platform.FileOps;
import com.jaspersoft.jrsctl.core.state.StateStore;
import com.jaspersoft.jrsctl.jrs.vendor.BuildomaticLocator;
import com.jaspersoft.jrsctl.jrs.vendor.VendorTools;
import com.jaspersoft.jrsctl.ops.Services;
import com.jaspersoft.jrsctl.ops.db.JdbcConnector;
import java.time.Clock;
import java.util.Objects;
import java.util.function.Function;

/**
 * Collaborators every load step needs, captured once at planning time (steps are rebuilt from the
 * stored arguments on recovery, so holding them here is safe). Invariant: all fields are non-null;
 * {@code vendorTools} is a function so tests can substitute a shorter timeout or another process
 * runner, and {@code jdbc} is the connector the catalogue is asked through.
 */
record LoadRuntime(
    Services services, Function<Services, VendorTools> vendorTools, JdbcConnector jdbc) {

  LoadRuntime {
    Objects.requireNonNull(services, "services");
    Objects.requireNonNull(vendorTools, "vendorTools");
    Objects.requireNonNull(jdbc, "jdbc");
  }

  StateStore store() {
    return services.stateStore().get();
  }

  FileOps files() {
    return services.platform().files();
  }

  Config config() {
    return services.config();
  }

  JrsctlHome home() {
    return services.home();
  }

  Clock clock() {
    return services.clock();
  }

  VendorTools tools() {
    return vendorTools.apply(services);
  }

  BuildomaticLocator locator() {
    return new BuildomaticLocator(services.platform());
  }
}
