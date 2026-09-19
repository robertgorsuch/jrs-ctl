package com.jaspersoft.jrsctl.ops.hotfix;

import com.jaspersoft.jrsctl.ops.JsConfig;
import java.util.Optional;

/**
 * The telemetry warning on a hotfix apply plan (review §3.3, issue #113): the usage-telemetry
 * programme reaches a server through a cumulative hotfix, so a package that lays down {@code
 * WEB-INF/js.config.properties} can switch {@code heartbeat.enabled} on, and a server that already
 * has it on keeps calling out after the apply. Invariants: read-only; a package that does not touch
 * the file and a server whose switch is off or absent produce no warning, so the plan summary
 * carries the note only when the operator has something to check.
 */
final class TelemetryNotice {

  private TelemetryNotice() {}

  static Optional<String> warning(Manifest manifest, HotfixPaths paths, String webappName) {
    boolean laysDown =
        manifest.files().stream()
            .anyMatch(f -> f.path().replace('\\', '/').endsWith(JsConfig.RELATIVE));
    if (laysDown) {
      return Optional.of(
          "this package lays down "
              + JsConfig.RELATIVE
              + ", which carries "
              + JsConfig.HEARTBEAT
              + " (usage telemetry upload to the vendor); run jrsctl doctor after the apply and set"
              + " it to false if this host must not call out (administrator guide)");
    }
    return JsConfig.read(paths.tomcatDir().resolve("webapps").resolve(webappName))
        .flatMap(cfg -> cfg.flag(JsConfig.HEARTBEAT).filter(on -> on).map(on -> cfg))
        .map(
            cfg ->
                JsConfig.HEARTBEAT
                    + "=true in "
                    + cfg.file()
                    + ": the server uploads usage telemetry to the vendor; set it to false if this"
                    + " host must not call out (jrsctl doctor reports it as the telemetry item)");
  }
}
