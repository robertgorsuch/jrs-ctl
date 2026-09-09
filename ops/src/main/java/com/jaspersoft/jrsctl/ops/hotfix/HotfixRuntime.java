package com.jaspersoft.jrsctl.ops.hotfix;

import com.jaspersoft.jrsctl.core.JrsctlHome;
import com.jaspersoft.jrsctl.core.config.Config;
import com.jaspersoft.jrsctl.core.engine.Sleeper;
import com.jaspersoft.jrsctl.core.keys.KeyRing;
import com.jaspersoft.jrsctl.core.platform.FileOps;
import com.jaspersoft.jrsctl.core.platform.ServiceController;
import com.jaspersoft.jrsctl.core.snapshot.SnapshotStore;
import com.jaspersoft.jrsctl.core.state.StateStore;
import com.jaspersoft.jrsctl.jrs.api.ServerIdentity;
import com.jaspersoft.jrsctl.ops.Services;
import com.jaspersoft.jrsctl.ops.db.JdbcConnector;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;

/**
 * Everything a hotfix step needs at run time, captured once per plan. Invariants: the state store
 * and adapter are reached through the lazy suppliers of {@link Services}, so nothing is opened
 * until a step needs it; the service controller is created from the configuration on every call,
 * never cached, so a stopped-then-started service is always re-queried.
 */
record HotfixRuntime(
    Services services,
    SnapshotStore snapshots,
    JdbcConnector jdbc,
    KeyRing keys,
    HttpProbe http,
    Sleeper sleeper) {

  HotfixRuntime {
    Objects.requireNonNull(services, "services");
    Objects.requireNonNull(snapshots, "snapshots");
    Objects.requireNonNull(jdbc, "jdbc");
    Objects.requireNonNull(keys, "keys");
    Objects.requireNonNull(http, "http");
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

  BundleVerifier verifier() {
    return new BundleVerifier(keys, files());
  }
}
