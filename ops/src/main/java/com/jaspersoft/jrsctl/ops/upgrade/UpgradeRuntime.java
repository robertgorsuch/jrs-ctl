package com.jaspersoft.jrsctl.ops.upgrade;

import com.jaspersoft.jrsctl.core.JrsctlHome;
import com.jaspersoft.jrsctl.core.config.Config;
import com.jaspersoft.jrsctl.core.engine.Sleeper;
import com.jaspersoft.jrsctl.core.platform.FileOps;
import com.jaspersoft.jrsctl.core.platform.ServiceController;
import com.jaspersoft.jrsctl.core.snapshot.SnapshotStore;
import com.jaspersoft.jrsctl.core.state.StateStore;
import com.jaspersoft.jrsctl.jrs.api.ServerIdentity;
import com.jaspersoft.jrsctl.jrs.vendor.BuildomaticLocator;
import com.jaspersoft.jrsctl.jrs.vendor.VendorTools;
import com.jaspersoft.jrsctl.ops.Services;
import com.jaspersoft.jrsctl.ops.hotfix.HotfixOperations;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.function.Function;

/**
 * Collaborators every upgrade step needs, captured once at planning time (steps are rebuilt from
 * the stored arguments on recovery, so holding them here is safe). Invariant: all fields are
 * non-null; {@code vendorTools} is a function so tests can substitute a fake process runner while
 * the default resolves the platform's runner lazily.
 */
record UpgradeRuntime(
    Services services,
    SnapshotStore snapshots,
    Function<Services, VendorTools> vendorTools,
    Function<Services, HotfixOperations> hotfixOperations,
    Sleeper sleeper) {

  UpgradeRuntime {
    Objects.requireNonNull(services, "services");
    Objects.requireNonNull(snapshots, "snapshots");
    Objects.requireNonNull(vendorTools, "vendorTools");
    Objects.requireNonNull(hotfixOperations, "hotfixOperations");
    Objects.requireNonNull(sleeper, "sleeper");
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

  HotfixOperations hotfixes() {
    return hotfixOperations.apply(services);
  }

  ServiceController controller() {
    return services.platform().services(config().toServiceConfig());
  }

  Duration serviceTimeout() {
    return Duration.ofSeconds(config().service().stopTimeoutSeconds());
  }

  ServerIdentity identity() {
    return services.adapter().get().identity();
  }

  String actor() {
    return System.getProperty("user.name", "unknown");
  }
}
