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
import java.io.IOException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Spec §14 Phase 6, exercised through the packaged jar: {@code jrsctl console} is started as a
 * background process against a WireMock JasperReports Server 8.2.0 PRO, its per-launch token
 * appears in {@code console.token}, the UI and the API answer as {@code web/README.md} documents
 * (401 without the token, 200 with it), and a graceful stop deletes the token file.
 */
@Tag("phase6")
class Phase6ConsoleTest {

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

  private static WireMockServer server;

  @TempDir Path tmp;

  private final HttpClient http = HttpClient.newHttpClient();

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
  }

  @AfterAll
  static void stopServer() {
    if (server != null) {
      server.stop();
    }
  }

  private static Path fakeLayout(Path install) throws IOException {
    Path tomcat = install.resolve("apache-tomcat");
    Path webapp = tomcat.resolve("webapps").resolve("jasperserver-pro");
    Files.createDirectories(webapp.resolve("WEB-INF").resolve("lib"));
    Files.createDirectories(webapp.resolve("WEB-INF").resolve("classes"));
    Files.createDirectories(tomcat.resolve("conf"));
    Files.writeString(
        tomcat.resolve("conf").resolve("server.xml"),
        "<Server><Service><Connector port=\"8080\" protocol=\"HTTP/1.1\"/></Service></Server>",
        StandardCharsets.UTF_8);
    Files.createDirectories(install.resolve("buildomatic"));
    return install;
  }

  private Path home(Path install) throws IOException {
    Path home = Files.createDirectories(tmp.resolve("home"));
    Files.writeString(
        home.resolve("config.yaml"),
        """
        server:
          baseUrl: http://localhost:%d%s
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
            .formatted(server.port(), WEBAPP, install.toString().replace("\\", "/")),
        StandardCharsets.UTF_8);
    return home;
  }

  private static int freePort() throws IOException {
    try (ServerSocket socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    }
  }

  private HttpResponse<String> httpGet(String url, String token) throws Exception {
    HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url)).GET();
    if (token != null) {
      request.header("Authorization", "Bearer " + token);
    }
    return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  private static JsonNode json(HttpResponse<String> response) throws Exception {
    return new ObjectMapper().readTree(response.body());
  }

  @Test
  void console_serves_ui_and_api_behind_token_and_deletes_token_on_stop() throws Exception {
    Path home = home(fakeLayout(tmp.resolve("jrs")));
    int port = freePort();
    Path stdout = tmp.resolve("console.out");
    Path stderr = tmp.resolve("console.err");
    List<String> command =
        List.of(
            System.getProperty("jrsctl.java"),
            "-jar",
            System.getProperty("jrsctl.jar"),
            "console",
            "--home",
            home.toString(),
            "--port",
            Integer.toString(port),
            "--no-open");
    ProcessBuilder pb = new ProcessBuilder(command);
    pb.environment().put("JRS_PASSWORD", "jasperadmin");
    pb.redirectOutput(stdout.toFile());
    pb.redirectError(stderr.toFile());
    Process console = pb.start();
    Path tokenFile = home.resolve("console.token");
    try {
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90);
      while (!Files.exists(tokenFile) || Files.size(tokenFile) == 0) {
        assertThat(console.isAlive())
            .as("console exited early\nstdout:\n%s\nstderr:\n%s", read(stdout), read(stderr))
            .isTrue();
        assertThat(System.nanoTime() < deadline).as("token file did not appear").isTrue();
        Thread.sleep(200);
      }
      String token = Files.readString(tokenFile, StandardCharsets.UTF_8).strip();
      assertThat(token).hasSizeGreaterThanOrEqualTo(43);
      String base = "http://127.0.0.1:" + port;
      waitUntilListening(base + "/", stdout, stderr, console);

      HttpResponse<String> index = httpGet(base + "/", null);
      assertThat(index.statusCode()).isEqualTo(200);
      assertThat(index.headers().firstValue("Content-Type").orElse("")).contains("text/html");
      assertThat(index.body()).contains("jrsctl");

      assertThat(httpGet(base + "/api/health", null).statusCode()).isEqualTo(401);

      HttpResponse<String> health = httpGet(base + "/api/health", token);
      assertThat(health.statusCode()).as(health.body()).isEqualTo(200);
      assertThat(json(health).get("tool").get("version").asText())
          .isEqualTo(System.getProperty("jrsctl.version"));
      assertThat(json(health).get("bind").asText()).isEqualTo("127.0.0.1:" + port);

      HttpResponse<String> identity = httpGet(base + "/api/server", token);
      assertThat(identity.statusCode()).as(identity.body()).isEqualTo(200);
      assertThat(json(identity).get("version").asText()).isEqualTo("8.2.0");
      assertThat(json(identity).get("edition").asText()).isEqualTo("PRO");

      HttpResponse<String> doctor = httpGet(base + "/api/doctor", token);
      assertThat(doctor.statusCode()).as(doctor.body()).isEqualTo(200);
      assertThat(json(doctor).get("items").size()).isGreaterThan(0);
      assertThat(json(doctor).get("counts").has("pass")).isTrue();

      HttpResponse<String> runs = httpGet(base + "/api/runs", token);
      assertThat(runs.statusCode()).isEqualTo(200);
      assertThat(json(runs).get("runs")).isEmpty();

      String printed = read(stdout);
      assertThat(printed).contains("Console: http://127.0.0.1:" + port + "/#token=" + token);
    } finally {
      stop(console);
    }
    assertThat(console.exitValue())
        .as("stdout:\n%s\nstderr:\n%s", read(stdout), read(stderr))
        .isZero();
    assertThat(tokenFile).as("token file is deleted on shutdown").doesNotExist();
  }

  private void waitUntilListening(String url, Path stdout, Path stderr, Process console)
      throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
    while (true) {
      try {
        httpGet(url, null);
        return;
      } catch (IOException notYet) {
        assertThat(console.isAlive())
            .as("console exited early\nstdout:\n%s\nstderr:\n%s", read(stdout), read(stderr))
            .isTrue();
        assertThat(System.nanoTime() < deadline).as("console did not start listening").isTrue();
        Thread.sleep(200);
      }
    }
  }

  /** Asks the console to stop over its standard input; destroys it if it does not comply. */
  private static void stop(Process console) throws Exception {
    try (OutputStream in = console.getOutputStream()) {
      in.write("stop\n".getBytes(StandardCharsets.UTF_8));
      in.flush();
    } catch (IOException alreadyGone) {
      // the process may have exited already
    }
    if (!console.waitFor(60, TimeUnit.SECONDS)) {
      console.destroyForcibly();
      console.waitFor(30, TimeUnit.SECONDS);
    }
  }

  private static String read(Path file) throws IOException {
    return Files.exists(file) ? Files.readString(file, StandardCharsets.UTF_8) : "";
  }
}
