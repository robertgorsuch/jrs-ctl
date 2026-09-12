package com.jaspersoft.jrsctl.acceptance;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.moreThanOrExactly;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.io.TempDir;

/**
 * Spec §14 Phase 4, exercised through the packaged jar against a WireMock JasperReports Server
 * 8.2.0 PRO whose async export/import endpoints answer immediately: a REST export writes the
 * archive and its sidecar; {@code import --plan} shows the pre-import snapshot and the best-effort
 * rollback warning; {@code import --yes} takes the snapshot (one export) and runs one import; a
 * sidecar whose keystore fingerprint differs from the server's is refused with exit 2 and the
 * keystore remediation. The steps share one home and archive, so they run in order.
 */
@Tag("phase4")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Phase4ExportImportTest {

  private static final String WEBAPP = "/jasperserver-pro";
  private static final int ONE_MB = 1024 * 1024;
  private static final String BEST_EFFORT =
      "Rollback re-imports the pre-import snapshot; it restores overwritten resources but cannot"
          + " delete resources the failed import created.";
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

  private static WireMockServer server;
  private static Cli cli;

  @TempDir static Path tmp;

  private static Path home;
  private static Path archive;

  @BeforeAll
  static void setUp() throws Exception {
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
            .willReturn(
                aResponse()
                    .withStatus(404)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"errorCode\":\"no.such.export.process\",\"message\":\"No task jrsctl-probe\"}")));
    server.stubFor(
        get(urlPathEqualTo(WEBAPP + "/rest_v2/import/jrsctl-probe/state"))
            .willReturn(
                aResponse()
                    .withStatus(404)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"errorCode\":\"no.such.export.process\",\"message\":\"No task jrsctl-probe\"}")));
    server.stubFor(
        post(urlPathEqualTo(WEBAPP + "/rest_v2/login"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Set-Cookie", "JSESSIONID=ABCDEF0123456789; Path=/; HttpOnly")));
    server.stubFor(
        post(urlPathEqualTo(WEBAPP + "/rest_v2/export"))
            .willReturn(okJson("{\"id\":\"e1\",\"phase\":\"inprogress\"}")));
    server.stubFor(
        get(urlPathEqualTo(WEBAPP + "/rest_v2/export/e1/state"))
            .willReturn(okJson("{\"phase\":\"ready\",\"fileName\":\"export.zip\"}")));
    byte[] body = new byte[ONE_MB];
    Arrays.fill(body, (byte) 'z');
    body[0] = 'P';
    body[1] = 'K';
    server.stubFor(
        get(urlPathEqualTo(WEBAPP + "/rest_v2/export/e1/export.zip"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/zip")
                    .withBody(body)));
    server.stubFor(
        post(urlPathEqualTo(WEBAPP + "/rest_v2/import")).willReturn(okJson("{\"id\":\"i1\"}")));
    server.stubFor(
        get(urlPathEqualTo(WEBAPP + "/rest_v2/import/i1/state"))
            .willReturn(okJson("{\"phase\":\"ready\"}")));

    cli = new Cli(Map.of("JRS_PASSWORD", "jasperadmin"));
    home = Files.createDirectories(tmp.resolve("home"));
    archive = tmp.resolve("x.zip");
    Files.writeString(
        home.resolve("config.yaml"),
        """
        server:
          baseUrl: http://localhost:%d%s
          webappName: jasperserver-pro
          auth:
            mode: basic
            username: jasperadmin
            passwordRef: env:JRS_PASSWORD
        service:
          kind: manual
        network:
          mode: public
        """
            .formatted(server.port(), WEBAPP),
        StandardCharsets.UTF_8);
  }

  @AfterAll
  static void tearDown() {
    if (server != null) {
      server.stop();
    }
  }

  private static Cli.Result jrsctl(Cli with, String... args) throws Exception {
    // --ascii as well as --no-color: since review 3.5 the two are separate decisions, so a
    // UTF-8 terminal without colour still gets the tick glyphs. Assertions on the words need
    // to ask for the words.
    String[] all = new String[args.length + 4];
    System.arraycopy(args, 0, all, 0, args.length);
    all[args.length] = "--home";
    all[args.length + 1] = home.toString();
    all[args.length + 2] = "--no-color";
    all[args.length + 3] = "--ascii";
    return with.run(all);
  }

  private static Cli.Result jrsctl(String... args) throws Exception {
    return jrsctl(cli, args);
  }

  private static Path sidecarOf(Path zip) {
    return zip.resolveSibling(zip.getFileName() + ".jrsctl.json");
  }

  // ---- criteria -------------------------------------------------------------------------------

  @Test
  @Order(1)
  void export_writes_the_archive_and_a_sidecar_with_the_server_version() throws Exception {
    Cli.Result exported =
        jrsctl("export", "--uri", "/public", "--out", archive.toString(), "--yes").assertExit(0);

    assertThat(exported.stdout()).contains("OK").contains("succeeded");
    assertThat(archive).exists();
    assertThat(Files.size(archive)).isEqualTo(ONE_MB);
    assertThat(sidecarOf(archive)).exists();
    JsonNode sidecar = new ObjectMapper().readTree(Files.readString(sidecarOf(archive)));
    assertThat(sidecar.get("serverVersion").asText()).isEqualTo("8.2.0");
    assertThat(sidecar.get("strategy").asText()).isEqualTo("REST");
    assertThat(sidecar.get("flags").get("uris").get(0).asText()).isEqualTo("/public");
    server.verify(1, postRequestedFor(urlPathEqualTo(WEBAPP + "/rest_v2/export")));
  }

  @Test
  @Order(2)
  void import_plan_prints_the_pre_import_snapshot_and_the_best_effort_warning() throws Exception {
    Cli.Result plan = jrsctl("import", archive.toString(), "--update", "--plan").assertExit(0);

    assertThat(plan.stdout())
        .contains("Plan  import  x.zip")
        .contains("precheck")
        .contains("backup")
        .contains("Pre-import snapshot")
        .contains("Enable snapshot rollback")
        .contains("! " + BEST_EFFORT)
        .contains("nothing has changed");
    server.verify(1, postRequestedFor(urlPathEqualTo(WEBAPP + "/rest_v2/export")));
    server.verify(0, postRequestedFor(urlPathEqualTo(WEBAPP + "/rest_v2/import")));
  }

  @Test
  @Order(3)
  void import_yes_snapshots_with_one_export_and_runs_one_import() throws Exception {
    Cli.Result imported = jrsctl("import", archive.toString(), "--update", "--yes").assertExit(0);

    assertThat(imported.stdout())
        .contains("OK")
        .contains("Pre-import snapshot")
        .contains("Start import task")
        .contains("succeeded");
    server.verify(2, postRequestedFor(urlPathEqualTo(WEBAPP + "/rest_v2/export")));
    server.verify(1, postRequestedFor(urlPathEqualTo(WEBAPP + "/rest_v2/import")));
    server.verify(
        moreThanOrExactly(1), getRequestedFor(urlPathEqualTo(WEBAPP + "/rest_v2/import/i1/state")));
    try (var snapshots = Files.walk(home.resolve("snapshots").resolve("pre-import"))) {
      assertThat(snapshots.filter(p -> p.toString().endsWith(".zip")).count()).isEqualTo(1);
    }
    Cli.Result runs = jrsctl("runs", "list", "--json").assertExit(0);
    JsonNode run = new ObjectMapper().readTree(runs.stdout()).get(0);
    assertThat(run.get("operation").asText()).isEqualTo("import");
    assertThat(run.get("terminalState").asText()).isEqualTo("SUCCEEDED");
  }

  @Test
  @Order(4)
  void import_is_refused_with_exit_2_when_the_sidecar_keystore_fingerprint_mismatches()
      throws Exception {
    // The server keystore is looked up in the run-as user's home, or the current user's home when
    // server.runAsUser is unset; the jar is started with user.home pointing at a temp directory
    // that holds a .jrsks so the target fingerprint is known and differs from the sidecar's.
    Path userHome = Files.createDirectories(tmp.resolve("userhome"));
    Files.writeString(userHome.resolve(".jrsks"), "target-server-keystore", StandardCharsets.UTF_8);
    Path copy = tmp.resolve("y.zip");
    Files.copy(archive, copy, StandardCopyOption.REPLACE_EXISTING);
    ObjectMapper mapper = new ObjectMapper();
    ObjectNode sidecar = (ObjectNode) mapper.readTree(Files.readString(sidecarOf(archive)));
    sidecar.put(
        "keystoreFingerprint", "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff");
    Files.writeString(sidecarOf(copy), mapper.writeValueAsString(sidecar), StandardCharsets.UTF_8);
    Cli withKeystore =
        new Cli(
            Map.of(
                "JRS_PASSWORD",
                "jasperadmin",
                "JAVA_TOOL_OPTIONS",
                "-Duser.home=" + userHome.toString().replace("\\", "/")));

    Cli.Result refused = jrsctl(withKeystore, "import", copy.toString(), "--yes").assertExit(2);

    assertThat(refused.stdout())
        .contains("keystore fingerprint mismatch")
        .contains("--source-keystore")
        .contains("nothing changed");
    server.verify(2, postRequestedFor(urlPathEqualTo(WEBAPP + "/rest_v2/export")));
    server.verify(1, postRequestedFor(urlPathEqualTo(WEBAPP + "/rest_v2/import")));
  }
}
