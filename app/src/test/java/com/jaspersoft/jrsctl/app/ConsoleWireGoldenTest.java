package com.jaspersoft.jrsctl.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.jaspersoft.jrsctl.app.console.OperationCatalog;
import com.jaspersoft.jrsctl.core.json.Json;
import com.jaspersoft.jrsctl.core.state.HotfixFile;
import com.jaspersoft.jrsctl.core.state.HotfixInstalled;
import com.jaspersoft.jrsctl.core.state.HotfixState;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Freezes the JSON of every console endpoint built from the current hand-assembled maps, before the
 * record migration. Invariants: a value-exact golden is captured from the code as it stood before
 * that migration and is never edited by hand afterwards, so a difference is a change to the
 * published wire format and fails the build; {@code doctor} and the doctor-cached branch of {@code
 * health} probe the real machine and would be flaky value-exact, so they are captured in shape mode
 * instead, which still guards key names, key order, nesting and the null-versus-absent distinction.
 * Run with {@code -Dconsole.golden.update=true} to write a missing golden, then read it before
 * committing.
 */
class ConsoleWireGoldenTest {

  private static final String DIR = "src/test/resources/console-wire/";

  @TempDir Path tmp;

  @Test
  void should_match_the_golden_when_the_console_is_fresh() throws Exception {
    Path home = tmp.resolve("home");
    TestAdapterFactory.unreachable = true;
    try (ConsoleFixture console = ConsoleFixture.start(home, Clock.systemUTC())) {
      assertGolden("health-fresh", console.get("/api/health"), home);
      assertGolden("server-unreachable", console.get("/api/server"), home);
      assertShapeGolden("doctor", console.get("/api/doctor"));
      assertShapeGolden("health-doctor-cached", console.get("/api/health"));
      assertGolden("hotfixes-empty", console.get("/api/hotfixes"), home);
    } finally {
      TestAdapterFactory.unreachable = false;
    }
  }

  @Test
  void should_match_the_golden_when_a_hotfix_run_succeeds() throws Exception {
    Path home = tmp.resolve("home");
    Path bundle = tmp.resolve("hf.zip");
    Files.writeString(bundle, "zip");
    FakeHotfixOperations fake = new FakeHotfixOperations();
    HotfixOps.factory = services -> fake;
    try (ConsoleFixture console = ConsoleFixture.start(home, Clock.systemUTC())) {
      HttpResponse<String> planned =
          console.post(
              "/api/plan",
              "{\"op\":\"hotfix.apply\",\"args\":{\"bundle\":\""
                  + bundle.toString().replace("\\", "\\\\")
                  + "\"}}");
      assertGolden("plan-hotfix-apply", planned, home);
      String planId = Json.mapper().readTree(planned.body()).get("planId").asText();

      HttpResponse<String> started =
          console.post("/api/run", "{\"planId\":\"" + planId + "\",\"confirm\":true}");
      assertGolden("run-started", started, home);
      String runId = Json.mapper().readTree(started.body()).get("runId").asText();
      waitForTerminal(console, runId);

      assertGolden("runs-after-apply", console.get("/api/runs"), home);
      assertGolden("runs-show-succeeded", console.get("/api/runs/" + runId), home);
      assertGolden("health-after-apply", console.get("/api/health"), home);
    } finally {
      HotfixOps.factory = HotfixOps.DEFAULT_FACTORY;
    }
  }

  @Test
  void should_match_the_golden_when_a_hotfix_run_fails() throws Exception {
    Path home = tmp.resolve("home");
    Path bundle = tmp.resolve("hf.zip");
    Files.writeString(bundle, "zip");
    FakeHotfixOperations fake = new FakeHotfixOperations();
    fake.failStep = Optional.of("atomic-swap");
    HotfixOps.factory = services -> fake;
    try (ConsoleFixture console = ConsoleFixture.start(home, Clock.systemUTC())) {
      HttpResponse<String> planned =
          console.post(
              "/api/plan",
              "{\"op\":\"hotfix.apply\",\"args\":{\"bundle\":\""
                  + bundle.toString().replace("\\", "\\\\")
                  + "\"}}");
      String planId = Json.mapper().readTree(planned.body()).get("planId").asText();
      HttpResponse<String> started =
          console.post("/api/run", "{\"planId\":\"" + planId + "\",\"confirm\":true}");
      String runId = Json.mapper().readTree(started.body()).get("runId").asText();
      waitForTerminal(console, runId);
      assertGolden("runs-show-failed", console.get("/api/runs/" + runId), home);
    } finally {
      HotfixOps.factory = HotfixOps.DEFAULT_FACTORY;
    }
  }

