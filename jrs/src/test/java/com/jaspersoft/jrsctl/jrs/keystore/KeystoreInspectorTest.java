package com.jaspersoft.jrsctl.jrs.keystore;

import static org.assertj.core.api.Assertions.assertThat;

import com.jaspersoft.jrsctl.core.config.Config;
import com.jaspersoft.jrsctl.core.platform.Platform;
import com.jaspersoft.jrsctl.jrs.FakePlatform;
import com.jaspersoft.jrsctl.jrs.TestConfigs;
import com.jaspersoft.jrsctl.jrs.api.KeystoreInfo;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.AnnotatedElementContext;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.io.TempDirFactory;

class KeystoreInspectorTest {

  private static final URI BASE = URI.create("http://localhost:8080/jasperserver-pro");

  /**
   * Creates the fixture under this module's {@code target} directory rather than {@code
   * java.io.tmpdir}. A passwd home field is colon-separated, so the passwd test writes a drive-less
   * path that Java resolves against the current drive; on a Windows CI runner the temp directory
   * sits on a different drive from the checkout, so a fixture there is never found.
   */
  static final class CurrentDriveTempDir implements TempDirFactory {
    @Override
    public Path createTempDirectory(AnnotatedElementContext element, ExtensionContext context)
        throws IOException {
      Path base = Path.of("target", "tmp").toAbsolutePath();
      Files.createDirectories(base);
      return Files.createTempDirectory(base, "keystore-");
    }
  }

  @TempDir(factory = CurrentDriveTempDir.class)
  Path root;

  private KeystoreInspector.Homes homes(Path current) {
    return new KeystoreInspector.Homes(
        root.resolve("Users"), root.resolve("passwd"), root.resolve("home"), current);
  }

  private static Config config(Optional<String> runAsUser) {
    return TestConfigs.server(BASE, Config.AuthMode.BASIC, Config.NetworkMode.ISOLATED, runAsUser);
  }

  private static void keystoreIn(Path home, boolean withProperties) throws IOException {
    Files.createDirectories(home);
    Files.write(home.resolve(".jrsks"), "keystore-bytes".getBytes(StandardCharsets.US_ASCII));
    if (withProperties) {
      Files.writeString(home.resolve(".jrsksp"), "ks=x\nksp=y\n");
    }
  }

  @Test
  void should_find_keystore_in_windows_users_dir_when_run_as_user_set() throws IOException {
    Path home = root.resolve("Users").resolve("jasper");
    keystoreIn(home, true);
    KeystoreInspector inspector =
        new KeystoreInspector(
            new FakePlatform(Platform.OsFamily.WINDOWS),
            config(Optional.of("CORP\\jasper")),
            homes(root.resolve("me")));

    KeystoreInfo info = inspector.inspect();

    assertThat(info.present()).isTrue();
    assertThat(info.keystoreFile()).contains(home.resolve(".jrsks"));
    assertThat(info.propertiesFile()).contains(home.resolve(".jrsksp"));
    assertThat(info.fingerprint()).isPresent();
    assertThat(info.fingerprint().get()).hasSize(64).matches("[0-9a-f]+");
    assertThat(info.reason()).isPresent();
    assertThat(info.reason().get()).contains("CORP\\jasper");
  }

  /**
   * Review finding 2.5: the installation's {@code buildomatic/keystore.init.properties} names the
   * keystore location ({@code ks}, {@code ksp}) and wins over any account-based guess; the real
   * 10.0.0 install on this machine points both at the installing user's profile.
   */
  @Test
  void should_prefer_keystore_init_properties_when_the_install_names_the_location()
      throws IOException {
    Path install = root.resolve("install");
    Path ksDir = Files.createDirectories(root.resolve("ks-home"));
    Path kspDir = Files.createDirectories(root.resolve("ksp-home"));
    Files.write(ksDir.resolve(".jrsks"), "keystore-bytes".getBytes(StandardCharsets.US_ASCII));
    Files.writeString(kspDir.resolve(".jrsksp"), "ks=x\n");
    Files.createDirectories(install.resolve("buildomatic"));
    Files.writeString(
        install.resolve("buildomatic").resolve("keystore.init.properties"),
        "#Location of the keystore\nks="
            + ksDir.toString().replace("\\", "/")
            + "\nksp="
            + kspDir.toString().replace("\\", "/")
            + "\n");
    KeystoreInspector inspector =
        new KeystoreInspector(
            new FakePlatform(Platform.OsFamily.WINDOWS),
            withInstallDir(config(Optional.of("nobody-here")), install),
            homes(root.resolve("me")));

    KeystoreInfo info = inspector.inspect();

    assertThat(info.present()).isTrue();
    assertThat(info.keystoreFile()).contains(ksDir.resolve(".jrsks"));
    assertThat(info.propertiesFile()).contains(kspDir.resolve(".jrsksp"));
    assertThat(info.reason().orElse("")).contains("keystore.init.properties");
  }

