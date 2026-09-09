package com.jaspersoft.jrsctl.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.jaspersoft.jrsctl.app.console.ConsoleOptions;
import com.jaspersoft.jrsctl.app.console.ConsoleRefusedException;
import com.jaspersoft.jrsctl.app.console.ConsoleServer;
import com.jaspersoft.jrsctl.app.console.OperationCatalog;
import com.jaspersoft.jrsctl.app.console.PlanBuilder;
import com.jaspersoft.jrsctl.core.JrsctlHome;
import com.jaspersoft.jrsctl.core.engine.CheckResult;
import com.jaspersoft.jrsctl.core.engine.Context;
import com.jaspersoft.jrsctl.core.engine.Plan;
import com.jaspersoft.jrsctl.core.engine.PlanFingerprint;
import com.jaspersoft.jrsctl.core.engine.PlanSummary;
import com.jaspersoft.jrsctl.core.engine.Step;
import com.jaspersoft.jrsctl.core.engine.StepResult;
import com.jaspersoft.jrsctl.core.event.Event;
import com.jaspersoft.jrsctl.core.event.EventSink;
import com.jaspersoft.jrsctl.core.json.Json;
import com.jaspersoft.jrsctl.core.redact.Redactor;
import com.jaspersoft.jrsctl.core.state.StateStore;
import com.jaspersoft.jrsctl.core.state.StoredPlan;
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
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Spec §14 Phase 6 at the HTTP level: token and Host gate, the README shapes, plan/run/stream,
 * cancel, refusals and the support bundle, against a real listener on a free port.
 */
class ConsoleServerTest {

  private static final String SECRET = "Sup3r-Secret-Value-9f3c";

  @TempDir Path tmp;

  private final HttpClient http = HttpClient.newHttpClient();
  private FakeHotfixOperations fake;
  private Path home;
  private Path bundle;
  private Bootstrap boot;
  private ConsoleServer server;
  private String token;

