package com.jaspersoft.jrsctl.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.jaspersoft.jrsctl.app.console.ConsoleOptions;
import com.jaspersoft.jrsctl.app.console.ConsoleServer;
import com.jaspersoft.jrsctl.core.redact.Redactor;
import com.jaspersoft.jrsctl.core.state.AuditEntry;
import com.jaspersoft.jrsctl.ops.RunService;
import com.jaspersoft.jrsctl.ops.Services;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Review findings 4.1, 4.7, 4.8 and 4.9 at the HTTP level: the launch code is short-lived, single
 * and audited; an unknown run is refused before the event stream is committed; the support bundle
 * reads the log the process is actually writing and caps the event tail; and a closing console
 * accepts no new run.
 */
class ConsoleHardeningTest {

  @TempDir Path tmp;

  private final HttpClient http = HttpClient.newHttpClient();
  private Path home;
  private Bootstrap boot;
  private ConsoleServer server;
  private String token;
  private String savedLogFile;

  @BeforeEach
  void setUp() throws IOException {
    HotfixOps.factory = services -> new FakeHotfixOperations();
    TestAdapterFactory.unreachable = false;
    savedLogFile = System.getProperty(LogFile.PROPERTY);
    home = Files.createDirectories(tmp.resolve("home"));
    Files.writeString(
        home.resolve("config.yaml"),
        """
        server:
          baseUrl: http://localhost:8089/jasperserver-pro
          webappName: jasperserver-pro
          auth:
            mode: basic
            username: jasperadmin
            passwordRef: env:JRS_PASSWORD
        service:
          kind: manual
        """,
        StandardCharsets.UTF_8);
    Env.override(Map.of("JRS_PASSWORD", "jasperadmin"));
  }

  @AfterEach
  void tearDown() {
    if (server != null) {
      server.close();
    }
    if (boot != null) {
      boot.close();
    }
    if (savedLogFile == null) {
      System.clearProperty(LogFile.PROPERTY);
    } else {
      System.setProperty(LogFile.PROPERTY, savedLogFile);
    }
    HotfixOps.factory = HotfixOps.DEFAULT_FACTORY;
    Env.reset();
    Redactor.global().clear();
  }

  // ---- 4.1 launch code ------------------------------------------------------------------------

  @Test
  void should_keep_the_launch_code_worth_seconds_not_half_a_minute() {
    assertThat(ConsoleServer.LAUNCH_CODE_TTL)
        .as("the code sits in a browser command line any local account can read")
        .isLessThanOrEqualTo(Duration.ofSeconds(10));
  }

  @Test
  void should_invalidate_the_previous_code_when_a_second_is_issued() throws Exception {
    start();
    String first = codeOf(server.launchUrl());
    String second = codeOf(server.launchUrl());

    assertThat(exchange(first).statusCode()).as("the superseded code").isEqualTo(401);
    assertThat(exchange(second).statusCode()).isEqualTo(200);
  }

  @Test
  void should_audit_a_refused_launch_exchange() throws Exception {
    start();
    String code = codeOf(server.launchUrl());

    assertThat(exchange("not-a-code").statusCode()).isEqualTo(401);
    assertThat(exchange(code).statusCode())
        .as("a bad guess must not cancel the browser's own code")
        .isEqualTo(200);

    List<AuditEntry> audit = boot.services().stateStore().get().auditRows(50);
    assertThat(audit).anyMatch(a -> a.action().equals("console.launch.refused"));
    assertThat(audit).anyMatch(a -> a.action().equals("console.launch.exchanged"));
    assertThat(audit).noneMatch(a -> a.detail().orElse("").contains(code));
  }

  // ---- 4.7 event stream -----------------------------------------------------------------------

