package com.jaspersoft.jrsctl.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.jaspersoft.jrsctl.core.JrsctlHome;
import com.jaspersoft.jrsctl.core.config.Config;
import com.jaspersoft.jrsctl.core.config.ConfigLoader;
import com.jaspersoft.jrsctl.core.secrets.EncryptedSecretStore;
import com.jaspersoft.jrsctl.core.secrets.PassphraseSource;
import com.jaspersoft.jrsctl.core.secrets.Secret;
import com.jaspersoft.jrsctl.core.secrets.SecretRef;
import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Issue #70: change one setting without editing config.yaml. */
class ConfigCommandTest {

  @TempDir Path tmp;
  private Path home;

  @BeforeEach
  void setUp() throws IOException {
    home = Files.createDirectories(tmp.resolve("home"));
    Files.writeString(
        home.resolve("config.yaml"),
        """
        server:
          baseUrl: http://old.example.com:8080/jasperserver-pro
          webappName: jasperserver-pro
          runAsUser: tomcat
          auth:
            username: superuser
            passwordRef: env:JRS_PASSWORD
        """,
        StandardCharsets.UTF_8);
    Env.override(Map.of());
  }

  @AfterEach
  void restore() {
    Prompter.reset();
    Env.reset();
  }

  private InitCommandTest.Run jrsctl(String... args) {
    String[] all = new String[args.length + 2];
    System.arraycopy(args, 0, all, 0, args.length);
    all[args.length] = "--home";
    all[args.length + 1] = home.toString();
    return InitCommandTest.run(all);
  }

  private Config fileConfig() {
    return new ConfigLoader().load(new JrsctlHome(home), Map.of(), Map.of());
  }

  @Test
  void should_write_the_new_value_show_old_and_new_and_keep_a_backup_when_setting() {
    InitCommandTest.Run run =
        jrsctl("config", "set", "server.baseUrl", "https://new.example.com:8443/jasperserver-pro");

    assertThat(run.code()).as(run.out() + run.err()).isZero();
    assertThat(run.out())
        .contains("server.baseUrl")
        .contains("http://old.example.com:8080/jasperserver-pro")
        .contains("https://new.example.com:8443/jasperserver-pro")
        .contains("config.yaml.bak");
    assertThat(fileConfig().server().baseUrl())
        .contains(URI.create("https://new.example.com:8443/jasperserver-pro"));
    assertThat(fileConfig().server().runAsUser()).contains("tomcat");
    assertThat(home.resolve("config.yaml.bak")).content().contains("old.example.com");
  }

  @Test
  void should_refuse_an_unknown_key_and_change_nothing_when_setting() throws IOException {
    String before = Files.readString(home.resolve("config.yaml"), StandardCharsets.UTF_8);

    InitCommandTest.Run run = jrsctl("config", "set", "server.baseUlr", "http://x");

    assertThat(run.code()).isEqualTo(ExitCodes.PRECHECK_FAILED);
    assertThat(run.err()).contains("server.baseUlr").contains("jrsctl config keys");
    assertThat(home.resolve("config.yaml")).content().isEqualTo(before);
  }

  /** Field test 2, G9: a directory setting that does not exist is refused when it is written. */
  @Test
  void should_refuse_config_set_of_a_directory_key_that_does_not_exist() throws IOException {
    InitCommandTest.Run run = jrsctl("config", "set", "server.buildomaticDir", "/zugzug/whatever");

    assertThat(run.code()).isEqualTo(ExitCodes.USAGE);
    assertThat(run.err()).contains("no such directory: /zugzug/whatever");
    assertThat(Files.readString(home.resolve("config.yaml"), StandardCharsets.UTF_8))
        .doesNotContain("zugzug");
  }

