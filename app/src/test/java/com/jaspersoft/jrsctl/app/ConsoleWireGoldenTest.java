package com.jaspersoft.jrsctl.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.jaspersoft.jrsctl.core.json.Json;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
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
      assertGolden("hotfixes-installed", console.get("/api/hotfixes"), home);
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