  @Test
  void should_match_the_golden_when_hotfixes_are_installed() throws Exception {
    Path home = tmp.resolve("home");
    try (ConsoleFixture console = ConsoleFixture.start(home, Clock.systemUTC())) {
      Path sharedFile = Path.of("webapps/jasperserver-pro/scripts/jrsctl-fix.js");
      Path otherFile = Path.of("webapps/jasperserver-pro/scripts/jrsctl-old-fix.js");
      // Recorded in the console's own state store (ConsoleFixture#store), not a second store
      // opened on the same file, so this is exactly what the running console will read back.
      // installed_run_id has no foreign key onto the runs table (V001__init.sql), so a synthetic
      // run id needs no run row seeded first.
      console
          .store()
          .recordHotfixInstalled(
              new HotfixInstalled(
                  "JRS-8.2.0-HF-0001",
                  "1",
                  "Fake scheduler fix",
                  "r-seed-hf-0001",
                  Optional.empty(),
                  HotfixState.INSTALLED,
                  Instant.parse("2026-01-01T00:00:00Z")),
              List.of(
                  new HotfixFile(
                      "JRS-8.2.0-HF-0001",
                      sharedFile,
                      "replace",
                      Optional.empty(),
                      Optional.empty())));
      console
          .store()
          .recordHotfixInstalled(
              new HotfixInstalled(
                  "JRS-8.2.0-HF-0002",
                  "2",
                  "Fake scheduler fix, take two",
                  "r-seed-hf-0002",
                  Optional.empty(),
                  HotfixState.INSTALLED,
                  Instant.parse("2026-01-02T00:00:00Z")),
              List.of(
                  new HotfixFile(
                      "JRS-8.2.0-HF-0002",
                      sharedFile,
                      "replace",
                      Optional.empty(),
                      Optional.empty())));
      console
          .store()
          .recordHotfixInstalled(
              new HotfixInstalled(
                  "JRS-8.2.0-HF-0003",
                  "3",
                  "Fake fix, since rolled back",
                  "r-seed-hf-0003",
                  Optional.empty(),
                  HotfixState.ROLLED_BACK,
                  Instant.parse("2026-01-03T00:00:00Z")),
              List.of(
                  new HotfixFile(
                      "JRS-8.2.0-HF-0003", otherFile, "add", Optional.empty(), Optional.empty())));

      assertGolden("hotfixes-installed", console.get("/api/hotfixes"), home);
    }
  }

  /**
   * Captures {@code health.lock} held ({@code runId} and {@code pid} both present) and {@code
   * /api/runs/{id}} showing {@code outcome: "running"} with a null {@code finishedAt}, by reusing
   * {@link ConsoleServerTest.BlockingPlans}, the same fake the cancel test uses to hold a run
   * mid-flight, rather than inventing a second blocking mechanism.
   */
  @Test
  void should_match_the_golden_when_a_run_is_in_flight() throws Exception {
    Path home = tmp.resolve("home");
    ConsoleServerTest.BlockingPlans plans = new ConsoleServerTest.BlockingPlans();
    FakeHotfixOperations fake = new FakeHotfixOperations();
    try (ConsoleFixture console =
        ConsoleFixture.start(
            home, Clock.systemUTC(), services -> new OperationCatalog(plans, () -> fake))) {
      HttpResponse<String> planned =
          console.post("/api/plan", "{\"op\":\"hotfix.apply\",\"args\":{\"block\":true}}");
      String planId = Json.mapper().readTree(planned.body()).get("planId").asText();
      HttpResponse<String> started =
          console.post("/api/run", "{\"planId\":\"" + planId + "\",\"confirm\":true}");
      String runId = Json.mapper().readTree(started.body()).get("runId").asText();
      waitForBlocking(plans);

      assertGolden("health-run-in-flight", console.get("/api/health"), home);
      assertGolden("runs-show-running", console.get("/api/runs/" + runId), home);

      console.post("/api/runs/" + runId + "/cancel", "");
      waitForTerminal(console, runId);
    }
  }

