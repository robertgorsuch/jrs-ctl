package com.jaspersoft.jrsctl.jrs.keystore;

import com.jaspersoft.jrsctl.core.config.Config;
import com.jaspersoft.jrsctl.core.platform.Platform;
import com.jaspersoft.jrsctl.jrs.api.KeystoreInfo;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Finds the server keystore ({@code .jrsks}) and its properties ({@code .jrsksp}) in the home
 * directory of {@code server.runAsUser} (spec §9.3). Invariants: the home is resolved by the OS
 * convention of the {@link Platform} (Windows: {@code %SystemDrive%\Users\<user>}; Linux: the home
 * field of {@code /etc/passwd}, falling back to {@code /home/<user>}); when {@code runAsUser} is
 * unset the current user's home is inspected and the returned {@code reason} says so; the
 * fingerprint is the streaming SHA-256 of {@code .jrsks}; the keystore is never opened or
 * decrypted, and no keystore password is read.
 */
public final class KeystoreInspector {

  public static final String KEYSTORE_FILE = ".jrsks";
  public static final String PROPERTIES_FILE = ".jrsksp";

  /**
   * Roots used to resolve home directories; {@link #system()} describes the real machine, tests
   * point every root at a temporary tree.
   */
  public record Homes(
      Path windowsUsersRoot, Path passwdFile, Path linuxHomeRoot, Path currentUserHome) {

    public Homes {
      Objects.requireNonNull(windowsUsersRoot, "windowsUsersRoot");
      Objects.requireNonNull(passwdFile, "passwdFile");
      Objects.requireNonNull(linuxHomeRoot, "linuxHomeRoot");
      Objects.requireNonNull(currentUserHome, "currentUserHome");
    }

    public static Homes system() {
      String drive = System.getenv("SystemDrive");
      String usersRoot = (drive == null || drive.isBlank() ? "C:" : drive.strip()) + "\\Users";
      return new Homes(
          Path.of(usersRoot),
          Path.of("/etc/passwd"),
          Path.of("/home"),
          Path.of(System.getProperty("user.home", ".")));
    }
  }

  private final Platform platform;
  private final Config config;
  private final Homes homes;

  public KeystoreInspector(Platform platform, Config config) {
    this(platform, config, Homes.system());
  }

  public KeystoreInspector(Platform platform, Config config, Homes homes) {
    this.platform = Objects.requireNonNull(platform, "platform");
    this.config = Objects.requireNonNull(config, "config");
    this.homes = Objects.requireNonNull(homes, "homes");
  }

  public KeystoreInfo inspect() {
    Optional<String> user = config.server().runAsUser();
    Path home;
    String note;
    if (user.isPresent()) {
      Optional<Path> resolved = homeOf(user.get());
      if (resolved.isEmpty()) {
        return KeystoreInfo.absent(
            "home directory of server.runAsUser '"
                + user.get()
                + "' not found; check the account exists on this host or correct"
                + " server.runAsUser");
      }
      home = resolved.get();
      note = "resolved from server.runAsUser '" + user.get() + "' to " + home;
    } else {
      home = homes.currentUserHome();
      note = "server.runAsUser is not set; inspected the current user's home " + home;
    }
    Path keystore = home.resolve(KEYSTORE_FILE);
    Path properties = home.resolve(PROPERTIES_FILE);
    if (!Files.isRegularFile(keystore)) {
      return KeystoreInfo.absent(keystore + " not found (" + note + ")");
    }
    String fingerprint;
    try {
      fingerprint = platform.files().sha256(keystore);
    } catch (IOException e) {
      return KeystoreInfo.absent(
          "cannot read " + keystore + ": " + e.getMessage() + " (" + note + ")");
    }
    return new KeystoreInfo(
        true,
        Optional.of(keystore),
        Files.isRegularFile(properties) ? Optional.of(properties) : Optional.empty(),
        Optional.of(fingerprint),
        Optional.of(note));
  }

  /**
   * Home directory of an OS account by platform convention; a {@code DOMAIN\\user} or {@code
   * user@domain} spelling is reduced to the bare account name. Empty when no directory exists.
   */
  public Optional<Path> homeOf(String user) {
    String account = bareAccount(user);
    return switch (platform.os()) {
      case WINDOWS -> existingDir(homes.windowsUsersRoot().resolve(account));
      case LINUX ->
          passwdHome(account).or(() -> existingDir(homes.linuxHomeRoot().resolve(account)));
    };
  }

  private Optional<Path> passwdHome(String account) {
    if (!Files.isRegularFile(homes.passwdFile())) {
      return Optional.empty();
    }
    try (Stream<String> lines = Files.lines(homes.passwdFile(), StandardCharsets.UTF_8)) {
      return lines
          .map(l -> l.split(":", -1))
          .filter(f -> f.length >= 6 && f[0].equals(account) && !f[5].isBlank())
          .map(f -> Path.of(f[5]))
          .findFirst()
          .flatMap(KeystoreInspector::existingDir);
    } catch (IOException | java.io.UncheckedIOException e) {
      return Optional.empty();
    }
  }

  private static Optional<Path> existingDir(Path p) {
    return Files.isDirectory(p) ? Optional.of(p) : Optional.empty();
  }

  static String bareAccount(String user) {
    String u = user.strip();
    int backslash = u.lastIndexOf('\\');
    if (backslash >= 0) {
      u = u.substring(backslash + 1);
    }
    int at = u.indexOf('@');
    if (at > 0) {
      u = u.substring(0, at);
    }
    return u;
  }
}
