package com.jaspersoft.jrsctl.app.console;

import com.jaspersoft.jrsctl.core.config.Config;
import com.jaspersoft.jrsctl.core.crypto.Ed25519;
import com.jaspersoft.jrsctl.core.keys.KeyRing;
import com.jaspersoft.jrsctl.core.selfcheck.SelfCheck;
import com.jaspersoft.jrsctl.ops.Services;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.IntSupplier;

final class ConfigViews {

  private final Services services;

  ConfigViews(
      Services services, RunManager runs, DoctorCache doctor, String bind, IntSupplier port) {
    this.services = Objects.requireNonNull(services, "services");
  }

  ConfigDoc config() {
    Config.Server serverConfig = services.config().server();
    Config.Service serviceConfig = services.config().service();
    ConfigDoc.ServerConfig server =
        new ConfigDoc.ServerConfig(
            serverConfig.baseUrl().map(Object::toString).orElse(""),
            serverConfig.webappName().map(Config.WebappName::yamlValue).orElse(""),
            serverConfig.installDir().map(Object::toString).orElse(""),
            serverConfig.tomcatDir().map(Object::toString).orElse(""),
            serviceConfig.kind().map(Config.Service::kindToYaml).orElse(""),
            serverConfig.auth().mode().yamlValue(),
            serverConfig.auth().username().orElse(""),
            serverConfig.auth().passwordRef().isPresent());

    boolean sqliteHealthy = false;
    try {
      sqliteHealthy = services.stateStore().get() != null;
    } catch (RuntimeException e) {
      // state store may be unconfigured or unreachable
    }

    ConfigDoc.PlatformHealth platform =
        new ConfigDoc.PlatformHealth(
            System.getProperty("os.name"),
            System.getProperty("os.arch"),
            System.getProperty("java.version"),
            sqliteHealthy,
            Files.isWritable(services.home().root()));

    List<ConfigDoc.KeyEntry> keys = new ArrayList<>();
    for (KeyRing.TrustedKey k : new KeyRing(services.home()).list()) {
      keys.add(new ConfigDoc.KeyEntry(k.name(), k.fingerprint(), k.bundled(), "Ed25519"));
    }

    String yaml = "";
    try {
      if (Files.exists(services.home().configFile())) {
        yaml = services.redactor().redact(Files.readString(services.home().configFile()));
      }
    } catch (IOException e) {
      yaml = "# cannot read config.yaml: " + e.getMessage();
    }

    return new ConfigDoc(server, platform, keys, yaml);
  }

  SelfCheck.Report selfcheck() {
    return new SelfCheck().run();
  }

  List<Map<String, Object>> keys() {
    List<KeyRing.TrustedKey> keys = new KeyRing(services.home()).list();
    List<Map<String, Object>> rows = new ArrayList<>();
    for (KeyRing.TrustedKey k : keys) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("name", k.name());
      row.put("fingerprint", k.fingerprint());
      row.put("bundled", k.bundled());
      row.put("publicKey", Ed25519.encodePublic(k.key()));
      rows.add(row);
    }
    return rows;
  }
}
