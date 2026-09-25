package com.jaspersoft.jrsctl.core.config;

import com.jaspersoft.jrsctl.core.JrsctlHome;
import com.jaspersoft.jrsctl.core.platform.DefaultHome;
import com.jaspersoft.jrsctl.core.platform.HomeRedirect;
import com.jaspersoft.jrsctl.core.platform.Platform;
import com.jaspersoft.jrsctl.core.platform.UserPaths;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Locates {@code $JRSCTL_HOME} (spec §5.1): the {@code JRSCTL_HOME} environment variable wins,
 * otherwise the platform default (ProgramData or /var/lib, falling back to ~/.jrsctl). Invariants:
 * the returned home is an absolute, normalised path so every derived file path is unambiguous; the
 * per-user fallback is refused, not taken, when the system home exists and this user simply cannot
 * write to it. That case is an operator running unelevated against an installation another operator
 * already manages, and taking the fallback would give them a second {@code state.db} and a second
 * {@code runs.lock}, so the exclusive run lock of spec §5.5 would no longer be exclusive.
 */
public final class JrsctlHomeResolver {

  public static final String ENV_VAR = "JRSCTL_HOME";

  private JrsctlHomeResolver() {}

  public static JrsctlHome resolve(Map<String, String> env, Platform platform) {
    Objects.requireNonNull(env, "env");
    return resolve(env, platform, DefaultHome.choose(env));
  }

  /**
   * The resolution above against an already-made {@link DefaultHome.Choice}, so the refusal can be
   * exercised without an unwritable system directory.
   */
  static JrsctlHome resolve(Map<String, String> env, Platform platform, DefaultHome.Choice choice) {
    Objects.requireNonNull(env, "env");
    Objects.requireNonNull(platform, "platform");
    Objects.requireNonNull(choice, "choice");
    String fromEnv = env.get(ENV_VAR);
    if (fromEnv != null && !fromEnv.isBlank()) {
      return new JrsctlHome(HomeRedirect.follow(Path.of(UserPaths.expand(fromEnv.strip(), env))));
    }
    Path root = platform.defaultHome().toAbsolutePath().normalize();
    boolean fallback = choice.systemHomeUnwritable() && root.equals(normalise(choice.home()));
    if (fallback) {
      // ADR-0041, review of #169: the system home this user cannot write is the shared one. Only a
      // redirect written there (by someone who could) is followed; one in the per-user fallback
      // would let any operator give themselves a second journal and run lock.
      Optional<Path> shared = HomeRedirect.target(normalise(choice.systemHome()));
      if (shared.isPresent()) {
        return new JrsctlHome(shared.get());
      }
    } else {
      // ADR-0041: a default home that has been pointed elsewhere is followed
      Optional<Path> redirected = HomeRedirect.target(root);
      if (redirected.isPresent()) {
        return new JrsctlHome(redirected.get());
      }
    }
    if (fallback) {
      throw new ConfigException(
          choice.systemHome()
              + " exists but this user cannot write to it, so jrsctl would keep its state and run"
              + " lock under "
              + root
              + " instead; two operators could then change one installation at the same time",
          "run elevated (Windows) or as root (Linux), or point --home / JRSCTL_HOME at a directory"
              + " you own and that nobody else is using for this installation");
    }
    return new JrsctlHome(root);
  }

  private static Path normalise(Path path) {
    return path.toAbsolutePath().normalize();
  }
}
