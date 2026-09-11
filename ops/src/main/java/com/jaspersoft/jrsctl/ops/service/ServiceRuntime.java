package com.jaspersoft.jrsctl.ops.service;

import com.jaspersoft.jrsctl.core.engine.Sleeper;
import com.jaspersoft.jrsctl.core.platform.ServiceController;
import com.jaspersoft.jrsctl.jrs.api.ServerIdentity;
import java.time.Clock;
import java.time.Duration;

/**
 * What the shared service steps need from an operation's runtime. Invariants: {@link #controller()}
 * is created from the configuration on every call and never cached, so a stopped-then-started
 * service is always re-queried; {@link #refreshIdentity()} contacts the server rather than
 * returning a memoised identity, which is what makes wait-for-server a real probe.
 */
public interface ServiceRuntime {

  ServiceController controller();

  /** The configured stop timeout ({@code service.stopTimeoutSeconds}). */
  Duration serviceTimeout();

  Clock clock();

  Sleeper sleeper();

  /** {@code serverInfo} from the server, never from a cache. */
  ServerIdentity refreshIdentity();
}
