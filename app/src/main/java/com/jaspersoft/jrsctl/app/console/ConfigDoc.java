package com.jaspersoft.jrsctl.app.console;

import java.util.List;

record ConfigDoc(
    ServerConfig server, PlatformHealth platform, List<KeyEntry> keys, String redactedYaml) {

  record ServerConfig(
      String baseUrl,
      String webappName,
      String installDir,
      String tomcatDir,
      String serviceKind,
      String authMode,
      String username,
      boolean secretsConfigured) {}

  record PlatformHealth(
      String os, String arch, String jvmVersion, boolean sqliteHealthy, boolean writeAccess) {}

  record KeyEntry(String name, String fingerprint, boolean bundled, String algorithm) {}
}