  /**
   * Captures {@code health.pendingRuns[0].stepId} as {@code null} for a pending run that has no
   * transitions yet, seeded through the public {@link com.jaspersoft.jrsctl.core.state.StateStore}
   * API reached via {@link ConsoleFixture#store()}, the same way the hotfix goldens seed rows.
   */
  @Test
  void should_match_the_golden_when_a_pending_run_has_no_transitions() throws Exception {
    Path home = tmp.resolve("home");
    try (ConsoleFixture console = ConsoleFixture.start(home, Clock.systemUTC())) {
      console.store().recordRunStart("r-no-steps", "hotfix.apply", Optional.empty(), Instant.now());
      assertGolden("health-pending-no-steps", console.get("/api/health"), home);
    }
  }

  /** Polls until {@code plans}'s blocking step has started, so the run is reliably mid-flight. */
  private static void waitForBlocking(ConsoleServerTest.BlockingPlans plans) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (!plans.blocking) {
      if (System.nanoTime() >= deadline) {
        throw new AssertionError("the run never reached its blocking step");
      }
      Thread.sleep(20);
    }
  }

  /**
   * Polls until {@code runId} leaves {@code running}, then until the console's own run lock clears.
   * The two settle a moment apart: the state store's terminal write happens inside {@code
   * RunManager}'s worker body, while the in-process "a run is live" flag {@code /api/health}'s
   * {@code lock} reports from is only cleared once that body returns. Waiting for both keeps a
   * golden captured right after the run from reading the lock mid-release.
   */
  private static void waitForTerminal(ConsoleFixture console, String runId) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
    while (System.nanoTime() < deadline) {
      String outcome =
          Json.mapper().readTree(console.get("/api/runs/" + runId).body()).get("outcome").asText();
      if (!outcome.equals("running")) {
        waitForLockRelease(console, deadline);
        return;
      }
      Thread.sleep(50);
    }
    throw new AssertionError("run " + runId + " never reached a terminal state");
  }

  private static void waitForLockRelease(ConsoleFixture console, long deadline) throws Exception {
    while (System.nanoTime() < deadline) {
      JsonNode health = Json.mapper().readTree(console.get("/api/health").body());
      if (!health.path("lock").path("held").asBoolean(false)) {
        return;
      }
      Thread.sleep(50);
    }
    throw new AssertionError("the run lock was never released");
  }

  static void assertGolden(String name, HttpResponse<String> response, Path home)
      throws IOException {
    assertThat(response.statusCode()).as(name + " status").isEqualTo(200);
    JsonNode scrubbed = ConsoleScrub.scrub(Json.mapper().readTree(response.body()), home);
    assertAgainstGolden(name, scrubbed);
  }

  static void assertShapeGolden(String name, HttpResponse<String> response) throws IOException {
    assertThat(response.statusCode()).as(name + " status").isEqualTo(200);
    JsonNode shaped = ConsoleScrub.shape(Json.mapper().readTree(response.body()));
    assertAgainstGolden(name, shaped);
  }

  private static void assertAgainstGolden(String name, JsonNode doc) throws IOException {
    String actual = Json.writePretty(doc) + "\n";
    Path golden = Path.of(DIR + name + ".json");
    if (Boolean.getBoolean("console.golden.update") || !Files.exists(golden)) {
      Files.createDirectories(golden.getParent());
      Files.writeString(golden, actual, StandardCharsets.UTF_8);
      throw new AssertionError(
          "wrote golden " + golden + "; review it and run again without the update flag");
    }
    try (InputStream in = Files.newInputStream(golden)) {
      String expected = new String(in.readAllBytes(), StandardCharsets.UTF_8);
      assertThat(actual).as(name).isEqualTo(expected);
    }
  }
}
