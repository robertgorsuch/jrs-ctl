package com.jaspersoft.jrsctl.core.platform;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;

/** The {@code service:} block of config.yaml (spec §5.1). */
public record ServiceConfig(
    Kind kind, Optional<String> name, Optional<Path> scriptPath, Duration stopTimeout) {

  public enum Kind {
    WINDOWS_SERVICE,
    SYSTEMD,
    CTLSCRIPT,
    CATALINA,
    MANUAL
  }
}
