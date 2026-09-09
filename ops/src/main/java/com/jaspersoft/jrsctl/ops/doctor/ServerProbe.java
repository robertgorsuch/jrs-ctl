package com.jaspersoft.jrsctl.ops.doctor;

import com.jaspersoft.jrsctl.core.config.ConfigException;
import com.jaspersoft.jrsctl.jrs.api.JrsAdapter;
import com.jaspersoft.jrsctl.jrs.api.JrsUnreachableException;
import com.jaspersoft.jrsctl.jrs.api.ServerIdentity;
import com.jaspersoft.jrsctl.ops.ReportItem;
import com.jaspersoft.jrsctl.ops.Services;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * The one connection attempt {@code doctor} makes, shared by every server-dependent check (spec
 * §12.1). Invariants: the adapter is asked for its identity exactly once; when that fails the
 * {@code server} item is a FAIL carrying the adapter's remediation and every dependent check
 * becomes a SKIP saying "server unreachable" rather than a second failure for the same cause.
 */
final class ServerProbe {

  static final String NAME = "server";
  static final String UNREACHABLE = "server unreachable";

  /** A live adapter with the identity it reported. */
  record Connected(JrsAdapter adapter, ServerIdentity identity) {}

  private final Optional<Connected> connected;
  private final ReportItem item;

  private ServerProbe(Optional<Connected> connected, ReportItem item) {
    this.connected = connected;
    this.item = item;
  }

  static ServerProbe connect(Services services) {
    Objects.requireNonNull(services, "services");
    try {
      JrsAdapter adapter = services.adapter().get();
      ServerIdentity identity = adapter.identity();
      return new ServerProbe(
          Optional.of(new Connected(adapter, identity)),
          ReportItem.pass(NAME, "reachable at " + identity.baseUrl()));
    } catch (JrsUnreachableException e) {
      return new ServerProbe(
          Optional.empty(), ReportItem.fail(NAME, e.getMessage(), e.remediation()));
    } catch (ConfigException e) {
      return new ServerProbe(
          Optional.empty(), ReportItem.fail(NAME, firstLine(e.getMessage()), e.remediation()));
    } catch (RuntimeException e) {
      return new ServerProbe(
          Optional.empty(),
          ReportItem.fail(
              NAME,
              "unexpected " + e.getClass().getSimpleName() + ": " + e.getMessage(),
              "check server.baseUrl and the jrsctl log"));
    }
  }

  ReportItem item() {
    return item;
  }

  boolean reachable() {
    return connected.isPresent();
  }

  Optional<Connected> connected() {
    return connected;
  }

  /** Runs {@code check} against the connection, or returns a SKIP when there is none. */
  ReportItem dependent(String name, Function<Connected, ReportItem> check) {
    if (connected.isEmpty()) {
      return ReportItem.skip(name, UNREACHABLE, "fix the server check first");
    }
    return check.apply(connected.get());
  }

  private static String firstLine(String text) {
    int nl = text.indexOf('\n');
    return nl < 0 ? text : text.substring(0, nl);
  }
}
