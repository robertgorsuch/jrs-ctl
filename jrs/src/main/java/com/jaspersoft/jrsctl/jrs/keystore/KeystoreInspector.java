package com.jaspersoft.jrsctl.jrs.keystore;

import com.jaspersoft.jrsctl.core.config.Config;
import com.jaspersoft.jrsctl.core.platform.Platform;
import com.jaspersoft.jrsctl.jrs.api.KeystoreInfo;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
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
      Path windowsUsersRoot,
      Path passwdFile,
      Path linuxHomeRoot,
      Path currentUserHome,
      Path windowsSystemRoot) {
    public Homes {
      Objects.requireNonNull(windowsUsersRoot, "windowsUsersRoot");
      Objects.requireNonNull(passwdFile, "passwdFile");
      Objects.requireNonNull(linuxHomeRoot, "linuxHomeRoot");
      Objects.requireNonNull(currentUserHome, "currentUserHome");
      Objects.requireNonNull(windowsSystemRoot, "windowsSystemRoot");
    }

    /** The Windows directory is taken to sit beside the Users directory. */
    public Homes(Path windowsUsersRoot, Path passwdFile, Path linuxHomeRoot, Path currentUserHome) {
      this(
          windowsUsersRoot,
          passwdFile,
          linuxHomeRoot,
          currentUserHome,
          windowsUsersRoot.toAbsolutePath().resolveSibling("Windows"));
    }

    public static Homes system() {
      String drive = System.getenv("SystemDrive");
      String usersRoot = (drive == null || drive.isBlank() ? "C:" : drive.strip()) + "\\Users";
      String systemRoot = System.getenv("SystemRoot");
      return new Homes(
          Path.of(usersRoot),
          Path.of("/etc/passwd"),
          Path.of("/home"),
          Path.of(System.getProperty("user.home", ".")),
          Path.of(systemRoot == null || systemRoot.isBlank() ? "C:\\Windows" : systemRoot));
    }
  }

  /** {@code buildomatic/keystore.init.properties}: where the installer put the keystore. */
  public static final String INIT_PROPERTIES = "keystore.init.properties";

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
    Optional<KeystoreInfo> fromInstall = fromInitProperties();
    if (fromInstall.isPresent()) {
      return fromInstall.get();
    }
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
   * Review finding 2.5: the installer records the keystore location in {@code
   * buildomatic/keystore.init.properties} ({@code ks}, {@code ksp}), which beats any guess from the
   * account name; the real 10.0.0 install on the development machine points both at the installing
   * user's profile. Empty when {@code server.installDir} is unset or the file names no location.
   */
  private Optional<KeystoreInfo> fromInitProperties() {
    Optional<Path> installDir = config.server().installDir();
    if (installDir.isEmpty()) {
      return Optional.empty();
    }
    Path file = installDir.get().resolve("buildomatic").resolve(INIT_PROPERTIES);
    if (!Files.isRegularFile(file)) {
      return Optional.empty();
    }
    Properties props = new Properties();
    try (var in = Files.newBufferedReader(file, StandardCharsets.ISO_8859_1)) {
      props.load(in);
    } catch (IOException e) {
      return Optional.empty();
    }
    String ks = props.getProperty("ks", "").strip();
    if (ks.isEmpty()) {
      return Optional.empty();
    }
    String ksp = props.getProperty("ksp", "").strip();
    Path keystore = Path.of(ks).resolve(KEYSTORE_FILE);
    Path properties = Path.of(ksp.isEmpty() ? ks : ksp).resolve(PROPERTIES_FILE);
    String note =
        "resolved from " + file + " (ks=" + ks + (ksp.isEmpty() ? "" : ", ksp=" + ksp) + ")";
    if (!Files.isRegularFile(keystore)) {
      return Optional.of(KeystoreInfo.absent(keystore + " not found (" + note + ")"));
    }
    try {
      return Optional.of(
          new KeystoreInfo(
              true,
              Optional.of(keystore),
              Files.isRegularFile(properties) ? Optional.of(properties) : Optional.empty(),
              Optional.of(platform.files().sha256(keystore)),
              Optional.of(note)));
    } catch (IOException e) {
      return Optional.of(
          KeystoreInfo.absent(
              "cannot read " + keystore + ": " + e.getMessage() + " (" + note + ")"));
    }
  }

  /**
   * Home directory of an OS account by platform convention; a {@code DOMAIN\\user} or {@code
   * user@domain} spelling is reduced to the bare account name. The Windows service accounts
   * (SYSTEM, LocalService, NetworkService) have no directory under Users; their profiles live under
   * the Windows directory (review finding 2.5). Empty when no directory exists.
   */
  public Optional<Path> homeOf(String user) {
    String account = bareAccount(user);
    return switch (platform.os()) {
      case WINDOWS ->
          serviceProfile(account)
              .or(() -> Optional.of(homes.windowsUsersRoot().resolve(account)))
              .flatMap(KeystoreInspector::existingDir);
      case LINUX ->
          passwdHome(account).or(() -> existingDir(homes.linuxHomeRoot().resolve(account)));
    };
  }

  private Optional<Path> serviceProfile(String account) {
    String a = account.toLowerCase(Locale.ROOT).replace(" ", "");
    Path windows = homes.windowsSystemRoot();
    return switch (a) {
      case "system", "localsystem" ->
          Optional.of(windows.resolve("System32").resolve("config").resolve("systemprofile"));
      case "localservice" ->
          Optional.of(windows.resolve("ServiceProfiles").resolve("LocalService"));
      case "networkservice" ->
          Optional.of(windows.resolve("ServiceProfiles").resolve("NetworkService"));
      default -> Optional.empty();
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
