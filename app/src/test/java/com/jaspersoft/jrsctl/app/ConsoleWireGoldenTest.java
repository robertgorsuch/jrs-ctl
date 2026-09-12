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
