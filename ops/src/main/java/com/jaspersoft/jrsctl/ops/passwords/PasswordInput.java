package com.jaspersoft.jrsctl.ops.passwords;

import com.jaspersoft.jrsctl.core.platform.TomcatLayout;
import com.jaspersoft.jrsctl.jrs.vendor.Buildomatic;
import com.jaspersoft.jrsctl.ops.passwords.PasswordOperations.MigrateOptions;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;

/**
 * Everything the password steps read, resolved once while planning: the options, the installed
 * buildomatic, the Tomcat layout whose webapp the utility reads its settings from, and the server
 * version those settings exist for. Invariant: immutable; {@link #storageConfig()} is the file the
 * vendor's utility reads, {@code WEB-INF/js.password-storage-config.properties} of the deployed
 * webapp (installation guide 10.1 p.196).
 */
record PasswordInput(
    MigrateOptions options, Buildomatic buildomatic, TomcatLayout layout, String serverVersion) {

  static final String STORAGE_CONFIG = "js.password-storage-config.properties";
  static final String STRATEGY_KEY = "password.strategy";
  static final String MODERN = "modern";

  PasswordInput {
    Objects.requireNonNull(options, "options");
    Objects.requireNonNull(buildomatic, "buildomatic");
    Objects.requireNonNull(layout, "layout");
    Objects.requireNonNull(serverVersion, "serverVersion");
  }

  Path storageConfig() {
    return storageConfig(layout);
  }

  static Path storageConfig(TomcatLayout layout) {
    return layout.webappDir().resolve("WEB-INF").resolve(STORAGE_CONFIG);
  }

  /** The {@code password.strategy} the deployed webapp is configured with, when the file says. */
  static Optional<String> strategy(Path storageConfig) {
    if (!Files.isRegularFile(storageConfig)) {
      return Optional.empty();
    }
    Properties properties = new Properties();
    try (InputStream in = Files.newInputStream(storageConfig)) {
      properties.load(in);
    } catch (IOException | IllegalArgumentException e) {
      return Optional.empty();
    }
    return Optional.ofNullable(properties.getProperty(STRATEGY_KEY))
        .map(String::strip)
        .filter(s -> !s.isEmpty());
  }
}