  /** Windows service accounts have no directory under Users; their profiles live under Windows. */
  @Test
  void should_map_windows_service_accounts_to_their_profile_directories() throws IOException {
    Path system =
        root.resolve("Windows").resolve("System32").resolve("config").resolve("systemprofile");
    Path network = root.resolve("Windows").resolve("ServiceProfiles").resolve("NetworkService");
    keystoreIn(system, false);
    keystoreIn(network, false);
    KeystoreInspector inspector =
        new KeystoreInspector(
            new FakePlatform(Platform.OsFamily.WINDOWS),
            config(Optional.of("NT AUTHORITY\\SYSTEM")),
            homes(root.resolve("me")));

    assertThat(inspector.inspect().keystoreFile()).contains(system.resolve(".jrsks"));
    assertThat(inspector.homeOf("LocalSystem")).contains(system);
    assertThat(inspector.homeOf("NT AUTHORITY\\NetworkService")).contains(network);
    assertThat(inspector.homeOf("NetworkService")).contains(network);
    assertThat(inspector.homeOf("LocalService")).isEmpty();
  }

  private static Config withInstallDir(Config base, Path installDir) {
    Config.Server s = base.server();
    return new Config(
        new Config.Server(
            s.baseUrl(),
            s.webappName(),
            Optional.of(installDir),
            s.tomcatDir(),
            s.runAsUser(),
            s.auth()),
        base.service(),
        base.database(),
        base.vendor(),
        base.network(),
        base.console(),
        base.backups(),
        base.smoke());
  }

  @Test
  void should_resolve_home_from_passwd_when_linux_user_has_custom_home() throws IOException {
    Path home = root.resolve("srv").resolve("jrs-home");
    keystoreIn(home, false);
    // passwd fields are colon-separated, so a Windows drive letter cannot appear in the home field;
    // a drive-less absolute path resolves against the current drive on Windows and is a no-op on
    // Linux
    String homeField = home.toAbsolutePath().toString().replace('\\', '/');
    if (homeField.length() > 1 && homeField.charAt(1) == ':') {
      homeField = homeField.substring(2);
    }
    Files.writeString(
        root.resolve("passwd"),
        "root:x:0:0:root:/root:/bin/bash\n"
            + "jasperserver:x:1001:1001:JRS:"
            + homeField
            + ":/usr/sbin/nologin\n",
        StandardCharsets.UTF_8);
    KeystoreInspector inspector =
        new KeystoreInspector(
            new FakePlatform(Platform.OsFamily.LINUX),
            config(Optional.of("jasperserver")),
            homes(root.resolve("me")));

    KeystoreInfo info = inspector.inspect();

    assertThat(info.present()).isTrue();
    assertThat(info.keystoreFile().get().getFileName().toString()).isEqualTo(".jrsks");
    assertThat(info.propertiesFile()).isEmpty();
    assertThat(info.fingerprint()).isPresent();
  }

  @Test
  void should_fall_back_to_home_dir_when_passwd_has_no_entry() throws IOException {
    Path home = root.resolve("home").resolve("tomcat");
    keystoreIn(home, true);
    KeystoreInspector inspector =
        new KeystoreInspector(
            new FakePlatform(Platform.OsFamily.LINUX),
            config(Optional.of("tomcat")),
            homes(root.resolve("me")));

    assertThat(inspector.homeOf("tomcat")).contains(home);
    assertThat(inspector.inspect().present()).isTrue();
  }

  @Test
  void should_inspect_current_user_and_say_so_when_run_as_user_absent() throws IOException {
    Path me = root.resolve("me");
    keystoreIn(me, true);
    KeystoreInspector inspector =
        new KeystoreInspector(
            new FakePlatform(Platform.OsFamily.LINUX), config(Optional.empty()), homes(me));

    KeystoreInfo info = inspector.inspect();

    assertThat(info.present()).isTrue();
    assertThat(info.reason().orElseThrow()).contains("server.runAsUser is not set");
  }

  @Test
  void should_report_absent_when_home_has_no_keystore() throws IOException {
    Files.createDirectories(root.resolve("home").resolve("nobody"));
    KeystoreInspector inspector =
        new KeystoreInspector(
            new FakePlatform(Platform.OsFamily.LINUX),
            config(Optional.of("nobody")),
            homes(root.resolve("me")));

    KeystoreInfo info = inspector.inspect();

    assertThat(info.present()).isFalse();
    assertThat(info.fingerprint()).isEmpty();
    assertThat(info.reason().orElseThrow()).contains(".jrsks not found");
  }

  @Test
  void should_report_absent_when_user_home_does_not_exist() {
    KeystoreInspector inspector =
        new KeystoreInspector(
            new FakePlatform(Platform.OsFamily.WINDOWS),
            config(Optional.of("ghost")),
            homes(root.resolve("me")));

    KeystoreInfo info = inspector.inspect();

    assertThat(info.present()).isFalse();
    assertThat(info.reason().orElseThrow()).contains("ghost").contains("not found");
  }

  @Test
  void should_strip_domain_when_account_is_qualified() {
    assertThat(KeystoreInspector.bareAccount("CORP\\jasper")).isEqualTo("jasper");
    assertThat(KeystoreInspector.bareAccount("jasper@corp.example")).isEqualTo("jasper");
    assertThat(KeystoreInspector.bareAccount(" jasper ")).isEqualTo("jasper");
  }
}