  /**
   * Field test 2, G3: {@code ~} is the operator's home when the setting is checked, and the file
   * gets the expanded path, since the service account that reads it later has another home.
   */
  @Test
  void should_accept_a_tilde_path_that_exists_and_store_it_expanded() throws IOException {
    Path operatorHome = Files.createDirectories(tmp.resolve("operator"));
    Files.createDirectories(operatorHome.resolve("bd"));
    Env.override(Map.of("HOME", operatorHome.toString()));

    InitCommandTest.Run run = jrsctl("config", "set", "server.buildomaticDir", "~/bd");

    assertThat(run.code()).as(run.err()).isZero();
    assertThat(Files.readString(home.resolve("config.yaml"), StandardCharsets.UTF_8))
        .doesNotContain("~");
    assertThat(fileConfig().server().buildomaticDir()).contains(operatorHome.resolve("bd"));
  }

  @Test
  void should_refuse_a_password_on_the_command_line_without_echoing_it() {
    InitCommandTest.Run run = jrsctl("config", "set", "server.auth.passwordRef", "Hunter2-Secret");

    assertThat(run.code()).isEqualTo(ExitCodes.PRECHECK_FAILED);
    assertThat(run.out() + run.err())
        .doesNotContain("Hunter2-Secret")
        .contains("jrsctl config set server.auth.passwordRef");
    assertThat(fileConfig().server().auth().passwordRef().map(SecretRef::render))
        .contains("env:JRS_PASSWORD");
  }

  @Test
  void should_accept_a_secret_reference_as_the_value_of_a_password_key() {
    InitCommandTest.Run run =
        jrsctl("config", "set", "server.auth.passwordRef", "env:JRS_ADMIN_PW");

    assertThat(run.code()).as(run.out() + run.err()).isZero();
    assertThat(fileConfig().server().auth().passwordRef().map(SecretRef::render))
        .contains("env:JRS_ADMIN_PW");
  }

  @Test
  void should_store_a_typed_password_encrypted_when_a_password_key_is_set_without_a_value() {
    Env.override(Map.of("JRSCTL_PASSPHRASE", "store-pass"));
    Prompter.override(new StringReader("Adm1n-Secret\n"));

    InitCommandTest.Run run = jrsctl("config", "set", "server.auth.passwordRef");

    assertThat(run.code()).as(run.out() + run.err()).isZero();
    assertThat(run.out() + run.err()).doesNotContain("Adm1n-Secret").contains("enc:JRS_PASSWORD");
    assertThat(fileConfig().server().auth().passwordRef().map(SecretRef::render))
        .contains("enc:JRS_PASSWORD");
    EncryptedSecretStore store =
        new EncryptedSecretStore(
            new JrsctlHome(home).secretsFile(),
            new PassphraseSource.Fixed(Secret.fromString("store-pass")));
    try (Secret stored = store.get("JRS_PASSWORD").orElseThrow()) {
      assertThat(new String(stored.chars())).isEqualTo("Adm1n-Secret");
    }
  }

  @Test
  void should_ask_for_the_value_when_an_ordinary_key_is_set_without_one() {
    Prompter.override(new StringReader("jasperadmin\n"));

    InitCommandTest.Run run = jrsctl("config", "set", "server.auth.username");

    assertThat(run.code()).as(run.out() + run.err()).isZero();
    assertThat(run.out()).contains("[superuser]");
    assertThat(fileConfig().server().auth().username()).contains("jasperadmin");
  }

  @Test
  void should_warn_when_an_environment_variable_still_overrides_the_key_that_was_set() {
    Env.override(Map.of("JRSCTL_SERVER_RUN_AS_USER", "jasper"));

    InitCommandTest.Run run = jrsctl("config", "set", "server.runAsUser", "jrs");

    assertThat(run.code()).as(run.out() + run.err()).isZero();
    assertThat(run.out()).contains("JRSCTL_SERVER_RUN_AS_USER").contains("still overrides");
  }

