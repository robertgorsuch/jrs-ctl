package com.jaspersoft.jrsctl.core.config;

import com.jaspersoft.jrsctl.core.JrsctlHome;
import com.jaspersoft.jrsctl.core.platform.Platform;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

/**
 * Locates {@code $JRSCTL_HOME} (spec §5.1): the {@code JRSCTL_HOME} environment variable wins,
 * otherwise the platform default (ProgramData or /var/lib, falling back to ~/.jrsctl). Invariant:
 * the returned home is an absolute, normalised path so every derived file path is unambiguous.
 */
public final class JrsctlHomeResolver {

  public static final String ENV_VAR = "JRSCTL_HOME";

  private JrsctlHomeResolver() {}

  public static JrsctlHome resolve(Map<String, String> env, Platform platform) {
    Objects.requireNonNull(env, "env");
    Objects.requireNonNull(platform, "platform");
    String fromEnv = env.get(ENV_VAR);
    Path root =
        fromEnv == null || fromEnv.isBlank() ? platform.defaultHome() : Path.of(fromEnv.strip());
    return new JrsctlHome(root.toAbsolutePath().normalize());
  }
}
