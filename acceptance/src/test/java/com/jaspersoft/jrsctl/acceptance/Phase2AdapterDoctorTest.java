package com.jaspersoft.jrsctl.acceptance;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Spec §14 Phase 2, exercised through the packaged jar against a WireMock JasperReports Server
 * 8.2.0 PRO: {@code doctor} produces a full report with the server and identity items passing,
 * fails cleanly when the server is unreachable, and {@code init} detects a fake layout and writes a
 * valid configuration.
 */
@Tag("phase2")
class Phase2AdapterDoctorTest {

  private static final String WEBAPP = "/jasperserver-pro";
  private static final String SERVER_INFO =
      """
      {
        "version": "8.2.0",
        "edition": "PRO",
        "editionName": "Professional",
        "features": "Fusion AHD EXP DB AUD ANA MT ",
        "build": "20230315_1234",
        "licenseType": "Commercial",
        "expiration": "2099-01-01",
        "dateFormatPattern": "yyyy-MM-dd",
        "datetimeFormatPattern": "yyyy-MM-dd'T'HH:mm:ss"
      }
      """;

  /** FAIL items that depend on the machine rather than on jrsctl (no real keystore here). */
  private static final Set<String> ENVIRONMENT_DEPENDENT = Set.of("compat", "keystore");

  private static WireMockServer server;

  @TempDir Path tmp;

