package com.jaspersoft.jrsctl.ops.upgrade;

import com.jaspersoft.jrsctl.core.engine.Sleeper;
import com.jaspersoft.jrsctl.core.platform.ServiceController;
import com.jaspersoft.jrsctl.jrs.api.ServerIdentity;
import com.jaspersoft.jrsctl.jrs.service.ServiceRuntime;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * The upgrade's {@link ServiceRuntime} with its service controller bound to one Tomcat directory
 * rather than to {@code server.installDir} (issue #340, ADR-0062). In a run with {@code
 * --tomcat-dir} the upgraded server starts in the new Tomcat while the configuration still names
 * the old one, so the steps that start, wait for or stop the <em>upgraded</em> server use this
 * runtime: the {@code manual} controller's prompt then names the new Tomcat and its process scan
 * judges that Tomcat, not the old one. Invariants: everything but {@link #controller()} delegates
 * to the wrapped runtime unchanged; the controller is created on every call, as {@link
 * ServiceRuntime} requires; the directory is absolute and normalized.
 */
record HostTomcatServiceRuntime(UpgradeRuntime rt, Path tomcatDir) implements ServiceRuntime {

  HostTomcatServiceRuntime {
    Objects.requireNonNull(rt, "rt");
    tomcatDir = Objects.requireNonNull(tomcatDir, "tomcatDir").toAbsolutePath().normalize();
  }

  @Override
  public ServiceController controller() {
    return rt.services()
        .platform()
        .withInstallDir(tomcatDir)
        .services(rt.config().toServiceConfig());
  }

  @Override
  public Optional<ServiceController> databaseController() {
    return rt.databaseController();
  }

  @Override
  public Duration serviceTimeout() {
    return rt.serviceTimeout();
  }

  @Override
  public Clock clock() {
    return rt.clock();
  }

  @Override
  public Sleeper sleeper() {
    return rt.sleeper();
  }

  @Override
  public ServerIdentity refreshIdentity() {
    return rt.refreshIdentity();
  }
}
