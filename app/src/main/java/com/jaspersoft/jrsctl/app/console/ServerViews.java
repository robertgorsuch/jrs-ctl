package com.jaspersoft.jrsctl.app.console;

import com.jaspersoft.jrsctl.core.config.Config;
import com.jaspersoft.jrsctl.core.platform.ServiceController;
import com.jaspersoft.jrsctl.jrs.api.JrsAdapter;
import com.jaspersoft.jrsctl.jrs.api.KeystoreInfo;
import com.jaspersoft.jrsctl.jrs.api.ServerIdentity;
import com.jaspersoft.jrsctl.ops.Services;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.IntSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * What the console shows about the JasperReports Server itself: identity, reachability, the
 * configured service and the layout behind it (spec §13.1).
 *
 * <p>One area of the console per file (roadmap item 17). The shared state-store access and the
 * dashboard live in {@link ConsoleViews}, which owns this object and is the only caller.
 */
final class ServerViews {

  private static final Logger LOG = LoggerFactory.getLogger(ServerViews.class);

  private final Services services;

  ServerViews(
      Services services, RunManager runs, DoctorCache doctor, String bind, IntSupplier port) {
    this.services = services;
  }

  Map<String, Object> server() {
    Config config = services.config();
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("product", "JasperReports Server");
    Optional<JrsAdapter> adapter = Optional.empty();
    Optional<ServerIdentity> identity = Optional.empty();
    try {
      adapter = Optional.of(services.adapter().get());
      identity = Optional.of(adapter.get().identity());
    } catch (RuntimeException e) {
      LOG.debug("server not reachable for /api/server: {}", e.getMessage());
    }
    m.put("version", identity.map(ServerIdentity::version).orElse(""));
    m.put("edition", identity.map(i -> i.edition().name()).orElse(""));
    m.put(
        "tenancy",
        identity
            .map(
                i -> i.tenancy() == ServerIdentity.Tenancy.MULTI ? "multi-tenant" : "single-tenant")
            .orElse(""));
    Map<String, Object> database = new LinkedHashMap<>();
    database.put("vendor", config.database().type().map(Config.DatabaseType::yamlValue).orElse(""));
    database.put("version", "");
    m.put("database", database);
    m.put(
        "baseUrl",
        identity
            .map(i -> i.baseUrl().toString())
            .or(() -> config.server().baseUrl().map(Object::toString))
            .orElse(""));
    m.put("installDir", config.server().installDir().map(Path::toString).orElse(""));
    m.put("service", service(config));
    Map<String, Object> keystore = new LinkedHashMap<>();
    Optional<KeystoreInfo> info = Optional.empty();
    if (adapter.isPresent()) {
      try {
        info = Optional.of(adapter.get().keystore());
      } catch (RuntimeException e) {
        LOG.debug("keystore not inspectable: {}", e.getMessage());
      }
    }
    keystore.put("present", info.map(KeystoreInfo::present).orElse(false));
    keystore.put("user", config.server().runAsUser().orElse(""));
    m.put("keystore", keystore);
    m.put("networkMode", config.network().mode().yamlValue());
    m.put("reachable", identity.isPresent());
    return m;
  }

  Map<String, Object> service(Config config) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("kind", config.service().kind().map(Config.Service::kindToYaml).orElse(""));
    m.put("name", config.service().name().orElse(""));
    String state = "";
    if (config.service().kind().isPresent()) {
      try {
        ServiceController controller = services.platform().services(config.toServiceConfig());
        state = controller.state().name().toLowerCase(Locale.ROOT);
      } catch (RuntimeException e) {
        LOG.debug("service state unavailable: {}", e.getMessage());
      }
    }
    m.put("state", state);
    return m;
  }
}