  @Test
  void should_remove_the_key_from_the_file_when_unsetting() {
    InitCommandTest.Run run = jrsctl("config", "unset", "server.runAsUser");

    assertThat(run.code()).as(run.out() + run.err()).isZero();
    assertThat(run.out()).contains("server.runAsUser").contains("tomcat");
    assertThat(fileConfig().server().runAsUser()).isEmpty();
    assertThat(fileConfig().server().baseUrl())
        .contains(URI.create("http://old.example.com:8080/jasperserver-pro"));
  }

  @Test
  void should_list_every_key_with_its_value_source_and_description() {
    Env.override(Map.of("JRSCTL_CONSOLE_PORT", "7500"));

    InitCommandTest.Run run = jrsctl("config", "keys");

    assertThat(run.code()).as(run.out() + run.err()).isZero();
    for (String key : new ConfigLoader().knownKeys()) {
      assertThat(run.out()).contains(key);
    }
    assertThat(run.out())
        .contains("JRSCTL_CONSOLE_PORT")
        .contains("7500")
        .contains(ConfigKeys.description("server.baseUrl"));
  }

  /** Issue #73: a database value read from default_master.properties says so. */
  @Test
  void should_label_database_values_read_from_buildomatic_when_listing_keys() throws Exception {
    Path install = InitCommandTest.fakeLayout(tmp.resolve("jrs"));
    Files.writeString(
        home.resolve("config.yaml"),
        """
        server:
          baseUrl: http://localhost:8089/jasperserver-pro
          installDir: %s
        """
            .formatted(install.toString().replace("\\", "/")),
        StandardCharsets.UTF_8);

    InitCommandTest.Run keys = jrsctl("config", "keys");
    InitCommandTest.Run show = jrsctl("config", "show");

    assertThat(keys.code()).as(keys.out() + keys.err()).isZero();
    assertThat(keys.out().lines().filter(l -> l.startsWith("database.type")).findFirst())
        .hasValueSatisfying(
            l -> assertThat(l).contains("postgresql").contains("default_master.properties"));
    assertThat(show.out()).contains("# database.type: from").contains("default_master.properties");
  }

  /** Issue #74: config set and show work on a home configured with jrsctl.properties. */
  @Test
  void should_change_and_show_a_properties_configuration_in_its_own_format() throws IOException {
    Files.delete(home.resolve("config.yaml"));
    Files.writeString(
        home.resolve("jrsctl.properties"),
        "server.baseUrl=http://old.example.com:8080/jasperserver-pro\nserver.runAsUser=tomcat\n",
        StandardCharsets.UTF_8);

    InitCommandTest.Run set =
        jrsctl("config", "set", "server.baseUrl", "https://new.example.com/jasperserver-pro");
    InitCommandTest.Run show = jrsctl("config", "show", "--format", "properties");

    assertThat(set.code()).as(set.out() + set.err()).isZero();
    assertThat(home.resolve("config.yaml")).doesNotExist();
    assertThat(home.resolve("jrsctl.properties"))
        .content()
        .contains("server.baseUrl=https://new.example.com/jasperserver-pro")
        .contains("server.runAsUser=tomcat");
    assertThat(home.resolve("jrsctl.properties.bak")).exists();
    assertThat(show.code()).as(show.out() + show.err()).isZero();
    assertThat(show.out())
        .contains("server.baseUrl=https://new.example.com/jasperserver-pro")
        .doesNotContain("server:");
  }

  @Test
  void should_describe_every_key_the_schema_knows() {
    for (String key : new ConfigLoader().knownKeys()) {
      assertThat(ConfigKeys.description(key)).as(key).isNotBlank().doesNotContain("no description");
    }
  }

  @Test
  void should_note_overridden_values_when_showing_the_configuration() {
    Env.override(Map.of("JRSCTL_CONSOLE_PORT", "7500"));

    InitCommandTest.Run run = jrsctl("config", "show", "--set", "server.runAsUser=jrs");

    assertThat(run.code()).as(run.out() + run.err()).isZero();
    assertThat(run.out())
        .contains("# console.port: overridden by JRSCTL_CONSOLE_PORT")
        .contains("# server.runAsUser: overridden by --set");
  }
}