  @BeforeEach
  void setUp() throws Exception {
    fake = new FakeHotfixOperations();
    HotfixOps.factory = services -> fake;
    TestAdapterFactory.unreachable = false;
    home = Files.createDirectories(tmp.resolve("home"));
    bundle = tmp.resolve("hf.zip");
    Files.writeString(bundle, "zip");
    Files.writeString(
        home.resolve("config.yaml"),
        """
        server:
          baseUrl: http://localhost:8089/jasperserver-pro
          webappName: jasperserver-pro
          installDir: %s
          auth:
            mode: basic
            username: jasperadmin
            passwordRef: env:JRS_PASSWORD
        service:
          kind: manual
        """
            .formatted(tmp.toString().replace("\\", "/")),
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
    HotfixOps.factory = HotfixOps.DEFAULT_FACTORY;
    TestAdapterFactory.unreachable = false;
    Env.reset();
    Redactor.global().clear();
  }

  // ---- fixtures -------------------------------------------------------------------------------

  private Services services(String... extraSet) {
    GlobalOptions global = new GlobalOptions();
    global.home = home;
    global.nonInteractive = true;
    for (String kv : extraSet) {
      String[] parts = kv.split("=", 2);
      global.set.put(parts[0], parts[1]);
    }
    boot = Bootstrap.open(global, Env.vars(), Clock.systemUTC());
    return boot.services();
  }

  private void start(Services services, OperationCatalog catalog) throws IOException {
    server =
        new ConsoleServer(
            services,
            new ConsoleOptions(Optional.empty(), Optional.of(0)),
            new RunService(services),
            catalog);
    server.start();
    token = server.token().orElseThrow().text();
  }

  private void startDefault() throws IOException {
    Services services = services();
    start(services, ConsoleCommand.catalog(services));
  }

  private HttpRequest.Builder request(String path) {
    return HttpRequest.newBuilder(URI.create(server.baseUrl() + path))
        .header("Authorization", "Bearer " + token);
  }

  private HttpResponse<String> get(String path) throws Exception {
    return http.send(request(path).GET().build(), HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> post(String path, String body) throws Exception {
    return http.send(
        request(path)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private static JsonNode json(HttpResponse<String> response) throws Exception {
    return Json.mapper().readTree(response.body());
  }

  private String planId(String op, String args) throws Exception {
    HttpResponse<String> planned =
        post("/api/plan", "{\"op\":\"" + op + "\",\"args\":" + args + "}");
    assertThat(planned.statusCode()).as(planned.body()).isEqualTo(200);
    return json(planned).get("planId").asText();
  }

  private String startRun(String planId) throws Exception {
    HttpResponse<String> started =
        post("/api/run", "{\"planId\":\"" + planId + "\",\"confirm\":true}");
    assertThat(started.statusCode()).as(started.body()).isEqualTo(200);
    return json(started).get("runId").asText();
  }

  /** Reads the SSE stream to its end and returns the event names in order. */
  private List<String> eventNames(String runId) throws Exception {
    HttpResponse<java.util.stream.Stream<String>> stream =
        http.send(
            request("/api/runs/" + runId + "/events")
                .header("Accept", "text/event-stream")
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofLines());
    assertThat(stream.statusCode()).isEqualTo(200);
    assertThat(stream.headers().firstValue("Content-Type").orElse(""))
        .startsWith("text/event-stream");
    List<String> names = new ArrayList<>();
    stream
        .body()
        .forEach(
            line -> {
              if (line.startsWith("event: ")) {
                names.add(line.substring(7).strip());
              }
            });
    return names;
  }

  private String argsFor(Path b) {
    return "{\"bundle\":\"" + b.toString().replace("\\", "\\\\") + "\"}";
  }

  private void waitUntil(java.util.function.BooleanSupplier condition) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (!condition.getAsBoolean()) {
      assertThat(System.nanoTime() < deadline).as("timed out waiting").isTrue();
      Thread.sleep(50);
    }
  }

  // ---- gate -----------------------------------------------------------------------------------

  @Test
  void should_answer_401_with_error_json_when_token_missing() throws Exception {
    startDefault();
    HttpResponse<String> response =
        http.send(
            HttpRequest.newBuilder(URI.create(server.baseUrl() + "/api/health")).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).isEqualTo(401);
    assertThat(json(response).get("error").asText()).isEqualTo("token required");
    assertThat(response.headers().firstValue("Set-Cookie")).isEmpty();
  }

  @Test
  void should_answer_401_when_token_wrong() throws Exception {
    startDefault();
    HttpResponse<String> response =
        http.send(
            HttpRequest.newBuilder(URI.create(server.baseUrl() + "/api/health"))
                .header("Authorization", "Bearer " + token.substring(1) + "x")
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).isEqualTo(401);
  }

  @Test
  void should_answer_421_when_host_header_is_foreign() throws Exception {
    startDefault();
    // java.net.http refuses to send a custom Host header, so speak HTTP/1.1 on a raw socket.
    String raw;
    try (java.net.Socket socket =
        new java.net.Socket(java.net.InetAddress.getLoopbackAddress(), server.port())) {
      socket.setSoTimeout(10_000);
      String request =
          "GET /api/health HTTP/1.1\r\nHost: evil.example:"
              + server.port()
              + "\r\nAuthorization: Bearer "
              + token
              + "\r\nConnection: close\r\n\r\n";
      socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
      socket.getOutputStream().flush();
      raw = new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }
    assertThat(raw).startsWith("HTTP/1.1 421");
    assertThat(raw).contains("\"error\":\"host header rejected\"");
  }

  @Test
  void should_serve_index_and_security_headers_without_token() throws Exception {
    startDefault();
    HttpResponse<String> index =
        http.send(
            HttpRequest.newBuilder(URI.create(server.baseUrl() + "/")).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(index.statusCode()).isEqualTo(200);
    assertThat(index.body()).contains("jrsctl");
    assertThat(index.headers().firstValue("Cache-Control")).contains("no-store");
    assertThat(index.headers().firstValue("Content-Security-Policy").orElse(""))
        .startsWith("default-src 'self'");
    HttpResponse<String> asset =
        http.send(
            HttpRequest.newBuilder(URI.create(server.baseUrl() + "/app.js")).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(asset.statusCode()).isEqualTo(200);
    assertThat(asset.headers().firstValue("Cache-Control")).contains("no-store");
  }

  // ---- shapes ---------------------------------------------------------------------------------

  @Test
  void should_answer_health_with_readme_keys_when_token_given() throws Exception {
    startDefault();
    HttpResponse<String> response = get("/api/health");
    assertThat(response.statusCode()).isEqualTo(200);
    JsonNode health = json(response);
    assertThat(health.get("tool").get("version").asText()).isNotBlank();
    assertThat(health.get("tool").get("matrixVersion").asText()).isNotBlank();
    assertThat(health.get("bind").asText()).isEqualTo("127.0.0.1:" + server.port());
    assertThat(health.get("networkMode").asText()).isEqualTo("isolated");
    assertThat(health.get("lock").get("held").asBoolean()).isFalse();
    assertThat(health.get("pendingRuns").isArray()).isTrue();
    assertThat(health.get("snapshots").get("retentionDays").asInt()).isEqualTo(30);
    assertThat(health.has("lastRun")).isTrue();
  }

  @Test
  void should_answer_server_identity_when_server_reachable() throws Exception {
    startDefault();
    JsonNode s = json(get("/api/server"));
    assertThat(s.get("product").asText()).isEqualTo("JasperReports Server");
    assertThat(s.get("version").asText()).isEqualTo("8.2.0");
    assertThat(s.get("edition").asText()).isEqualTo("PRO");
    assertThat(s.get("tenancy").asText()).isEqualTo("multi-tenant");
    assertThat(s.get("baseUrl").asText()).isEqualTo("http://localhost:8089/jasperserver-pro");
    assertThat(s.get("service").get("kind").asText()).isEqualTo("manual");
    assertThat(s.get("keystore").get("present").asBoolean()).isTrue();
    assertThat(s.get("networkMode").asText()).isEqualTo("isolated");
    assertThat(s.get("reachable").asBoolean()).isTrue();
  }

  @Test
  void should_answer_config_known_fields_with_reachable_false_when_server_unreachable()
      throws Exception {
    TestAdapterFactory.unreachable = true;
    startDefault();
    JsonNode s = json(get("/api/server"));
    assertThat(s.get("reachable").asBoolean()).isFalse();
    assertThat(s.get("baseUrl").asText()).isEqualTo("http://localhost:8089/jasperserver-pro");
    assertThat(s.get("installDir").asText()).isNotBlank();
    assertThat(s.get("version").asText()).isEmpty();
  }

  @Test
  void should_answer_doctor_and_hotfixes_in_readme_shapes() throws Exception {
    startDefault();
    JsonNode doctor = json(get("/api/doctor"));
    assertThat(doctor.get("ranAt").asText()).isNotBlank();
    assertThat(doctor.get("counts").has("pass")).isTrue();
    assertThat(doctor.get("items").size()).isGreaterThan(5);
    JsonNode first = doctor.get("items").get(0);
    assertThat(first.has("id") && first.has("status") && first.has("title") && first.has("detail"))
        .isTrue();
    JsonNode hotfixes = json(get("/api/hotfixes"));
    assertThat(hotfixes.get("hotfixes").isArray()).isTrue();
    JsonNode health = json(get("/api/health"));
    assertThat(health.get("doctor").get("ranAt").asText()).isEqualTo(doctor.get("ranAt").asText());
  }

  // ---- plans ----------------------------------------------------------------------------------

  @Test
  void should_return_plan_id_and_steps_when_planning_hotfix_verify() throws Exception {
    startDefault();
    HttpResponse<String> response =
        post("/api/plan", "{\"op\":\"hotfix.verify\",\"args\":" + argsFor(bundle) + "}");
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    JsonNode body = json(response);
    String planId = body.get("planId").asText();
    assertThat(planId).startsWith("verify-");
    JsonNode plan = body.get("plan");
    assertThat(plan.get("op").asText()).isEqualTo("hotfix.verify");
    assertThat(plan.get("fingerprint").asText()).startsWith("sha256:");
    assertThat(Instant.parse(plan.get("validUntil").asText())).isAfter(Instant.now());
    assertThat(plan.get("summary").get("warnings").isArray()).isTrue();
    JsonNode step = plan.get("steps").get(0);
    assertThat(step.get("id").asText()).isEqualTo("verify-bundle");
    assertThat(step.get("phase").asText()).isEqualTo("verify");
    assertThat(step.has("title") && step.has("why")).isTrue();
    try (StateStore store = StateStore.open(new JrsctlHome(home), Clock.systemUTC())) {
      StoredPlan stored = store.loadPlan(planId).orElseThrow();
      assertThat(stored.operation()).isEqualTo("hotfix.verify");
      assertThat(Duration.between(stored.createdAt(), stored.expiresAt()))
          .isEqualTo(Duration.ofMinutes(30));
    }
  }

  @Test
  void should_return_501_when_operation_not_available_and_400_when_unknown() throws Exception {
    startDefault();
    HttpResponse<String> export = post("/api/plan", "{\"op\":\"upgrade\",\"args\":{}}");
    assertThat(export.statusCode()).isEqualTo(501);
    assertThat(json(export).get("error").asText())
        .isEqualTo("operation not available in this build");
    HttpResponse<String> unknown = post("/api/plan", "{\"op\":\"format-disk\",\"args\":{}}");
    assertThat(unknown.statusCode()).isEqualTo(400);
  }

  // ---- runs -----------------------------------------------------------------------------------

  @Test
  void should_run_hotfix_apply_and_replay_then_stream_events_to_terminal() throws Exception {
    startDefault();
    String planId = planId("hotfix.apply", argsFor(bundle));
    String runId = startRun(planId);
    assertThat(runId).isNotBlank();

    List<String> names = eventNames(runId);
    assertThat(names).contains("StepRunning", "StepSucceeded");
    assertThat(names.get(names.size() - 1)).isEqualTo("RunSucceeded");
    assertThat(fake.executed).containsExactlyElementsOf(FakeHotfixOperations.APPLY_STEPS);

    waitUntil(() -> !server.runs().find(runId).orElseThrow().running());
    JsonNode detail = json(get("/api/runs/" + runId));
    assertThat(detail.get("outcome").asText()).isEqualTo("succeeded");
    assertThat(detail.get("op").asText()).isEqualTo("hotfix.apply");
    assertThat(detail.get("target").asText()).isEqualTo(FakeHotfixOperations.ID);
    assertThat(detail.get("steps")).hasSize(6);
    assertThat(detail.get("steps").get(0).get("status").asText()).isEqualTo("succeeded");
    assertThat(detail.get("steps").get(0).get("durationMs").isNumber()).isTrue();
    assertThat(detail.has("failure")).isFalse();

    // A second connection after the run replays the journal and the stored terminal outcome.
    List<String> replay = eventNames(runId);
    assertThat(replay.get(0)).isEqualTo("StepPending");
    assertThat(replay.get(replay.size() - 1)).isEqualTo("RunSucceeded");

    JsonNode runs = json(get("/api/runs"));
    assertThat(runs.get("runs").get(0).get("id").asText()).isEqualTo(runId);
    assertThat(runs.get("runs").get(0).get("supportBundleAvailable").asBoolean()).isTrue();
    try (StateStore store = StateStore.open(new JrsctlHome(home), Clock.systemUTC())) {
      assertThat(store.loadPlan(planId).orElseThrow().consumedByRunId()).contains(runId);
      assertThat(store.auditRows(20)).anyMatch(a -> a.action().equals("console.run.start"));
    }
  }

  @Test
  void should_refuse_run_with_409_when_fingerprint_differs_and_410_when_expired() throws Exception {
    startDefault();
    Instant now = Instant.now();
    Plan plan =
        fake.planApply(
            bundle, new com.jaspersoft.jrsctl.ops.hotfix.HotfixOperations.ApplyOptions(false));
    try (StateStore store = StateStore.open(new JrsctlHome(home), Clock.systemUTC())) {
      store.savePlan(
          new StoredPlan(
              "p-changed",
              PlanRegistry.HOTFIX_APPLY,
              PlanRegistry.applyArgs(bundle, false),
              PlanPrinter.toJson(plan),
              "sha256:someone-else",
              now,
              now.plus(Duration.ofMinutes(30)),
              Optional.empty()));
      store.savePlan(
          new StoredPlan(
              "p-expired",
              PlanRegistry.HOTFIX_APPLY,
              PlanRegistry.applyArgs(bundle, false),
              PlanPrinter.toJson(plan),
              plan.fingerprint().value(),
              now.minus(Duration.ofHours(1)),
              now.minus(Duration.ofMinutes(1)),
              Optional.empty()));
    }
    HttpResponse<String> changed = post("/api/run", "{\"planId\":\"p-changed\",\"confirm\":true}");
    assertThat(changed.statusCode()).isEqualTo(409);
    assertThat(json(changed).get("error").asText()).contains("fingerprint");
    HttpResponse<String> expired = post("/api/run", "{\"planId\":\"p-expired\",\"confirm\":true}");
    assertThat(expired.statusCode()).isEqualTo(410);
    HttpResponse<String> unconfirmed = post("/api/run", "{\"planId\":\"p-changed\"}");
    assertThat(unconfirmed.statusCode()).isEqualTo(400);
    assertThat(fake.executed).isEmpty();
  }

  @Test
  void should_cancel_a_live_run_and_end_the_stream_with_run_cancelled() throws Exception {
    BlockingPlans plans = new BlockingPlans();
    Services services = services();
    start(services, new OperationCatalog(plans, () -> fake));
    String planId = planId("hotfix.apply", "{\"block\":true}");
    String runId = startRun(planId);
    waitUntil(() -> plans.blocking);

    JsonNode running = json(get("/api/runs/" + runId));
    assertThat(running.get("outcome").asText()).isEqualTo("running");
    assertThat(running.get("steps").get(1).get("status").asText()).isEqualTo("running");

    HttpResponse<String> cancel = post("/api/runs/" + runId + "/cancel", "");
    assertThat(cancel.statusCode()).as(cancel.body()).isEqualTo(200);
    List<String> names = eventNames(runId);
    assertThat(names.get(names.size() - 1)).isEqualTo("RunCancelled");
    assertThat(plans.compensated).contains("quick", "slow");
    waitUntil(() -> !server.runs().find(runId).orElseThrow().running());
    JsonNode detail = json(get("/api/runs/" + runId));
    assertThat(detail.get("outcome").asText()).isEqualTo("cancelled");
    assertThat(detail.get("failure").get("cause").asText()).contains("cancelled");
    HttpResponse<String> again = post("/api/runs/" + runId + "/cancel", "");
    assertThat(again.statusCode()).isEqualTo(409);
  }

  @Test
  void should_start_a_rollback_run_when_the_run_installed_a_hotfix_still_in_place()
      throws Exception {
    startDefault();
    String applyRun = startRun(planId("hotfix.apply", argsFor(bundle)));
    waitUntil(() -> !server.runs().find(applyRun).orElseThrow().running());
    assertThat(json(get("/api/runs/" + applyRun)).get("rollbackAvailable").asBoolean()).isFalse();
    try (StateStore store = StateStore.open(new JrsctlHome(home), Clock.systemUTC())) {
      store.recordHotfixInstalled(
          new com.jaspersoft.jrsctl.core.state.HotfixInstalled(
              FakeHotfixOperations.ID,
              "1",
              FakeHotfixOperations.TITLE,
              applyRun,
              Optional.empty(),
              com.jaspersoft.jrsctl.core.state.HotfixState.INSTALLED,
              Instant.now()),
          List.of());
    }
    assertThat(json(get("/api/runs/" + applyRun)).get("rollbackAvailable").asBoolean()).isTrue();
    JsonNode hotfixes = json(get("/api/hotfixes"));
    assertThat(hotfixes.get("hotfixes").get(0).get("state").asText()).isEqualTo("installed");
    assertThat(hotfixes.get("hotfixes").get(0).get("blockedBy").isArray()).isTrue();

    HttpResponse<String> rollback = post("/api/runs/" + applyRun + "/rollback", "");
    assertThat(rollback.statusCode()).as(rollback.body()).isEqualTo(200);
    String rollbackRun = json(rollback).get("runId").asText();
    assertThat(rollbackRun).isNotEqualTo(applyRun);
    List<String> names = eventNames(rollbackRun);
    assertThat(names.get(names.size() - 1)).isEqualTo("RunSucceeded");
    assertThat(fake.lastRollbackId).contains(FakeHotfixOperations.ID);
    assertThat(fake.executed).contains("restore-snapshot", "record-rolled-back");
    waitUntil(() -> !server.runs().find(rollbackRun).orElseThrow().running());
    assertThat(json(get("/api/runs/" + rollbackRun)).get("op").asText())
        .isEqualTo("hotfix.rollback");
  }

  @Test
  void should_resume_an_interrupted_run_from_its_interrupted_step() throws Exception {
    startDefault();
    Plan plan =
        fake.planApply(
            bundle, new com.jaspersoft.jrsctl.ops.hotfix.HotfixOperations.ApplyOptions(false));
    Instant t0 = Instant.now().minus(Duration.ofMinutes(5));
    try (StateStore store = StateStore.open(new JrsctlHome(home), Clock.systemUTC())) {
      store.savePlan(
          new StoredPlan(
              "p-pending",
              PlanRegistry.HOTFIX_APPLY,
              PlanRegistry.applyArgs(bundle, false),
              PlanPrinter.toJson(plan),
              plan.fingerprint().value(),
              t0,
              t0.plus(Duration.ofMinutes(30)),
              Optional.of("r-pending")));
      store.recordRunStart("r-pending", "hotfix.apply", Optional.of("p-pending"), t0);
      store.appendTransition(
          "r-pending", "verify-signature", "verify", Optional.empty(), "PENDING", Optional.empty());
      store.appendTransition(
          "r-pending",
          "verify-signature",
          "verify",
          Optional.of("PENDING"),
          "RUNNING",
          Optional.empty());
      store.appendTransition(
          "r-pending",
          "verify-signature",
          "verify",
          Optional.of("RUNNING"),
          "SUCCEEDED",
          Optional.empty());
      store.appendTransition(
          "r-pending",
          "validate-manifest",
          "verify",
          Optional.empty(),
          "PENDING",
          Optional.empty());
      store.appendTransition(
          "r-pending",
          "validate-manifest",
          "verify",
          Optional.of("PENDING"),
          "RUNNING",
          Optional.empty());
    }
    JsonNode health = json(get("/api/health"));
    assertThat(health.get("pendingRuns").get(0).get("id").asText()).isEqualTo("r-pending");
    assertThat(health.get("pendingRuns").get(0).get("stepId").asText())
        .isEqualTo("validate-manifest");
    JsonNode before = json(get("/api/runs/r-pending"));
    assertThat(before.get("outcome").asText()).isEqualTo("interrupted");
    assertThat(before.get("rollbackAvailable").asBoolean()).isTrue();
    assertThat(before.get("steps").get(0).get("status").asText()).isEqualTo("succeeded");
    assertThat(before.get("steps").get(1).get("status").asText()).isEqualTo("running");
    HttpResponse<String> blocked = post("/api/run", "{\"planId\":\"p-pending\",\"confirm\":true}");
    assertThat(blocked.statusCode()).isEqualTo(409);

    HttpResponse<String> resumed = post("/api/runs/r-pending/resume", "");
    assertThat(resumed.statusCode()).as(resumed.body()).isEqualTo(200);
    assertThat(json(resumed).get("runId").asText()).isEqualTo("r-pending");
    List<String> names = eventNames("r-pending");
    assertThat(names.get(names.size() - 1)).isEqualTo("RunSucceeded");
    assertThat(fake.executed)
        .doesNotContain("verify-signature")
        .contains("validate-manifest", "record-installed");
    waitUntil(() -> !server.runs().find("r-pending").orElseThrow().running());
    assertThat(json(get("/api/runs/r-pending")).get("outcome").asText()).isEqualTo("succeeded");
    HttpResponse<String> again = post("/api/runs/r-pending/resume", "");
    assertThat(again.statusCode()).isEqualTo(409);
  }

  // ---- support bundle -------------------------------------------------------------------------

  @Test
  void should_write_support_bundle_with_expected_entries_and_no_registered_secret()
      throws Exception {
    Redactor.global().register(SECRET);
    BlockingPlans plans = new BlockingPlans();
    Services services = services();
    start(services, new OperationCatalog(plans, () -> fake));
    Files.createDirectories(home.resolve("logs"));
    Files.writeString(
        home.resolve("logs").resolve("jrsctl.log"),
        "{\"message\":\"login with password=" + SECRET + "\"}\n",
        StandardCharsets.UTF_8);
    String runId = startRun(planId("hotfix.apply", "{\"block\":false}"));
    waitUntil(() -> !server.runs().find(runId).orElseThrow().running());

    HttpResponse<byte[]> zip =
        http.send(
            request("/api/runs/" + runId + "/support-bundle").GET().build(),
            HttpResponse.BodyHandlers.ofByteArray());
    assertThat(zip.statusCode()).isEqualTo(200);
    assertThat(zip.headers().firstValue("Content-Type").orElse("")).startsWith("application/zip");
    List<String> entries = new ArrayList<>();
    StringBuilder everything = new StringBuilder();
    try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip.body()))) {
      ZipEntry entry;
      while ((entry = in.getNextEntry()) != null) {
        entries.add(entry.getName());
        everything.append(new String(in.readAllBytes(), StandardCharsets.UTF_8));
      }
    }
    assertThat(entries)
        .contains(
            "run.json",
            "plan.json",
            "transitions.jsonl",
            "events.jsonl",
            "server.json",
            "doctor.json",
            "config-redacted.yaml",
            "logs/jrsctl.log");
    String all = everything.toString();
    assertThat(all).contains("[redacted]");
    assertThat(all).doesNotContain(SECRET);
    assertThat(all)
        .doesNotContain(
            Base64.getEncoder().encodeToString(SECRET.getBytes(StandardCharsets.UTF_8)));
    assertThat(all).doesNotContain(token);
  }

  // ---- refusals and token file ----------------------------------------------------------------

  @Test
  void should_refuse_non_loopback_bind_when_tls_disabled() {
    Services services = services();
    ConsoleServer refused =
        new ConsoleServer(
            services,
            new ConsoleOptions(Optional.of("0.0.0.0"), Optional.of(0)),
            new RunService(services),
            ConsoleCommand.catalog(services));
    assertThatThrownBy(refused::start)
        .isInstanceOf(ConsoleRefusedException.class)
        .hasMessageContaining("console.tls.enabled")
        .hasMessageContaining("console.auth.mode");
    assertThat(home.resolve("console.token")).doesNotExist();
    refused.close();
  }

  @Test
  void should_write_owner_only_token_file_and_delete_it_on_close() throws Exception {
    startDefault();
    Path file = home.resolve("console.token");
    assertThat(file).exists();
    assertThat(Files.readString(file, StandardCharsets.UTF_8).strip()).isEqualTo(token);
    assertThat(boot.services().platform().files().isOwnerOnly(file)).isTrue();
    assertThat(token).hasSizeGreaterThanOrEqualTo(43);
    assertThat(Redactor.global().redact("Console: #token=" + token)).doesNotContain(token);
    assertThat(server.url()).isEqualTo(server.baseUrl() + "/#token=" + token);
    server.close();
    assertThat(file).doesNotExist();
    server.close();
  }

  @Test
  void should_serve_over_tls_when_pem_material_configured() throws Exception {
    Path keystore = tmp.resolve("console.p12");
    boolean windows =
        System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
    Process keytool =
        new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", windows ? "keytool.exe" : "keytool")
                    .toString(),
                "-genkeypair",
                "-alias",
                "console",
                "-keyalg",
                "RSA",
                "-keysize",
                "2048",
                "-dname",
                "CN=localhost",
                "-ext",
                "SAN=ip:127.0.0.1,dns:localhost",
                "-validity",
                "2",
                "-keystore",
                keystore.toString(),
                "-storetype",
                "PKCS12",
                "-storepass",
                "changeit")
            .redirectErrorStream(true)
            .start();
    keytool.getInputStream().readAllBytes();
    assertThat(keytool.waitFor(60, TimeUnit.SECONDS)).isTrue();
    assertThat(keytool.exitValue()).isZero();
    KeyStore ks = KeyStore.getInstance("PKCS12");
    try (var in = Files.newInputStream(keystore)) {
      ks.load(in, "changeit".toCharArray());
    }
    PrivateKey key = (PrivateKey) ks.getKey("console", "changeit".toCharArray());
    Certificate cert = ks.getCertificate("console");
    Path certPem = tmp.resolve("console.crt");
    Path keyPem = tmp.resolve("console.key");
    Files.writeString(certPem, pem("CERTIFICATE", cert.getEncoded()), StandardCharsets.US_ASCII);
    Files.writeString(keyPem, pem("PRIVATE KEY", key.getEncoded()), StandardCharsets.US_ASCII);

    Services services =
        services(
            "console.tls.enabled=true",
            "console.tls.certPath=" + certPem.toString().replace("\\", "/"),
            "console.tls.keyPath=" + keyPem.toString().replace("\\", "/"));
    start(services, ConsoleCommand.catalog(services));
    assertThat(server.tls()).isTrue();
    assertThat(server.baseUrl()).startsWith("https://127.0.0.1:");

    SSLContext trustAll = SSLContext.getInstance("TLS");
    trustAll.init(null, new TrustManager[] {new TrustEverything()}, new SecureRandom());
    HttpClient tls = HttpClient.newBuilder().sslContext(trustAll).build();
    HttpResponse<String> response =
        tls.send(
            HttpRequest.newBuilder(URI.create(server.baseUrl() + "/api/health"))
                .header("Authorization", "Bearer " + token)
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(json(response).get("tool").get("version").asText()).isNotBlank();
  }

  private static String pem(String type, byte[] der) {
    String body =
        Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(der);
    return "-----BEGIN " + type + "-----\n" + body + "\n-----END " + type + "-----\n";
  }

  /** Trusts the test's self-signed certificate; never used outside this test. */
  private static final class TrustEverything implements X509TrustManager {
    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType) {}

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType) {}

    @Override
    public X509Certificate[] getAcceptedIssuers() {
      return new X509Certificate[0];
    }
  }

  /**
   * A plan builder whose second step blocks until cancelled (when {@code args.block} is true) and
   * logs a registered secret, so cancellation and redaction can be observed over HTTP.
   */
  private static final class BlockingPlans implements PlanBuilder {

    volatile boolean blocking;
    final List<String> compensated = new java.util.concurrent.CopyOnWriteArrayList<>();

    @Override
    public Plan build(String operation, String argsJson) {
      if (!operation.equals(OperationCatalog.HOTFIX_APPLY)) {
        throw new UnsupportedOperationException("unknown operation " + operation);
      }
      boolean block = argsJson.contains("\"block\":true");
      List<Step> steps = List.of(step("quick", false), step("slow", block));
      PlanSummary summary =
          new PlanSummary(
              OperationCatalog.HOTFIX_APPLY,
              "JRS-TEST",
              List.of(Path.of("a.jar")),
              List.of(),
              false,
              List.of(),
              Map.of(),
              "test",
              List.of());
      return new Plan(
          "p-" + UUID.randomUUID(), steps, summary, PlanFingerprint.of(Map.of("args", argsJson)));
    }

    private Step step(String id, boolean block) {
      return new Step() {
        @Override
        public String id() {
          return id;
        }

        @Override
        public String title() {
          return "Step " + id;
        }

        @Override
        public String phase() {
          return "apply";
        }

        @Override
        public CheckResult precheck(Context ctx) {
          return CheckResult.pass();
        }

        @Override
        public StepResult execute(Context ctx, EventSink out) {
          out.emit(
              new Event.Log(
                  Instant.now(),
                  ctx.runId(),
                  Optional.of(id),
                  "apply",
                  Event.Log.Level.INFO,
                  "connecting with password=" + SECRET));
          if (block) {
            blocking = true;
            while (true) {
              ctx.cancel().checkpoint();
              try {
                Thread.sleep(20);
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return StepResult.ok();
              }
            }
          }
          return StepResult.ok();
        }

        @Override
        public StepResult compensate(Context ctx, EventSink out) {
          compensated.add(id);
          return StepResult.ok();
        }
      };
    }
  }
}
