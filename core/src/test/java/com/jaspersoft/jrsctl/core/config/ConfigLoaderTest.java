package com.jaspersoft.jrsctl.core.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jaspersoft.jrsctl.core.JrsctlHome;
import com.jaspersoft.jrsctl.core.platform.ServiceConfig;
import com.jaspersoft.jrsctl.core.secrets.SecretRef;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigLoaderTest {

  @TempDir Path tmp;

  private final ConfigLoader loader = new ConfigLoader();

  @Test
  void should_apply_schema_defaults_when_config_file_is_missing() {
    Config c = loader.load(new JrsctlHome(tmp), Map.of(), Map.of());

    assertThat(c).isEqualTo(Config.defaults());
    assertThat(c.server().baseUrl()).isEmpty();
    assertThat(c.server().auth().mode()).isEqualTo(Config.AuthMode.BASIC);
    assertThat(c.network().mode()).isEqualTo(Config.NetworkMode.ISOLATED);
    assertThat(c.console().bind()).isEqualTo("127.0.0.1");
    assertThat(c.console().port()).isEqualTo(7420);
    assertThat(c.console().auth().mode()).isEqualTo(Config.ConsoleAuthMode.TOKEN);
    assertThat(c.backups().retentionDays()).isEqualTo(30);
    assertThat(c.backups().maxSnapshots()).isEqualTo(20);
    assertThat(c.service().stopTimeoutSeconds()).isEqualTo(180);
  }

  @Test
  void should_read_every_block_when_file_is_complete() throws IOException {
    write(
        """
        server:
          baseUrl: http://localhost:8080/jasperserver-pro
          webappName: jasperserver-pro
          installDir: /opt/jrs
          tomcatDir: /opt/jrs/apache-tomcat
          runAsUser: jasperserver
          auth:
            mode: form
            username: jasperadmin
            passwordRef: env:JRS_PASSWORD
        service:
          kind: windows-service
          name: jasperreportsTomcat
          stopTimeoutSeconds: 60
        database:
          type: postgresql
          url: jdbc:postgresql://localhost:5432/jasperserver
          username: jasperdb
          passwordRef: enc:db
          driverDir: null
        vendor:
          javaHome: /opt/jrs/java
        network:
          mode: public
          proxy: { host: proxy.local, port: 3128, username: u, passwordRef: file:/run/secrets/p }
          trustStore: { path: /etc/ssl/ts.p12, passwordRef: env:TS }
        console:
          bind: 0.0.0.0
          port: 8443
          tls: { enabled: true, certPath: /etc/ssl/c.pem, keyPath: /etc/ssl/k.pem }
          auth: { mode: local, passwordRef: env:CONSOLE_PW }
        backups:
          retentionDays: 7
          maxSnapshots: 3
        smoke:
          reportUri: /public/Samples/Reports/AllAccounts
        """);

    Config c = loader.load(new JrsctlHome(tmp), Map.of(), Map.of());

    assertThat(c.server().baseUrl()).contains(URI.create("http://localhost:8080/jasperserver-pro"));
    assertThat(c.server().webappName()).contains(Config.WebappName.JASPERSERVER_PRO);
    assertThat(c.server().installDir()).contains(Path.of("/opt/jrs"));
    assertThat(c.server().auth().mode()).isEqualTo(Config.AuthMode.FORM);
    assertThat(c.server().auth().passwordRef()).contains(new SecretRef.Env("JRS_PASSWORD"));
    assertThat(c.service().kind()).contains(ServiceConfig.Kind.WINDOWS_SERVICE);
    assertThat(c.service().stopTimeoutSeconds()).isEqualTo(60);
    assertThat(c.database().type()).contains(Config.DatabaseType.POSTGRESQL);
    assertThat(c.database().passwordRef()).contains(new SecretRef.Enc("db"));
    assertThat(c.database().driverDir()).isEmpty();
    assertThat(c.vendor().javaHome()).contains(Path.of("/opt/jrs/java"));
    assertThat(c.network().mode()).isEqualTo(Config.NetworkMode.PUBLIC);
    assertThat(c.network().proxy().port()).contains(3128);
    assertThat(c.network().proxy().passwordRef())
        .contains(new SecretRef.File(Path.of("/run/secrets/p")));
    assertThat(c.network().trustStore().path()).contains(Path.of("/etc/ssl/ts.p12"));
    assertThat(c.console().tls().enabled()).isTrue();
    assertThat(c.console().auth().mode()).isEqualTo(Config.ConsoleAuthMode.LOCAL);
    assertThat(c.backups()).isEqualTo(new Config.Backups(7, 3));
    assertThat(c.smoke().reportUri()).contains("/public/Samples/Reports/AllAccounts");
  }

  @Test
  void should_prefer_flag_over_env_over_file_when_all_three_set_the_same_key() throws IOException {
    write("server:\n  baseUrl: http://file:8080/jasperserver\nconsole:\n  port: 1000\n");
    Map<String, String> env =
        Map.of(
            "JRSCTL_SERVER_BASE_URL", "http://env:8080/jasperserver",
            "JRSCTL_CONSOLE_PORT", "2000",
            "JRSCTL_BACKUPS_RETENTION_DAYS", "5");
    Map<String, String> flags = Map.of("server.baseUrl", "http://flag:8080/jasperserver");

    Config c = loader.load(new JrsctlHome(tmp), env, flags);

    assertThat(c.server().baseUrl()).contains(URI.create("http://flag:8080/jasperserver"));
    assertThat(c.console().port()).isEqualTo(2000);
    assertThat(c.backups().retentionDays()).isEqualTo(5);
    assertThat(c.backups().maxSnapshots()).isEqualTo(20);
  }

  /** Review finding 2.9: hosts that must not go through the proxy are listed under the proxy. */
  @Test
  void should_parse_the_proxy_bypass_list_when_given() throws IOException {
    write(
        """
        server:
          baseUrl: http://localhost:8080/jasperserver-pro
        network:
          proxy:
            host: proxy.local
            port: 3128
            noProxy: [".corp.example", "intranet"]
        """);

    Config c = loader.load(new JrsctlHome(tmp), Map.of(), Map.of());

    assertThat(c.network().proxy().host()).contains("proxy.local");
    assertThat(c.network().proxy().noProxy()).containsExactly(".corp.example", "intranet");
    assertThat(Config.Proxy.empty().noProxy()).isEmpty();
  }

  @Test
  void should_map_camel_case_paths_to_upper_snake_env_keys() {
    assertThat(ConfigLoader.envKey("server.baseUrl")).isEqualTo("JRSCTL_SERVER_BASE_URL");
    assertThat(ConfigLoader.envKey("server.auth.passwordRef"))
        .isEqualTo("JRSCTL_SERVER_AUTH_PASSWORD_REF");
    assertThat(ConfigLoader.envKey("network.trustStore.path"))
        .isEqualTo("JRSCTL_NETWORK_TRUST_STORE_PATH");
    assertThat(ConfigLoader.envKey("service.stopTimeoutSeconds"))
        .isEqualTo("JRSCTL_SERVICE_STOP_TIMEOUT_SECONDS");
    assertThat(loader.knownKeys()).contains("server.baseUrl", "console.tls.enabled");
  }

  @Test
  void should_ignore_unrelated_jrsctl_variables_when_reading_env() {
    Map<String, String> env =
        Map.of(
            "JRSCTL_HOME", tmp.toString(),
            "JRSCTL_PASSPHRASE", "not-a-config-key",
            "JRSCTL_CONSOLE_TLS_ENABLED", "true",
            "JRSCTL_SERVER_BASE_URL", "http://h/jasperserver");

    Config c = loader.load(new JrsctlHome(tmp), env, Map.of());

    assertThat(c.console().tls().enabled()).isTrue();
    assertThat(c.server().baseUrl()).contains(URI.create("http://h/jasperserver"));
  }

  @Test
  void should_list_every_violation_when_several_keys_are_invalid() throws IOException {
    write(
        """
        server:
          baseUrl: 42
          auth:
            mode: magic
        console:
          port: 99999
        bogus: 1
        """);

    assertThatThrownBy(() -> loader.load(new JrsctlHome(tmp), Map.of(), Map.of()))
        .isInstanceOf(ConfigException.class)
        .satisfies(
            t -> {
              ConfigException e = (ConfigException) t;
              assertThat(e.violations()).hasSizeGreaterThanOrEqualTo(4);
              assertThat(e.violations()).anyMatch(v -> v.startsWith("server.baseUrl: "));
              assertThat(e.violations()).anyMatch(v -> v.startsWith("server.auth.mode: "));
              assertThat(e.violations()).anyMatch(v -> v.startsWith("console.port: "));
              assertThat(e.violations()).anyMatch(v -> v.contains("bogus"));
              assertThat(e.getMessage()).contains("console.port: ").contains(e.remediation());
            });
  }

  @Test
  void should_reject_unknown_flag_key_when_schema_does_not_define_it() {
    assertThatThrownBy(
            () -> loader.load(new JrsctlHome(tmp), Map.of(), Map.of("server.colour", "blue")))
        .isInstanceOf(ConfigException.class)
        .hasMessageContaining("colour");
  }

  @Test
  void should_report_type_error_when_env_integer_is_not_numeric() {
    assertThatThrownBy(
            () ->
                loader.load(
                    new JrsctlHome(tmp),
                    Map.of("JRSCTL_CONSOLE_PORT", "eighty"),
                    Map.of("server.baseUrl", "http://h/jasperserver")))
        .isInstanceOf(ConfigException.class)
        .hasMessageContaining("console.port: ");
  }

  @Test
  void should_reject_malformed_secret_reference_when_present() throws IOException {
    write("server:\n  baseUrl: http://h/jasperserver\n  auth:\n    passwordRef: vault:x\n");

    assertThatThrownBy(() -> loader.load(new JrsctlHome(tmp), Map.of(), Map.of()))
        .isInstanceOf(ConfigException.class)
        .hasMessageContaining("server.auth.passwordRef");
  }

  @Test
  void should_tell_operator_to_run_init_when_server_is_absent() {
    Config c = loader.load(new JrsctlHome(tmp), Map.of(), Map.of());

    assertThatThrownBy(() -> ConfigLoader.requireServer(c))
        .isInstanceOf(ConfigException.class)
        .hasMessageContaining("run jrsctl init");
  }

  @Test
  void should_return_base_url_when_server_is_configured() {
    Config c =
        loader.load(
            new JrsctlHome(tmp), Map.of(), Map.of("server.baseUrl", "http://h/jasperserver"));

    assertThat(ConfigLoader.requireServer(c)).isEqualTo(URI.create("http://h/jasperserver"));
  }

  @Test
  void should_build_service_config_when_kind_is_set() throws IOException {
    write("service:\n  kind: ctlscript\n  scriptPath: /opt/jrs/ctlscript.sh\n");

    ServiceConfig sc = loader.load(new JrsctlHome(tmp), Map.of(), Map.of()).toServiceConfig();

    assertThat(sc.kind()).isEqualTo(ServiceConfig.Kind.CTLSCRIPT);
    assertThat(sc.scriptPath()).contains(Path.of("/opt/jrs/ctlscript.sh"));
    assertThat(sc.name()).isEmpty();
    assertThat(sc.stopTimeout()).isEqualTo(Duration.ofMinutes(3));
  }

  @Test
  void should_fail_closed_when_service_kind_is_missing() {
    assertThatThrownBy(() -> Config.defaults().toServiceConfig())
        .isInstanceOf(ConfigException.class)
        .hasMessageContaining("service.kind");
  }

  @Test
  void should_treat_empty_file_as_defaults_when_file_has_no_content() throws IOException {
    write("# nothing here\n");

    assertThat(loader.load(new JrsctlHome(tmp), Map.of(), Map.of())).isEqualTo(Config.defaults());
  }

  @Test
  void should_report_parse_error_when_yaml_is_malformed() throws IOException {
    write("server: [unclosed\n");

    assertThatThrownBy(() -> loader.load(new JrsctlHome(tmp), Map.of(), Map.of()))
        .isInstanceOf(ConfigException.class)
        .hasMessageContaining("cannot parse");
  }

  private void write(String yaml) throws IOException {
    Files.writeString(new JrsctlHome(tmp).configFile(), yaml, StandardCharsets.UTF_8);
  }
}