  @BeforeAll
  static void startServer() {
    server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
    server.start();
    server.stubFor(
        get(urlPathEqualTo(WEBAPP + "/rest_v2/serverInfo")).willReturn(okJson(SERVER_INFO)));
    server.stubFor(
        get(urlPathEqualTo(WEBAPP + "/rest_v2/resources"))
            .willReturn(okJson("{\"resourceLookup\":[]}")));
    server.stubFor(
        get(urlPathEqualTo(WEBAPP + "/rest_v2/jobs")).willReturn(okJson("{\"jobsummary\":[]}")));
    server.stubFor(
        get(urlPathEqualTo(WEBAPP + "/rest_v2/organizations"))
            .willReturn(okJson("{\"organization\":[]}")));
    server.stubFor(
        get(urlPathEqualTo(WEBAPP + "/rest_v2/export/jrsctl-probe/state"))
            .willReturn(aResponse().withStatus(404)));
    server.stubFor(
        get(urlPathEqualTo(WEBAPP + "/rest_v2/import/jrsctl-probe/state"))
            .willReturn(aResponse().withStatus(404)));
    server.stubFor(
        post(urlPathEqualTo(WEBAPP + "/rest_v2/login"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Set-Cookie", "JSESSIONID=ABCDEF0123456789; Path=/; HttpOnly")));
  }

  @AfterAll
  static void stopServer() {
    if (server != null) {
      server.stop();
    }
  }

  private static String baseUrl() {
    return "http://localhost:" + server.port() + WEBAPP;
  }

  private static Path fakeLayout(Path install) throws Exception {
    Path tomcat = install.resolve("apache-tomcat");
    Path webapp = tomcat.resolve("webapps").resolve("jasperserver-pro");
    Files.createDirectories(webapp.resolve("WEB-INF").resolve("lib"));
    Files.createDirectories(webapp.resolve("WEB-INF").resolve("classes"));
    Files.createDirectories(tomcat.resolve("conf"));
    Files.writeString(
        tomcat.resolve("conf").resolve("server.xml"),
        "<Server><Service><Connector port=\"8080\" protocol=\"HTTP/1.1\"/></Service></Server>",
        StandardCharsets.UTF_8);
    Path buildomatic = Files.createDirectories(install.resolve("buildomatic"));
    for (String ext : new String[] {".sh", ".bat"}) {
      Files.writeString(install.resolve("ctlscript" + ext), "", StandardCharsets.UTF_8);
      for (String script : new String[] {"js-export", "js-import", "js-ant"}) {
        Files.writeString(buildomatic.resolve(script + ext), "", StandardCharsets.UTF_8);
      }
    }
    Files.writeString(
        buildomatic.resolve("default_master.properties"),
        "dbType=postgresql\ndbHost=localhost\ndbPort=5432\ndbUsername=jasperdb\n"
            + "dbPassword=TopSecret\njs.dbName=jasperserver\n",
        StandardCharsets.UTF_8);
    return install;
  }

  private Path home(String name, String url, Path install) throws Exception {
    Path home = Files.createDirectories(tmp.resolve(name));
    Files.writeString(
        home.resolve("config.yaml"),
        """
        server:
          baseUrl: %s
          webappName: jasperserver-pro
          installDir: %s
          auth:
            mode: basic
            username: jasperadmin
            passwordRef: env:JRS_PASSWORD
        service:
          kind: manual
        network:
          mode: public
        """
            .formatted(url, install.toString().replace("\\", "/")),
        StandardCharsets.UTF_8);
    return home;
  }

  private static Map<String, JsonNode> items(String json) throws Exception {
    JsonNode root = new ObjectMapper().readTree(json);
    Map<String, JsonNode> byName = new HashMap<>();
    for (JsonNode item : root.get("items")) {
      byName.put(item.get("name").asText(), item);
    }
    return byName;
  }

  @Test
  void doctor_reports_server_and_identity_pass_against_wiremock_8_2_0_pro() throws Exception {
    Path install = fakeLayout(tmp.resolve("jrs"));
    Path home = home("home", baseUrl(), install);
    Cli cli = new Cli(Map.of("JRS_PASSWORD", "jasperadmin"));

    Cli.Result result = cli.run("doctor", "--home", home.toString(), "--json");

    assertThat(result.exitCode())
        .as("doctor ran to completion\nstdout:\n%s\nstderr:\n%s", result.stdout(), result.stderr())
        .isIn(0, 2, 6);
    Map<String, JsonNode> items = items(result.stdout());
    assertThat(items.get("server").get("status").asText()).isEqualTo("PASS");
    assertThat(items.get("identity").get("status").asText()).isEqualTo("PASS");
    assertThat(items.get("identity").get("detail").asText()).contains("8.2.0");
    assertThat(items.get("auth").get("status").asText()).isEqualTo("PASS");
    List<String> failing =
        items.values().stream()
            .filter(i -> i.get("status").asText().equals("FAIL"))
            .map(i -> i.get("name").asText())
            .collect(Collectors.toList());
    assertThat(failing)
        .as("only environment-dependent checks may fail")
        .isSubsetOf(ENVIRONMENT_DEPENDENT);
    if (failing.isEmpty() || failing.equals(List.of("compat"))) {
      assertThat(result.exitCode()).isIn(0, 6);
    }
    assertThat(result.stdout()).doesNotContain("jasperadmin\"password");
  }

  @Test
  void doctor_exits_2_with_server_fail_when_port_is_closed() throws Exception {
    Path install = fakeLayout(tmp.resolve("jrs2"));
    Path home = home("home2", "http://localhost:1" + WEBAPP, install);
    Cli cli = new Cli(Map.of("JRS_PASSWORD", "jasperadmin"));

    Cli.Result result = cli.run("doctor", "--home", home.toString(), "--json").assertExit(2);

    Map<String, JsonNode> items = items(result.stdout());
    assertThat(items.get("server").get("status").asText()).isEqualTo("FAIL");
    assertThat(items.get("identity").get("status").asText()).isEqualTo("SKIP");
  }

  @Test
  void init_writes_config_for_a_fake_layout_without_prompting() throws Exception {
    Path install = fakeLayout(tmp.resolve("jrs3"));
    Path home = tmp.resolve("home3");

    Cli.Result result =
        new Cli()
            .run(
                "init",
                "--yes",
                "--non-interactive",
                "--home",
                home.toString(),
                "--install-dir",
                install.toString())
            .assertExit(0);

    Path config = home.resolve("config.yaml");
    assertThat(config).exists();
    String yaml = Files.readString(config, StandardCharsets.UTF_8);
    assertThat(yaml)
        .contains("webappName: jasperserver-pro")
        .contains("baseUrl: http://localhost:8080/jasperserver-pro")
        .doesNotContain("TopSecret");
    assertThat(result.stdout()).contains("wrote ");
    assertThat(home.resolve("logs").resolve("jrsctl.log")).exists();
  }
}