  @Test
  void should_refuse_an_unknown_run_with_a_document_rather_than_an_empty_event_stream()
      throws Exception {
    start();

    HttpResponse<String> response =
        http.send(
            HttpRequest.newBuilder(URI.create(server.baseUrl() + "/api/runs/r-nope/events"))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "text/event-stream")
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode())
        .as("an empty 200 makes the browser reconnect for ever")
        .isEqualTo(404);
    assertThat(response.headers().firstValue("Content-Type").orElse(""))
        .as("the stream must not have been committed")
        .doesNotContain("text/event-stream");
    assertThat(response.body()).as("body").contains("unknown run");
  }

  // ---- 4.8 support bundle ---------------------------------------------------------------------

  @Test
  void should_read_the_log_the_process_is_writing_and_cap_the_event_tail() throws Exception {
    Path log = tmp.resolve("elsewhere").resolve("jrsctl.log");
    Files.createDirectories(log.getParent());
    Files.writeString(log, "{\"message\":\"from the configured log\"}\n", StandardCharsets.UTF_8);
    System.setProperty(LogFile.PROPERTY, log.toString());
    Files.writeString(home.resolve("logs-decoy.txt"), "not this one\n", StandardCharsets.UTF_8);
    start();
    String runId = journalledRun();

    Map<String, String> entries = bundleEntries(runId);

    assertThat(entries).containsKey("logs/jrsctl.log");
    assertThat(entries.get("logs/jrsctl.log")).contains("from the configured log");
    assertThat(entries.get("events.jsonl")).contains("omitted; this is the last");
    assertThat(entries.get("events.jsonl").lines().count()).isLessThan(25_000);
  }

  // ---- 4.9 shutdown ---------------------------------------------------------------------------

  @Test
  void should_accept_no_new_run_once_the_console_is_closing() throws Exception {
    start();
    assertThat(server.runs().closing()).isFalse();

    server.runs().shutdown();

    assertThat(server.runs().closing()).isTrue();
    assertThat(server.runs().start(null, "r-late", null))
        .isInstanceOfSatisfying(
            com.jaspersoft.jrsctl.app.console.RunManager.Refused.class,
            r -> assertThat(r.reason()).contains("shutting down"));
  }

  // ---- fixtures -------------------------------------------------------------------------------

  private void start() throws IOException {
    GlobalOptions global = new GlobalOptions();
    global.home = home;
    global.nonInteractive = true;
    boot = Bootstrap.open(global, Env.vars(), Clock.systemUTC());
    Services services = boot.services();
    server =
        new ConsoleServer(
            services,
            new ConsoleOptions(Optional.empty(), Optional.of(0)),
            new RunService(services),
            ConsoleCommand.catalog(services));
    server.start();
    token = server.token().orElseThrow().text();
  }

  /** A journalled run with an oversized {@code events.jsonl} beside it. */
  private String journalledRun() throws IOException {
    String runId = "r-bundle";
    boot.services()
        .stateStore()
        .get()
        .recordRunStart(runId, "hotfix.apply", Optional.empty(), java.time.Instant.now());
    Path events = boot.services().home().runDir(runId).resolve("events.jsonl");
    Files.createDirectories(events.getParent());
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < 25_000; i++) {
      sb.append("{\"type\":\"Log\",\"n\":").append(i).append("}\n");
    }
    Files.writeString(events, sb.toString(), StandardCharsets.UTF_8);
    return runId;
  }

  private Map<String, String> bundleEntries(String runId) throws Exception {
    HttpResponse<byte[]> zip =
        http.send(
            HttpRequest.newBuilder(
                    URI.create(server.baseUrl() + "/api/runs/" + runId + "/support-bundle"))
                .header("Authorization", "Bearer " + token)
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofByteArray());
    assertThat(zip.statusCode()).isEqualTo(200);
    Map<String, String> entries = new LinkedHashMap<>();
    try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip.body()))) {
      ZipEntry entry;
      while ((entry = in.getNextEntry()) != null) {
        entries.put(entry.getName(), new String(in.readAllBytes(), StandardCharsets.UTF_8));
      }
    }
    return entries;
  }

  private static String codeOf(String launchUrl) {
    return launchUrl.substring(launchUrl.indexOf("#launch=") + 8);
  }

  private HttpResponse<String> exchange(String code) throws Exception {
    return http.send(
        HttpRequest.newBuilder(URI.create(server.baseUrl() + "/api/auth/launch"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("{\"code\":\"" + code + "\"}"))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }
}
