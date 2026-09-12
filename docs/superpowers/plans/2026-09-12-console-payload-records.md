# Console Payload Records Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace every hand-built `LinkedHashMap` behind the console's `/api/*` endpoints with immutable Java records, publish a JSON Schema per endpoint, and prove the JSON on the wire did not change.

**Architecture:** Capture the current JSON as normalised golden files first, while the maps are still in place. Write the schemas against those goldens. Then migrate one view area at a time; a golden that moves is a bug in the migration, not a golden to update. Records live one file per endpoint document in the existing `console` package, package-private, with nested records for nested objects.

**Tech Stack:** Java 21 records, Jackson 2.22 (`core.json.Json`, already registers `JavaTimeModule` and `Jdk8Module`), networknt json-schema-validator 1.4.0 (already a compile dependency of `core`), JUnit 5, AssertJ.

**Spec:** `docs/superpowers/specs/2026-09-12-console-payload-records-design.md`

## Global Constraints

- Build only through `scripts\mvn.cmd` (Windows) or `scripts/mvn.sh`. The machine's default `java` is 11; the wrappers select JDK 21.
- `scripts\mvn.cmd spotless:apply` before every commit. Formatter is google-java-format.
- Compilation runs with `-Werror` and Error Prone. A warning fails the build.
- Package root is `com.jaspersoft.jrsctl.<module>`. All new code here is `com.jaspersoft.jrsctl.app.console`.
- No new third-party dependency without an ADR. This plan adds none.
- No `null` returns from public APIs. Use `Optional` or a sealed result.
- One-paragraph Javadoc stating invariants on every public class. Apply it to the package-private records too; the codebase does.
- Tests are named `should_<behaviour>_when_<condition>`.
- Conventional Commits, one logical change per commit.
- Run one module's tests with `scripts\mvn.cmd test -pl app -am -Dtest=<ClassName>`.
- Full check for this work: `scripts\mvn.cmd verify -Dphase=6`.
- Branch `feat/console-payload-records` already exists and holds the design doc. Work on it.

## File Structure

**Created:**

| Path | Responsibility |
|---|---|
| `app/src/test/java/com/jaspersoft/jrsctl/app/ConsoleFixture.java` | Boots a real `ConsoleServer` on a free port over a temp home; the shared harness for the golden test |
| `app/src/test/java/com/jaspersoft/jrsctl/app/ConsoleScrub.java` | Replaces volatile substrings and numbers in a JSON tree so goldens are stable across runs |
| `app/src/test/java/com/jaspersoft/jrsctl/app/ConsoleWireGoldenTest.java` | Fetches every `/api/*` document and compares it to a checked-in golden |
| `app/src/test/java/com/jaspersoft/jrsctl/app/ConsoleSchemaTest.java` | Validates every golden against its published schema |
| `app/src/test/resources/console-wire/*.json` | The goldens; captured from the map code, never edited by hand after that |
| `app/src/main/resources/schema/json/api-*.schema.json` | Eight published schemas, one per endpoint shape |
| `app/src/main/java/.../console/HealthDoc.java` | `/api/health` payload records |
| `app/src/main/java/.../console/DoctorDoc.java` | `/api/doctor` payload records |
| `app/src/main/java/.../console/ServerDoc.java` | `/api/server` payload records |
| `app/src/main/java/.../console/HotfixesDoc.java` | `/api/hotfixes` payload records |
| `app/src/main/java/.../console/PlanDoc.java` | `POST /api/plan` payload records |
| `app/src/main/java/.../console/RunsDoc.java` | `/api/runs` and `/api/runs/{id}` payload records |

**Modified:**

| Path | Change |
|---|---|
| `app/src/main/java/.../console/ConsoleViews.java` | `health()` and `doctor()` return records; delegating signatures follow the per-area returns |
| `app/src/main/java/.../console/ServerViews.java` | `server()` and `service()` return records |
| `app/src/main/java/.../console/HotfixViews.java` | `hotfixes()` returns a record |
| `app/src/main/java/.../console/RunViews.java` | `planResponse()`, `runList()`, `runItem()`, `runDetail()`, `steps()`, `stepRow()`, `failure()` return records |
| `app/src/main/java/com/jaspersoft/jrsctl/app/JsonSchemas.java` | Adds the endpoint-keyed schema registry |
| `app/src/test/java/com/jaspersoft/jrsctl/app/ConsoleServerTest.java` | Validates each live `/api/*` response against its schema |
| `app/src/main/resources/web/README.md` | Three corrections where the published contract disagrees with the code |
| `docs/BUILD_STATUS.md` | Records the console schema surface |

---

### Task 1: Golden harness and goldens for the four simple endpoints

Captures today's JSON for `/api/health`, `/api/server`, `/api/doctor` and `/api/hotfixes`, while those endpoints are still built from maps. This is the safety net for Tasks 4 to 6, so it lands before any production code changes.

**Files:**
- Create: `app/src/test/java/com/jaspersoft/jrsctl/app/ConsoleFixture.java`
- Create: `app/src/test/java/com/jaspersoft/jrsctl/app/ConsoleScrub.java`
- Create: `app/src/test/java/com/jaspersoft/jrsctl/app/ConsoleWireGoldenTest.java`
- Create: `app/src/test/resources/console-wire/health-fresh.json`, `server-unreachable.json`, `doctor.json`, `hotfixes-empty.json`

**Interfaces:**
- Consumes: `ConsoleServer`, `ConsoleOptions`, `RunService`, `ConsoleCommand.catalog`, `Bootstrap`, `GlobalOptions`, `Env`, `TestAdapterFactory`, all already present in `app`.
- Produces: `ConsoleFixture.start(Path home)` returning a started fixture with `get(String path)`, `post(String path, String body)`, `baseUrl()`, `close()`. `ConsoleScrub.scrub(JsonNode)` returning a stable tree. `ConsoleWireGoldenTest.assertGolden(String name, JsonNode doc)`.

- [ ] **Step 1: Write the fixture**

`ConsoleFixture` copies the bootstrap `ConsoleServerTest` already uses. `ConsoleServerTest` is left alone; refactoring a 946-line passing test is not part of this work.

```java
package com.jaspersoft.jrsctl.app;

import com.jaspersoft.jrsctl.app.console.ConsoleOptions;
import com.jaspersoft.jrsctl.app.console.ConsoleServer;
import com.jaspersoft.jrsctl.ops.RunService;
import com.jaspersoft.jrsctl.ops.Services;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;
import java.util.Optional;

/**
 * A real console listener on a free port over a temporary home, for tests that need the HTTP
 * surface rather than the view objects. Invariants: the caller owns the home directory and the
 * clock; {@link #close()} stops the listener and the bootstrap in that order; every request
 * carries the per-launch bearer token, so a 401 from this fixture is a product bug.
 */
final class ConsoleFixture implements AutoCloseable {

  private final HttpClient http = HttpClient.newHttpClient();
  private final Bootstrap boot;
  private final ConsoleServer server;
  private final String token;

  private ConsoleFixture(Bootstrap boot, ConsoleServer server, String token) {
    this.boot = boot;
    this.server = server;
    this.token = token;
  }

  /** Writes a minimal configuration into {@code home} and starts a console over it. */
  static ConsoleFixture start(Path home, Clock clock) throws IOException {
    Files.createDirectories(home);
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
            .formatted(home.toString().replace("\\", "/")),
        StandardCharsets.UTF_8);
    Env.override(Map.of("JRS_PASSWORD", "jasperadmin"));
    GlobalOptions global = new GlobalOptions();
    global.home = home;
    global.nonInteractive = true;
    Bootstrap boot = Bootstrap.open(global, Env.vars(), clock);
    Services services = boot.services();
    ConsoleServer server =
        new ConsoleServer(
            services,
            new ConsoleOptions(Optional.empty(), Optional.of(0)),
            new RunService(services),
            ConsoleCommand.catalog(services));
    server.start();
    return new ConsoleFixture(boot, server, server.token().orElseThrow().text());
  }

  HttpResponse<String> get(String path) throws Exception {
    return http.send(request(path).GET().build(), HttpResponse.BodyHandlers.ofString());
  }

  HttpResponse<String> post(String path, String body) throws Exception {
    return http.send(
        request(path)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private HttpRequest.Builder request(String path) {
    return HttpRequest.newBuilder(URI.create(server.baseUrl() + path))
        .header("Authorization", "Bearer " + token);
  }

  @Override
  public void close() {
    server.close();
    boot.close();
    Env.reset();
  }
}
```

- [ ] **Step 2: Write the scrubber**

Volatile values are replaced as substrings, not whole values, so `subtitle` keeps its strategy text and only its run id changes. Numbers are keyed, and `null` stays `null` because the null-versus-absent distinction is the whole point of the goldens.

```java
package com.jaspersoft.jrsctl.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.LongNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Rewrites the values a console document cannot repeat between runs so a golden file stays
 * stable. Invariants: structure and key order are untouched; a JSON {@code null} stays
 * {@code null}, because whether a key is absent, null or set is exactly what the goldens
 * guard; replacement is by substring, so text around an id or a path survives.
 */
final class ConsoleScrub {

  private static final Pattern UUID =
      Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
  private static final Pattern RUN_ID = Pattern.compile("r-\\d{8}-\\d{6}-[0-9a-f]{4}");
  private static final Pattern INSTANT =
      Pattern.compile("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?Z");
  private static final Pattern SHA = Pattern.compile("sha256:[0-9a-f]{8,}");
  private static final Pattern HOSTPORT = Pattern.compile("127\\.0\\.0\\.1:\\d+");

  /** Numeric keys whose value is a measurement, replaced by 0 when not null. */
  private static final List<String> MEASURED = List.of("durationMs", "bytes");

  private ConsoleScrub() {}

  /** A copy of {@code node} with volatile values replaced; {@code home} becomes {@code <home>}. */
  static JsonNode scrub(JsonNode node, Path home) {
    return walk(node.deepCopy(), home, "");
  }

  private static JsonNode walk(JsonNode node, Path home, String key) {
    if (node instanceof ObjectNode object) {
      for (String name : List.copyOf(object.properties().stream().map(Map.Entry::getKey).toList())) {
        object.set(name, walk(object.get(name), home, name));
      }
      return object;
    }
    if (node instanceof ArrayNode array) {
      for (int i = 0; i < array.size(); i++) {
        array.set(i, walk(array.get(i), home, key));
      }
      return array;
    }
    if (node.isNull()) {
      return node;
    }
    if (node.isNumber() && MEASURED.contains(key)) {
      return LongNode.valueOf(0L);
    }
    if (node.isTextual()) {
      return TextNode.valueOf(text(node.asText(), home));
    }
    return node;
  }

  private static String text(String value, Path home) {
    String out = value.replace(home.toString(), "<home>").replace(
        home.toString().replace("\\", "/"), "<home>");
    out = RUN_ID.matcher(out).replaceAll("<runId>");
    out = UUID.matcher(out).replaceAll("<uuid>");
    out = INSTANT.matcher(out).replaceAll("<instant>");
    out = SHA.matcher(out).replaceAll("sha256:<hex>");
    out = HOSTPORT.matcher(out).replaceAll("127.0.0.1:<port>");
    return out;
  }
}
```

- [ ] **Step 3: Write the failing golden test for the four simple endpoints**

```java
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
 * Freezes the JSON of every console endpoint. Invariants: a golden is captured from the code as
 * it stood before the record migration and is never edited by hand afterwards; a difference is a
 * change to the published wire format and fails the build. Run with
 * {@code -Dconsole.golden.update=true} to write a missing golden, then read it before committing.
 */
class ConsoleWireGoldenTest {

  private static final String DIR = "app/src/test/resources/console-wire/";

  @TempDir Path tmp;

  @Test
  void should_match_the_golden_when_the_console_is_fresh() throws Exception {
    Path home = tmp.resolve("home");
    TestAdapterFactory.unreachable = true;
    try (ConsoleFixture console = ConsoleFixture.start(home, Clock.systemUTC())) {
      assertGolden("health-fresh", console.get("/api/health"), home);
      assertGolden("server-unreachable", console.get("/api/server"), home);
      assertGolden("doctor", console.get("/api/doctor"), home);
      assertGolden("hotfixes-empty", console.get("/api/hotfixes"), home);
    } finally {
      TestAdapterFactory.unreachable = false;
    }
  }

  static void assertGolden(String name, HttpResponse<String> response, Path home)
      throws IOException {
    assertThat(response.statusCode()).as(name + " status").isEqualTo(200);
    JsonNode scrubbed = ConsoleScrub.scrub(Json.mapper().readTree(response.body()), home);
    String actual = Json.writePretty(scrubbed) + "\n";
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
```

- [ ] **Step 4: Run it to see it fail with no goldens present**

Run: `scripts\mvn.cmd test -pl app -am -Dtest=ConsoleWireGoldenTest`
Expected: FAIL with `wrote golden ...health-fresh.json; review it and run again without the update flag`.

- [ ] **Step 5: Read the four goldens and check them against the README**

Open each file under `app/src/test/resources/console-wire/`. Confirm by eye against `app/src/main/resources/web/README.md`:
- `health-fresh.json` has `lastRun: null` and no `doctor` key, because nothing has run and no doctor is cached.
- `server-unreachable.json` has `reachable: false`, empty strings for `version`, `edition`, `tenancy`, and `database.version`.
- `doctor.json` has `counts` with all four of `pass`, `warn`, `fail`, `skip`.
- `hotfixes-empty.json` is `{"hotfixes": []}`.

If any of those is wrong, the fixture is wrong, not the golden. Fix the fixture and recapture.

- [ ] **Step 6: Run it again to verify it passes**

Run: `scripts\mvn.cmd test -pl app -am -Dtest=ConsoleWireGoldenTest`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
scripts\mvn.cmd spotless:apply
git add app/src/test/java/com/jaspersoft/jrsctl/app/ConsoleFixture.java \
        app/src/test/java/com/jaspersoft/jrsctl/app/ConsoleScrub.java \
        app/src/test/java/com/jaspersoft/jrsctl/app/ConsoleWireGoldenTest.java \
        app/src/test/resources/console-wire
git commit -m "test(app): freeze the console wire format for the simple endpoints"
```

---

### Task 2: Goldens for the plan and run endpoints

The remaining four documents, including the branches the design flags as the risk: a run that failed, a run still running, and a plan with `resourcesTouched`.

**Files:**
- Modify: `app/src/test/java/com/jaspersoft/jrsctl/app/ConsoleWireGoldenTest.java`
- Create: `app/src/test/resources/console-wire/plan-hotfix-apply.json`, `run-started.json`, `runs-after-apply.json`, `runs-show-succeeded.json`, `runs-show-failed.json`, `health-after-apply.json`, `hotfixes-installed.json`

**Interfaces:**
- Consumes: `ConsoleFixture`, `ConsoleScrub`, `ConsoleWireGoldenTest.assertGolden` from Task 1; `FakeHotfixOperations` and `HotfixOps.factory` from the existing app test sources.
- Produces: goldens covering the `failure` block, a null `finishedAt`, and a non-empty `blockedBy`.

- [ ] **Step 1: Write the failing test for a plan and a successful run**

`FakeHotfixOperations` is the same fake `ConsoleServerTest` installs. A bundle path containing `HF-0002` makes the fake run fail, which is how the failed-run golden is produced.

```java
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

  private static void waitForTerminal(ConsoleFixture console, String runId) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
    while (System.nanoTime() < deadline) {
      String outcome =
          Json.mapper().readTree(console.get("/api/runs/" + runId).body()).get("outcome").asText();
      if (!outcome.equals("running")) {
        return;
      }
      Thread.sleep(50);
    }
    throw new AssertionError("run " + runId + " never reached a terminal state");
  }
```

Add the imports `java.util.concurrent.TimeUnit` and `java.net.http.HttpResponse` to the test class.

- [ ] **Step 2: Run it, review the written goldens, run it again**

Run: `scripts\mvn.cmd test -pl app -am -Dtest=ConsoleWireGoldenTest`
Expected: FAIL writing goldens on the first pass. Read them. Confirm `runs-show-succeeded.json` has no `failure` key and that `plan-hotfix-apply.json`'s `summary` carries exactly `filesTouched`, `service`, `backups`, `rollbackPoints`, `strategy`, `downtime`, `warnings`. Run again.
Expected: PASS.

- [ ] **Step 3: Write the failing test for a failed run**

```java
  @Test
  void should_match_the_golden_when_a_hotfix_run_fails() throws Exception {
    Path home = tmp.resolve("home");
    Path bundle = tmp.resolve("HF-0002.zip");
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
```

- [ ] **Step 4: Run it, confirm the failure block, run it again**

Run: `scripts\mvn.cmd test -pl app -am -Dtest=ConsoleWireGoldenTest`
Expected: FAIL writing the golden. Read `runs-show-failed.json` and confirm it has a `failure` object with `stepId`, `cause`, `backups` and `nextAction`, and that `outcome` is `failed` or `failed_rolled_back`. Run again.
Expected: PASS.

If `FakeHotfixOperations` does not fail on an `HF-0002` bundle path, read that class and use whatever trigger it does provide; the `HF-0002` convention comes from `web/mock.js` and may be front-end only. The requirement is a golden of a genuinely failed run, not this particular trigger.

- [ ] **Step 5: Commit**

```bash
scripts\mvn.cmd spotless:apply
git add app/src/test/java/com/jaspersoft/jrsctl/app/ConsoleWireGoldenTest.java \
        app/src/test/resources/console-wire
git commit -m "test(app): freeze the console wire format for plans and runs"
```

---

### Task 3: Publish the eight schemas and validate the goldens against them

**Files:**
- Create: `app/src/main/resources/schema/json/api-health.schema.json`, `api-server.schema.json`, `api-plan.schema.json`, `api-runs.schema.json`, `api-runs-show.schema.json`, `api-doctor.schema.json`, `api-hotfixes.schema.json`, `api-run-started.schema.json`
- Modify: `app/src/main/java/com/jaspersoft/jrsctl/app/JsonSchemas.java`
- Create: `app/src/test/java/com/jaspersoft/jrsctl/app/ConsoleSchemaTest.java`

**Interfaces:**
- Consumes: `JsonSchemas.IRI_PREFIX`, `JsonSchemas.iri`, `JsonSchemas.open`, `JsonSchemas.resourcePath`, all already public.
- Produces: `JsonSchemas.forEndpoint(String path)` returning `Optional<String>`, and `JsonSchemas.endpoints()` returning `Set<String>`. Task 8 calls both.

- [ ] **Step 1: Write the failing schema test**

Goldens are scrubbed, so the schema must accept the placeholder strings. Keep formats out of the schemas for the scrubbed fields: use `"type": "string"` for instants rather than `"format": "date-time"`. The schema's job here is structure, which is what the task asks for.

```java
package com.jaspersoft.jrsctl.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.jaspersoft.jrsctl.core.json.Json;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.Test;

/**
 * Every console document the golden files hold validates against the schema published for its
 * endpoint. Invariants: each endpoint in {@link JsonSchemas#endpoints()} has at least one golden;
 * a golden that no schema accepts fails the build, so the records, the schemas and the wire
 * cannot drift apart.
 */
class ConsoleSchemaTest {

  private static final JsonSchemaFactory FACTORY =
      JsonSchemaFactory.getInstance(
          SpecVersion.VersionFlag.V202012,
          builder ->
              builder.schemaLoaders(
                  loaders ->
                      loaders.add(
                          location ->
                              JsonSchemas.resourcePath(location.toString())
                                  .map(ConsoleSchemaTest.class::getResourceAsStream)
                                  .map(in -> (com.networknt.schema.resource.InputStreamSource) () -> in)
                                  .orElse(null))));

  static Stream<org.junit.jupiter.params.provider.Arguments> goldens() {
    return Stream.of(
        org.junit.jupiter.params.provider.Arguments.of("health-fresh", "api-health.schema.json"),
        org.junit.jupiter.params.provider.Arguments.of(
            "health-after-apply", "api-health.schema.json"),
        org.junit.jupiter.params.provider.Arguments.of(
            "server-unreachable", "api-server.schema.json"),
        org.junit.jupiter.params.provider.Arguments.of("doctor", "api-doctor.schema.json"),
        org.junit.jupiter.params.provider.Arguments.of(
            "hotfixes-empty", "api-hotfixes.schema.json"),
        org.junit.jupiter.params.provider.Arguments.of(
            "hotfixes-installed", "api-hotfixes.schema.json"),
        org.junit.jupiter.params.provider.Arguments.of(
            "plan-hotfix-apply", "api-plan.schema.json"),
        org.junit.jupiter.params.provider.Arguments.of(
            "run-started", "api-run-started.schema.json"),
        org.junit.jupiter.params.provider.Arguments.of(
            "runs-after-apply", "api-runs.schema.json"),
        org.junit.jupiter.params.provider.Arguments.of(
            "runs-show-succeeded", "api-runs-show.schema.json"),
        org.junit.jupiter.params.provider.Arguments.of(
            "runs-show-failed", "api-runs-show.schema.json"));
  }

  @ParameterizedTest(name = "{0} validates against {1}")
  @MethodSource("goldens")
  void should_validate_the_golden_against_its_schema(String golden, String schemaName)
      throws Exception {
    JsonNode doc =
        Json.mapper()
            .readTree(Files.readString(Path.of("src/test/resources/console-wire/" + golden + ".json")));
    JsonSchema schema = FACTORY.getSchema(SchemaLocation.of(JsonSchemas.iri(schemaName)));
    Set<ValidationMessage> errors = schema.validate(doc);
    assertThat(errors).as(golden + " against " + schemaName).isEmpty();
  }

  @Test
  void should_publish_a_schema_for_every_console_endpoint() {
    for (String endpoint : JsonSchemas.endpoints()) {
      String name = JsonSchemas.forEndpoint(endpoint).orElseThrow();
      assertThat(JsonSchemas.open(name)).as(endpoint).isPresent();
    }
  }
}
```

If the schema-loader lambda above does not match the networknt 1.4.0 API, copy the loader configuration from `JsonOutputSchemaTest` lines 60 to 75 verbatim instead; that test already resolves these `$id`s from the classpath and is the reference implementation.

- [ ] **Step 2: Run it to verify it fails**

Run: `scripts\mvn.cmd test -pl app -am -Dtest=ConsoleSchemaTest`
Expected: FAIL. `JsonSchemas.endpoints` does not compile yet.

- [ ] **Step 3: Add the endpoint registry to `JsonSchemas`**

Insert after the `BY_COMMAND` declaration and its `build()` method.

```java
  /** The console API schemas, keyed by endpoint path (spec §13.1). */
  private static final Map<String, String> BY_ENDPOINT = endpointSchemas();

  private static Map<String, String> endpointSchemas() {
    Map<String, String> m = new LinkedHashMap<>();
    m.put("GET /api/health", "api-health.schema.json");
    m.put("GET /api/server", "api-server.schema.json");
    m.put("POST /api/plan", "api-plan.schema.json");
    m.put("POST /api/run", "api-run-started.schema.json");
    m.put("GET /api/runs", "api-runs.schema.json");
    m.put("GET /api/runs/{id}", "api-runs-show.schema.json");
    m.put("GET /api/doctor", "api-doctor.schema.json");
    m.put("GET /api/hotfixes", "api-hotfixes.schema.json");
    return Collections.unmodifiableMap(m);
  }

  /** The schema file for a console endpoint such as {@code "GET /api/health"}. */
  public static Optional<String> forEndpoint(String endpoint) {
    return Optional.ofNullable(BY_ENDPOINT.get(endpoint));
  }

  /** Every console endpoint that has a schema, in declaration order. */
  public static Set<String> endpoints() {
    return BY_ENDPOINT.keySet();
  }
```

Then add the console schemas to `schemas()` so the existing parse assertion in `JsonOutputSchemaTest` covers them. Inside `schemas()`, before the `return`:

```java
    names.addAll(BY_ENDPOINT.values());
```

Update the class Javadoc's first sentence to say the file also holds the console API schemas keyed by endpoint path.

- [ ] **Step 4: Write the eight schema files**

Each carries `$schema`, `$id` under `https://jaspersoft.com/jrsctl/`, `additionalProperties: false`, and a `required` list holding exactly the keys that are always present. A key that is present-but-null is required with a nullable type; a key that is absent when unset is simply not required.

`api-health.schema.json`:

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "$id": "https://jaspersoft.com/jrsctl/api-health.schema.json",
  "title": "GET /api/health (spec §13.1)",
  "type": "object",
  "additionalProperties": false,
  "required": ["tool", "bind", "networkMode", "lastRun", "lock", "pendingRuns", "snapshots"],
  "properties": {
    "tool": {
      "type": "object",
      "additionalProperties": false,
      "required": ["version", "matrixVersion"],
      "properties": {
        "version": { "type": "string" },
        "matrixVersion": { "type": "string" }
      }
    },
    "bind": { "type": "string" },
    "networkMode": { "enum": ["isolated", "proxy", "direct"] },
    "lastRun": {
      "type": ["object", "null"],
      "additionalProperties": false,
      "required": ["id", "op", "outcome", "finishedAt"],
      "properties": {
        "id": { "type": "string" },
        "op": { "type": "string" },
        "outcome": { "$ref": "https://jaspersoft.com/jrsctl/api-runs.schema.json#/$defs/outcome" },
        "finishedAt": { "type": ["string", "null"] }
      }
    },
    "lock": {
      "type": "object",
      "additionalProperties": false,
      "required": ["held"],
      "properties": {
        "held": { "type": "boolean" },
        "runId": { "type": "string" },
        "pid": { "type": "string" }
      }
    },
    "pendingRuns": {
      "type": "array",
      "items": {
        "type": "object",
        "additionalProperties": false,
        "required": ["id", "op", "startedAt", "stepId"],
        "properties": {
          "id": { "type": "string" },
          "op": { "type": "string" },
          "startedAt": { "type": "string" },
          "stepId": { "type": ["string", "null"] }
        }
      }
    },
    "snapshots": {
      "type": "object",
      "additionalProperties": false,
      "required": ["count", "bytes", "retentionDays"],
      "properties": {
        "count": { "type": "integer", "minimum": 0 },
        "bytes": { "type": "integer", "minimum": 0 },
        "retentionDays": { "type": "integer", "minimum": 0 }
      }
    },
    "doctor": {
      "type": "object",
      "additionalProperties": false,
      "required": ["pass", "warn", "fail", "ranAt", "attention"],
      "properties": {
        "pass": { "type": "integer", "minimum": 0 },
        "warn": { "type": "integer", "minimum": 0 },
        "fail": { "type": "integer", "minimum": 0 },
        "ranAt": { "type": "string" },
        "attention": {
          "type": "array",
          "items": {
            "type": "object",
            "additionalProperties": false,
            "required": ["status", "title", "detail"],
            "properties": {
              "status": { "enum": ["WARN", "FAIL"] },
              "title": { "type": "string" },
              "detail": { "type": "string" }
            }
          }
        }
      }
    }
  }
}
```

`api-server.schema.json`:

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "$id": "https://jaspersoft.com/jrsctl/api-server.schema.json",
  "title": "GET /api/server (spec §13.1)",
  "type": "object",
  "additionalProperties": false,
  "required": ["product", "version", "edition", "tenancy", "database", "baseUrl", "installDir",
               "service", "keystore", "networkMode", "reachable"],
  "properties": {
    "product": { "type": "string" },
    "version": { "type": "string" },
    "edition": { "type": "string" },
    "tenancy": { "enum": ["", "multi-tenant", "single-tenant"] },
    "database": {
      "type": "object",
      "additionalProperties": false,
      "required": ["vendor", "version"],
      "properties": {
        "vendor": { "type": "string" },
        "version": { "type": "string" }
      }
    },
    "baseUrl": { "type": "string" },
    "installDir": { "type": "string" },
    "service": {
      "type": "object",
      "additionalProperties": false,
      "required": ["kind", "name", "state"],
      "properties": {
        "kind": { "type": "string" },
        "name": { "type": "string" },
        "state": { "type": "string" }
      }
    },
    "keystore": {
      "type": "object",
      "additionalProperties": false,
      "required": ["present", "user"],
      "properties": {
        "present": { "type": "boolean" },
        "user": { "type": "string" }
      }
    },
    "networkMode": { "enum": ["isolated", "proxy", "direct"] },
    "reachable": { "type": "boolean" }
  }
}
```

`api-doctor.schema.json`:

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "$id": "https://jaspersoft.com/jrsctl/api-doctor.schema.json",
  "title": "GET /api/doctor (spec §13.1)",
  "type": "object",
  "additionalProperties": false,
  "required": ["ranAt", "counts", "items", "exitCode"],
  "properties": {
    "ranAt": { "type": "string" },
    "counts": {
      "type": "object",
      "additionalProperties": false,
      "required": ["pass", "warn", "fail", "skip"],
      "properties": {
        "pass": { "type": "integer", "minimum": 0 },
        "warn": { "type": "integer", "minimum": 0 },
        "fail": { "type": "integer", "minimum": 0 },
        "skip": { "type": "integer", "minimum": 0 }
      }
    },
    "items": {
      "type": "array",
      "items": {
        "type": "object",
        "additionalProperties": false,
        "required": ["id", "name", "status", "title", "detail", "remediation"],
        "properties": {
          "id": { "type": "string" },
          "name": { "type": "string" },
          "status": { "enum": ["PASS", "WARN", "FAIL", "SKIP"] },
          "title": { "type": "string" },
          "detail": { "type": "string" },
          "remediation": { "type": "string" }
        }
      }
    },
    "exitCode": { "type": "integer", "minimum": 0 }
  }
}
```

`api-hotfixes.schema.json`:

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "$id": "https://jaspersoft.com/jrsctl/api-hotfixes.schema.json",
  "title": "GET /api/hotfixes (spec §13.1)",
  "type": "object",
  "additionalProperties": false,
  "required": ["hotfixes"],
  "properties": {
    "hotfixes": {
      "type": "array",
      "items": {
        "type": "object",
        "additionalProperties": false,
        "required": ["id", "title", "installedAt", "files", "state", "blockedBy"],
        "properties": {
          "id": { "type": "string" },
          "title": { "type": "string" },
          "installedAt": { "type": "string" },
          "files": { "type": "integer", "minimum": 0 },
          "state": { "enum": ["installed", "rolled_back"] },
          "blockedBy": { "type": "array", "items": { "type": "string" } }
        }
      }
    }
  }
}
```

`api-run-started.schema.json`:

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "$id": "https://jaspersoft.com/jrsctl/api-run-started.schema.json",
  "title": "POST /api/run, /api/runs/{id}/rollback, /api/runs/{id}/resume (spec §13.1)",
  "type": "object",
  "additionalProperties": false,
  "required": ["runId"],
  "properties": {
    "runId": { "type": "string" }
  }
}
```

`api-plan.schema.json`:

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "$id": "https://jaspersoft.com/jrsctl/api-plan.schema.json",
  "title": "POST /api/plan (spec §13.1)",
  "type": "object",
  "additionalProperties": false,
  "required": ["planId", "plan"],
  "properties": {
    "planId": { "type": "string" },
    "plan": {
      "type": "object",
      "additionalProperties": false,
      "required": ["op", "title", "fingerprint", "validUntil", "summary", "steps"],
      "properties": {
        "op": { "type": "string" },
        "title": { "type": "string" },
        "fingerprint": { "type": "string" },
        "validUntil": { "type": "string" },
        "summary": {
          "type": "object",
          "additionalProperties": false,
          "required": ["filesTouched", "service", "backups", "rollbackPoints", "strategy",
                       "downtime", "warnings"],
          "properties": {
            "filesTouched": { "type": "string" },
            "resourcesTouched": { "type": "string" },
            "service": { "type": "string" },
            "backups": { "type": "string" },
            "rollbackPoints": { "type": "string" },
            "strategy": { "type": "string" },
            "downtime": { "type": "string" },
            "warnings": { "type": "array", "items": { "type": "string" } }
          }
        },
        "steps": {
          "type": "array",
          "items": {
            "type": "object",
            "additionalProperties": false,
            "required": ["id", "phase", "title", "why"],
            "properties": {
              "id": { "type": "string" },
              "phase": { "type": "string" },
              "title": { "type": "string" },
              "why": { "type": "string" }
            }
          }
        }
      }
    }
  }
}
```

`api-runs.schema.json` owns the shared run item and outcome vocabulary:

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "$id": "https://jaspersoft.com/jrsctl/api-runs.schema.json",
  "title": "GET /api/runs (spec §13.1)",
  "type": "object",
  "additionalProperties": false,
  "required": ["runs"],
  "properties": {
    "runs": { "type": "array", "items": { "$ref": "#/$defs/runItem" } }
  },
  "$defs": {
    "outcome": {
      "enum": ["succeeded", "failed", "failed_rolled_back", "rollback_incomplete", "cancelled",
               "running", "interrupted"]
    },
    "runItem": {
      "type": "object",
      "additionalProperties": false,
      "required": ["id", "op", "target", "startedAt", "finishedAt", "durationMs", "outcome",
                   "rollbackAvailable", "supportBundleAvailable"],
      "properties": {
        "id": { "type": "string" },
        "op": { "type": "string" },
        "target": { "type": "string" },
        "startedAt": { "type": "string" },
        "finishedAt": { "type": ["string", "null"] },
        "durationMs": { "type": "integer", "minimum": 0 },
        "outcome": { "$ref": "#/$defs/outcome" },
        "rollbackAvailable": { "type": "boolean" },
        "supportBundleAvailable": { "type": "boolean" }
      }
    }
  }
}
```

`api-runs-show.schema.json` reuses the run item's properties rather than `$ref`ing the whole object, because the detail document adds keys and `additionalProperties: false` would reject them:

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "$id": "https://jaspersoft.com/jrsctl/api-runs-show.schema.json",
  "title": "GET /api/runs/{id} (spec §13.1)",
  "type": "object",
  "additionalProperties": false,
  "required": ["id", "op", "target", "startedAt", "finishedAt", "durationMs", "outcome",
               "rollbackAvailable", "supportBundleAvailable", "title", "subtitle", "backups",
               "steps"],
  "properties": {
    "id": { "type": "string" },
    "op": { "type": "string" },
    "target": { "type": "string" },
    "startedAt": { "type": "string" },
    "finishedAt": { "type": ["string", "null"] },
    "durationMs": { "type": "integer", "minimum": 0 },
    "outcome": { "$ref": "https://jaspersoft.com/jrsctl/api-runs.schema.json#/$defs/outcome" },
    "rollbackAvailable": { "type": "boolean" },
    "supportBundleAvailable": { "type": "boolean" },
    "title": { "type": "string" },
    "subtitle": { "type": "string" },
    "backups": { "type": "string" },
    "steps": {
      "type": "array",
      "items": {
        "type": "object",
        "additionalProperties": false,
        "required": ["id", "phase", "title", "why", "status", "durationMs"],
        "properties": {
          "id": { "type": "string" },
          "phase": { "type": "string" },
          "title": { "type": "string" },
          "why": { "type": "string" },
          "status": {
            "enum": ["pending", "running", "succeeded", "failed", "skipped", "rolled_back",
                     "rollback_failed"]
          },
          "durationMs": { "type": ["integer", "null"], "minimum": 0 }
        }
      }
    },
    "failure": {
      "type": "object",
      "additionalProperties": false,
      "required": ["stepId", "cause", "backups", "nextAction"],
      "properties": {
        "stepId": { "type": "string" },
        "cause": { "type": "string" },
        "backups": { "type": "array", "items": { "type": "string" } },
        "nextAction": { "type": "string" }
      }
    }
  }
}
```

- [ ] **Step 5: Run the schema test to verify it passes**

Run: `scripts\mvn.cmd test -pl app -am -Dtest=ConsoleSchemaTest`
Expected: PASS, 12 tests.

A failure here is information, not an obstacle. It means the schema disagrees with what the code actually emits. Fix the schema, never the golden.

- [ ] **Step 6: Run the existing schema test to check nothing regressed**

Run: `scripts\mvn.cmd test -pl app -am -Dtest=JsonOutputSchemaTest`
Expected: PASS. The eight new files are now in `JsonSchemas.schemas()` and must load and parse.

- [ ] **Step 7: Commit**

```bash
scripts\mvn.cmd spotless:apply
git add app/src/main/resources/schema/json app/src/main/java/com/jaspersoft/jrsctl/app/JsonSchemas.java \
        app/src/test/java/com/jaspersoft/jrsctl/app/ConsoleSchemaTest.java
git commit -m "feat(app): publish JSON Schemas for the console API"
```

---

### Task 4: Migrate `/api/health` and `/api/doctor` to records

**Files:**
- Create: `app/src/main/java/com/jaspersoft/jrsctl/app/console/HealthDoc.java`
- Create: `app/src/main/java/com/jaspersoft/jrsctl/app/console/DoctorDoc.java`
- Modify: `app/src/main/java/com/jaspersoft/jrsctl/app/console/ConsoleViews.java`

**Interfaces:**
- Consumes: `RunViews.lastStep(List<Transition>)` returning `Optional<String>`; `DoctorCache.Cached(DoctorReport report, Instant ranAt)`; `ReportItem(String name, Status status, String detail, String remediation)`.
- Produces: `HealthDoc`, `DoctorDoc` and their nested records. `ConsoleViews.health()` returns `HealthDoc`; `ConsoleViews.doctor(DoctorCache.Cached)` returns `DoctorDoc`. `SupportBundle` calls the latter and needs no change, because `Json.writePretty` takes `Object`.

- [ ] **Step 1: Write `HealthDoc`**

```java
package com.jaspersoft.jrsctl.app.console;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The {@code GET /api/health} document (spec §13.1, {@code api-health.schema.json}). Invariants:
 * component order is the key order on the wire; {@code lastRun}, {@code finishedAt} and
 * {@code stepId} are emitted as {@code null} when absent because the front-end reads them
 * unconditionally, while {@code doctor}, {@code runId} and {@code pid} are omitted entirely, which
 * is what {@link JsonInclude.Include#NON_ABSENT} does to an empty {@code Optional}.
 */
record HealthDoc(
    Tool tool,
    String bind,
    String networkMode,
    Optional<LastRun> lastRun,
    Lock lock,
    List<PendingRun> pendingRuns,
    Snapshots snapshots,
    @JsonInclude(JsonInclude.Include.NON_ABSENT) Optional<DoctorSummary> doctor) {

  record Tool(String version, String matrixVersion) {}

  record LastRun(String id, String op, String outcome, Optional<Instant> finishedAt) {}

  record Lock(
      boolean held,
      @JsonInclude(JsonInclude.Include.NON_ABSENT) Optional<String> runId,
      @JsonInclude(JsonInclude.Include.NON_ABSENT) Optional<String> pid) {}

  record PendingRun(String id, String op, Instant startedAt, Optional<String> stepId) {}

  record Snapshots(int count, long bytes, int retentionDays) {}

  record DoctorSummary(int pass, int warn, int fail, Instant ranAt, List<Attention> attention) {}

  record Attention(String status, String title, String detail) {}
}
```

- [ ] **Step 2: Write `DoctorDoc`**

```java
package com.jaspersoft.jrsctl.app.console;

import java.time.Instant;
import java.util.List;

/**
 * The {@code GET /api/doctor} document (spec §13.1, {@code api-doctor.schema.json}). Invariants:
 * component order is the key order on the wire; every component is always present, because a
 * doctor report always has counts, items and an exit code; {@code id}, {@code name} and
 * {@code title} all carry {@link com.jaspersoft.jrsctl.ops.ReportItem#name()}, which the
 * front-end relies on and this record preserves rather than corrects.
 */
record DoctorDoc(Instant ranAt, Counts counts, List<Item> items, int exitCode) {

  record Counts(int pass, int warn, int fail, int skip) {}

  record Item(
      String id, String name, String status, String title, String detail, String remediation) {}
}
```

- [ ] **Step 3: Replace the two builders in `ConsoleViews`**

Change the return types and rewrite the bodies. Delete the now-unused `LinkedHashMap` import if nothing else in the file uses it.

```java
  HealthDoc health() {
    StateStore store = store();
    HealthDoc.Tool tool =
        new HealthDoc.Tool(
            Version.current().version(), Integer.toString(services.matrix().matrixVersion()));
    Optional<RunRecord> last =
        store.runs(RUN_LIMIT).stream().filter(r -> r.terminalState().isPresent()).findFirst();
    List<HealthDoc.PendingRun> pending = new ArrayList<>();
    for (RunRecord run : store.pendingRuns()) {
      pending.add(
          new HealthDoc.PendingRun(
              run.runId(),
              run.operation(),
              run.startedAt(),
              RunViews.lastStep(store.transitions(run.runId()))));
    }
    return new HealthDoc(
        tool,
        bind + ":" + port.getAsInt(),
        services.config().network().mode().yamlValue(),
        last.map(this::lastRun),
        lock(store),
        pending,
        snapshots(),
        doctor.last().map(ConsoleViews::doctorSummary));
  }

  private HealthDoc.LastRun lastRun(RunRecord run) {
    return new HealthDoc.LastRun(run.runId(), run.operation(), outcome(run), run.endedAt());
  }

  private HealthDoc.Lock lock(StateStore store) {
    Optional<RunManager.LiveRun> live = runs.running();
    if (live.isPresent()) {
      return new HealthDoc.Lock(
          true,
          Optional.of(live.get().runId()),
          Optional.of(Long.toString(ProcessHandle.current().pid())));
    }
    Optional<RunLock.Holder> holder = RunLock.readHolder(services.home().runLock());
    boolean alive =
        holder
            .flatMap(
                h -> {
                  try {
                    return ProcessHandle.of(Long.parseLong(h.pid())).map(ProcessHandle::isAlive);
                  } catch (NumberFormatException e) {
                    return Optional.<Boolean>empty();
                  }
                })
            .orElse(false);
    boolean held =
        alive
            && holder
                .map(h -> store.run(h.runId()).map(RunRecord::pending).orElse(true))
                .orElse(false);
    return held
        ? new HealthDoc.Lock(
            true, holder.map(RunLock.Holder::runId), holder.map(RunLock.Holder::pid))
        : new HealthDoc.Lock(false, Optional.empty(), Optional.empty());
  }

  private HealthDoc.Snapshots snapshots() {
    SnapshotStore snapshots =
        new SnapshotStore(services.home(), services.platform().files(), services.clock());
    int count = 0;
    long bytes = 0;
    try {
      count = snapshots.list().size();
      bytes = snapshots.totalBytes();
    } catch (IOException | RuntimeException e) {
      LOG.debug("cannot size the snapshot store: {}", e.getMessage());
    }
    return new HealthDoc.Snapshots(count, bytes, services.config().backups().retentionDays());
  }

  private static HealthDoc.DoctorSummary doctorSummary(DoctorCache.Cached cached) {
    DoctorReport report = cached.report();
    List<HealthDoc.Attention> attention = new ArrayList<>();
    for (ReportItem item : report.items()) {
      if (item.status() == ReportItem.Status.WARN || item.status() == ReportItem.Status.FAIL) {
        attention.add(
            new HealthDoc.Attention(item.status().name(), item.name(), item.detail()));
      }
    }
    return new HealthDoc.DoctorSummary(
        report.counts().pass(),
        report.counts().warn(),
        report.counts().fail(),
        cached.ranAt(),
        attention);
  }

  static DoctorDoc doctor(DoctorCache.Cached cached) {
    DoctorReport report = cached.report();
    List<DoctorDoc.Item> items = new ArrayList<>();
    for (ReportItem item : report.items()) {
      items.add(
          new DoctorDoc.Item(
              item.name(),
              item.name(),
              item.status().name(),
              item.name(),
              item.detail(),
              item.remediation()));
    }
    return new DoctorDoc(
        cached.ranAt(),
        new DoctorDoc.Counts(
            report.counts().pass(),
            report.counts().warn(),
            report.counts().fail(),
            report.counts().skip()),
        items,
        report.exitCode());
  }
```

Note the behaviour change hidden in `health()`: the old code used `doctor.last().ifPresent(...)` to omit the key, and the new code uses an empty `Optional` with `NON_ABSENT`. Those are the same on the wire. The golden proves it.

- [ ] **Step 4: Run the goldens to verify the wire did not move**

Run: `scripts\mvn.cmd test -pl app -am -Dtest=ConsoleWireGoldenTest`
Expected: PASS. If `health-fresh.json` or `doctor.json` differs, the record is wrong. Read the diff, fix the record, and never edit the golden.

- [ ] **Step 5: Run the console tests**

Run: `scripts\mvn.cmd test -pl app -am -Dtest=ConsoleServerTest+ConsoleHardeningTest+ConsoleSchemaTest`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
scripts\mvn.cmd spotless:apply
git add app/src/main/java/com/jaspersoft/jrsctl/app/console/HealthDoc.java \
        app/src/main/java/com/jaspersoft/jrsctl/app/console/DoctorDoc.java \
        app/src/main/java/com/jaspersoft/jrsctl/app/console/ConsoleViews.java
git commit -m "refactor(app): build the health and doctor documents from records"
```

---

### Task 5: Migrate `/api/server` to records

**Files:**
- Create: `app/src/main/java/com/jaspersoft/jrsctl/app/console/ServerDoc.java`
- Modify: `app/src/main/java/com/jaspersoft/jrsctl/app/console/ServerViews.java`
- Modify: `app/src/main/java/com/jaspersoft/jrsctl/app/console/ConsoleViews.java` (the `server()` delegate's return type)

**Interfaces:**
- Consumes: `Config`, `ServerIdentity`, `KeystoreInfo`, `ServiceController`, all unchanged.
- Produces: `ServerDoc` and its nested records. `ServerViews.server()` returns `ServerDoc`; `ServerViews.service(Config)` returns `ServerDoc.Service`. `SupportBundle` needs no change.

- [ ] **Step 1: Write `ServerDoc`**

```java
package com.jaspersoft.jrsctl.app.console;

/**
 * The {@code GET /api/server} document (spec §13.1, {@code api-server.schema.json}). Invariants:
 * component order is the key order on the wire; every component is always present, and an unknown
 * value is the empty string rather than {@code null}, so the document degrades to the
 * configuration-known fields with {@code reachable:false} instead of failing when the server
 * cannot be reached.
 */
record ServerDoc(
    String product,
    String version,
    String edition,
    String tenancy,
    Database database,
    String baseUrl,
    String installDir,
    Service service,
    Keystore keystore,
    String networkMode,
    boolean reachable) {

  record Database(String vendor, String version) {}

  record Service(String kind, String name, String state) {}

  record Keystore(boolean present, String user) {}
}
```

- [ ] **Step 2: Rewrite `ServerViews`**

```java
  ServerDoc server() {
    Config config = services.config();
    Optional<JrsAdapter> adapter = Optional.empty();
    Optional<ServerIdentity> identity = Optional.empty();
    try {
      adapter = Optional.of(services.adapter().get());
      identity = Optional.of(adapter.get().identity());
    } catch (RuntimeException e) {
      LOG.debug("server not reachable for /api/server: {}", e.getMessage());
    }
    Optional<KeystoreInfo> info = Optional.empty();
    if (adapter.isPresent()) {
      try {
        info = Optional.of(adapter.get().keystore());
      } catch (RuntimeException e) {
        LOG.debug("keystore not inspectable: {}", e.getMessage());
      }
    }
    return new ServerDoc(
        "JasperReports Server",
        identity.map(ServerIdentity::version).orElse(""),
        identity.map(i -> i.edition().name()).orElse(""),
        identity
            .map(i -> i.tenancy() == ServerIdentity.Tenancy.MULTI ? "multi-tenant" : "single-tenant")
            .orElse(""),
        new ServerDoc.Database(
            config.database().type().map(Config.DatabaseType::yamlValue).orElse(""), ""),
        identity
            .map(i -> i.baseUrl().toString())
            .or(() -> config.server().baseUrl().map(Object::toString))
            .orElse(""),
        config.server().installDir().map(Path::toString).orElse(""),
        service(config),
        new ServerDoc.Keystore(
            info.map(KeystoreInfo::present).orElse(false),
            config.server().runAsUser().orElse("")),
        config.network().mode().yamlValue(),
        identity.isPresent());
  }

  ServerDoc.Service service(Config config) {
    String state = "";
    if (config.service().kind().isPresent()) {
      try {
        ServiceController controller = services.platform().services(config.toServiceConfig());
        state = controller.state().name().toLowerCase(Locale.ROOT);
      } catch (RuntimeException e) {
        LOG.debug("service state unavailable: {}", e.getMessage());
      }
    }
    return new ServerDoc.Service(
        config.service().kind().map(Config.Service::kindToYaml).orElse(""),
        config.service().name().orElse(""),
        state);
  }
```

Remove the now-unused `LinkedHashMap` and `Map` imports.

- [ ] **Step 3: Change the delegate in `ConsoleViews`**

```java
  ServerDoc server() {
    return serverViews.server();
  }
```

- [ ] **Step 4: Run the goldens**

Run: `scripts\mvn.cmd test -pl app -am -Dtest=ConsoleWireGoldenTest`
Expected: PASS. `server-unreachable.json` must be byte-identical.

- [ ] **Step 5: Run the console tests**

Run: `scripts\mvn.cmd test -pl app -am -Dtest=ConsoleServerTest+ConsoleSchemaTest`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
scripts\mvn.cmd spotless:apply
git add app/src/main/java/com/jaspersoft/jrsctl/app/console/ServerDoc.java \
        app/src/main/java/com/jaspersoft/jrsctl/app/console/ServerViews.java \
        app/src/main/java/com/jaspersoft/jrsctl/app/console/ConsoleViews.java
git commit -m "refactor(app): build the server document from records"
```

---

### Task 6: Migrate `/api/hotfixes` to records

**Files:**
- Create: `app/src/main/java/com/jaspersoft/jrsctl/app/console/HotfixesDoc.java`
- Modify: `app/src/main/java/com/jaspersoft/jrsctl/app/console/HotfixViews.java`
- Modify: `app/src/main/java/com/jaspersoft/jrsctl/app/console/ConsoleViews.java` (the `hotfixes()` delegate's return type)

**Interfaces:**
- Consumes: `HotfixInstalled(String id, String version, String title, String installedRunId, Optional<String> snapshotRef, HotfixState state, Instant installedAt)`, `HotfixFile`.
- Produces: `HotfixesDoc` and `HotfixesDoc.Row`. `HotfixViews.hotfixes()` returns `HotfixesDoc`.

- [ ] **Step 1: Write `HotfixesDoc`**

```java
package com.jaspersoft.jrsctl.app.console;

import java.time.Instant;
import java.util.List;

/**
 * The {@code GET /api/hotfixes} document (spec §13.1, {@code api-hotfixes.schema.json}).
 * Invariants: component order is the key order on the wire; {@code files} is a count, not a list,
 * because the console only shows how many files a hotfix touched; {@code blockedBy} is empty
 * rather than absent, and lists the later installed hotfixes that share a file with this one, so
 * a non-empty list means this hotfix cannot be rolled back on its own.
 */
record HotfixesDoc(List<Row> hotfixes) {

  record Row(
      String id,
      String title,
      Instant installedAt,
      int files,
      String state,
      List<String> blockedBy) {}
}
```

- [ ] **Step 2: Rewrite `HotfixViews.hotfixes`**

Only the row construction changes; the blocking calculation is untouched.

```java
  HotfixesDoc hotfixes() {
    StateStore store = store();
    List<HotfixInstalled> all = store.hotfixes();
    Map<String, Set<String>> files = new HashMap<>();
    for (HotfixInstalled h : all) {
      Set<String> paths = new HashSet<>();
      for (HotfixFile f : store.hotfixFiles(h.id())) {
        paths.add(f.path().toString());
      }
      files.put(h.id(), paths);
    }
    List<HotfixesDoc.Row> rows = new ArrayList<>();
    for (int i = 0; i < all.size(); i++) {
      HotfixInstalled h = all.get(i);
      List<String> blockedBy = new ArrayList<>();
      if (h.state() == HotfixState.INSTALLED) {
        for (int j = i + 1; j < all.size(); j++) {
          HotfixInstalled later = all.get(j);
          if (later.state() == HotfixState.INSTALLED
              && files.get(later.id()).stream().anyMatch(files.get(h.id())::contains)) {
            blockedBy.add(later.id());
          }
        }
      }
      rows.add(
          new HotfixesDoc.Row(
              h.id(),
              h.title(),
              h.installedAt(),
              files.get(h.id()).size(),
              h.state().name().toLowerCase(Locale.ROOT),
              blockedBy));
    }
    return new HotfixesDoc(rows);
  }
```

Remove the now-unused `LinkedHashMap` import.

- [ ] **Step 3: Change the delegate in `ConsoleViews`**

```java
  HotfixesDoc hotfixes() {
    return hotfixViews.hotfixes();
  }
```

- [ ] **Step 4: Run the goldens and the console tests**

Run: `scripts\mvn.cmd test -pl app -am -Dtest=ConsoleWireGoldenTest+ConsoleServerTest+ConsoleSchemaTest`
Expected: PASS. Both `hotfixes-empty.json` and `hotfixes-installed.json` must be byte-identical.

- [ ] **Step 5: Commit**

```bash
scripts\mvn.cmd spotless:apply
git add app/src/main/java/com/jaspersoft/jrsctl/app/console/HotfixesDoc.java \
        app/src/main/java/com/jaspersoft/jrsctl/app/console/HotfixViews.java \
        app/src/main/java/com/jaspersoft/jrsctl/app/console/ConsoleViews.java
git commit -m "refactor(app): build the hotfixes document from records"
```

---

### Task 7: Migrate the plan and run documents to records

The hard one. `runDetail` is currently built by adding five keys to the map `runItem` returns; records cannot inherit, so `RunDetail` declares the nine base components plus its own five and is assembled by a static factory.

**Files:**
- Create: `app/src/main/java/com/jaspersoft/jrsctl/app/console/PlanDoc.java`
- Create: `app/src/main/java/com/jaspersoft/jrsctl/app/console/RunsDoc.java`
- Modify: `app/src/main/java/com/jaspersoft/jrsctl/app/console/RunViews.java`
- Modify: `app/src/main/java/com/jaspersoft/jrsctl/app/console/ConsoleViews.java` (four delegates' return types)

**Interfaces:**
- Consumes: `Plan`, `PlanSummary`, `Step`, `StoredPlan`, `RunRecord`, `Transition`, `SnapshotRecord`, `Event`, all unchanged. `RunViews.files(List<Path>)`, `RunViews.rollbackPoints(PlanSummary)`, `RunViews.label(String)`, `RunViews.stepStatus(StepState)`, `RunViews.lastStep(List<Transition>)` keep their current signatures.
- Produces: `PlanDoc`, `RunsDoc.RunList`, `RunsDoc.RunItem`, `RunsDoc.RunDetail`, `RunsDoc.StepRow`, `RunsDoc.Failure`, and `RunsDoc.RunDetail.of(RunItem, String, String, String, List<StepRow>, Optional<Failure>)`.

- [ ] **Step 1: Write `PlanDoc`**

```java
package com.jaspersoft.jrsctl.app.console;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The {@code POST /api/plan} response (spec §13.1, {@code api-plan.schema.json}). Invariants:
 * component order is the key order on the wire; every {@link Summary} value is a display string
 * the console renders verbatim, so objects and lists are flattened before they get here, with
 * {@code warnings} the one list because the front-end renders it as callouts;
 * {@code resourcesTouched} is omitted rather than empty when the plan touches no repository
 * resources.
 */
record PlanDoc(String planId, PlanBody plan) {

  record PlanBody(
      String op,
      String title,
      String fingerprint,
      Instant validUntil,
      Summary summary,
      List<PlanStep> steps) {}

  record Summary(
      String filesTouched,
      @JsonInclude(JsonInclude.Include.NON_ABSENT) Optional<String> resourcesTouched,
      String service,
      String backups,
      String rollbackPoints,
      String strategy,
      String downtime,
      List<String> warnings) {}

  record PlanStep(String id, String phase, String title, String why) {}
}
```

- [ ] **Step 2: Write `RunsDoc`**

```java
package com.jaspersoft.jrsctl.app.console;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The {@code GET /api/runs} and {@code GET /api/runs/{id}} documents (spec §13.1,
 * {@code api-runs.schema.json} and {@code api-runs-show.schema.json}). Invariants: component order
 * is the key order on the wire; {@link RunDetail} repeats {@link RunItem}'s nine components in the
 * same order because a record cannot extend another and {@code @JsonUnwrapped} is unreliable on
 * record components, so {@link RunDetail#of} is the only way it should be built; {@code failure}
 * is omitted for a run that succeeded or is still running; {@code finishedAt} and a step's
 * {@code durationMs} are emitted as {@code null} while unknown, because the front-end reads them
 * unconditionally.
 */
final class RunsDoc {

  private RunsDoc() {}

  record RunList(List<RunItem> runs) {}

  record RunItem(
      String id,
      String op,
      String target,
      Instant startedAt,
      Optional<Instant> finishedAt,
      long durationMs,
      String outcome,
      boolean rollbackAvailable,
      boolean supportBundleAvailable) {}

  record RunDetail(
      String id,
      String op,
      String target,
      Instant startedAt,
      Optional<Instant> finishedAt,
      long durationMs,
      String outcome,
      boolean rollbackAvailable,
      boolean supportBundleAvailable,
      String title,
      String subtitle,
      String backups,
      List<StepRow> steps,
      @JsonInclude(JsonInclude.Include.NON_ABSENT) Optional<Failure> failure) {

    /** The list item plus the detail-only keys, in the order the console README documents. */
    static RunDetail of(
        RunItem base,
        String title,
        String subtitle,
        String backups,
        List<StepRow> steps,
        Optional<Failure> failure) {
      return new RunDetail(
          base.id(),
          base.op(),
          base.target(),
          base.startedAt(),
          base.finishedAt(),
          base.durationMs(),
          base.outcome(),
          base.rollbackAvailable(),
          base.supportBundleAvailable(),
          title,
          subtitle,
          backups,
          steps,
          failure);
    }
  }

  record StepRow(
      String id,
      String phase,
      String title,
      String why,
      String status,
      Optional<Long> durationMs) {}

  record Failure(String stepId, String cause, List<String> backups, String nextAction) {}
}
```

- [ ] **Step 3: Rewrite the builders in `RunViews`**

`planResponse`:

```java
  PlanDoc planResponse(StoredPlan stored, Plan plan) {
    PlanSummary s = plan.summary();
    PlanDoc.Summary summary =
        new PlanDoc.Summary(
            files(s.filesTouched()),
            s.resourcesTouched().isEmpty()
                ? Optional.empty()
                : Optional.of(String.join(", ", s.resourcesTouched())),
            s.serviceRestart()
                ? "Stop and start the service; the plan changes files that require it"
                : "No service restart",
            s.backupLocations().isEmpty()
                ? "none"
                : String.join(", ", s.backupLocations().stream().map(Path::toString).toList()),
            rollbackPoints(s),
            s.strategy().isBlank() ? "-" : s.strategy(),
            s.serviceRestart()
                ? "Running stops the server while the files are swapped."
                : "No downtime expected.",
            s.warnings());
    List<PlanDoc.PlanStep> steps = new ArrayList<>();
    for (Step step : plan.steps()) {
      steps.add(
          new PlanDoc.PlanStep(
              step.id(),
              step.phase(),
              step.irreversible() ? step.title() + " (irreversible)" : step.title(),
              step.detail()));
    }
    return new PlanDoc(
        stored.planId(),
        new PlanDoc.PlanBody(
            stored.operation(),
            "Plan: " + label(s.operation()) + " " + s.target(),
            plan.fingerprint().value(),
            stored.expiresAt(),
            summary,
            steps));
  }
```

Watch the key order here. The old code put `resourcesTouched` immediately after `filesTouched` and only when non-empty; the record keeps that position and omits it when empty, which is the same wire.

`runList`, `runItem` and `runDetail`:

```java
  RunsDoc.RunList runList() {
    List<RunsDoc.RunItem> items = new ArrayList<>();
    for (RunRecord run : store().runs(RUN_LIMIT)) {
      items.add(runItem(run));
    }
    return new RunsDoc.RunList(items);
  }

  RunsDoc.RunItem runItem(RunRecord run) {
    Optional<JsonNode> plan = planTree(run);
    return new RunsDoc.RunItem(
        run.runId(),
        run.operation(),
        plan.map(t -> t.path("summary").path("target").asText("")).orElse(""),
        run.startedAt(),
        run.endedAt(),
        Duration.between(run.startedAt(), run.endedAt().orElseGet(services.clock()::instant))
            .toMillis(),
        outcome(run),
        rollbackAvailable(run),
        true);
  }

  RunsDoc.RunDetail runDetail(RunRecord run) {
    StateStore store = store();
    Optional<JsonNode> plan = planTree(run);
    List<Transition> transitions = store.transitions(run.runId());
    List<SnapshotRecord> snapshots = store.snapshots(run.runId());
    String target = plan.map(t -> t.path("summary").path("target").asText("")).orElse("");
    String strategy = plan.map(t -> t.path("summary").path("strategy").asText("")).orElse("");
    return RunsDoc.RunDetail.of(
        runItem(run),
        label(run.operation()) + (target.isEmpty() ? "" : " " + target),
        strategy.isEmpty() ? run.runId() : run.runId() + " · " + strategy,
        backups(plan, snapshots),
        steps(plan, transitions),
        failure(run, transitions, snapshots));
  }
```

`steps`, `stepRow` and `failure`. Only the construction changes; the status, duration and phase bookkeeping is untouched.

```java
  static List<RunsDoc.StepRow> steps(Optional<JsonNode> plan, List<Transition> transitions) {
    Map<String, String> status = new LinkedHashMap<>();
    Map<String, Instant> running = new HashMap<>();
    Map<String, Long> durations = new HashMap<>();
    Map<String, String> phases = new HashMap<>();
    for (Transition t : transitions) {
      StepState to;
      try {
        to = StepState.valueOf(t.toState());
      } catch (IllegalArgumentException unknown) {
        continue;
      }
      phases.put(t.stepId(), t.phase());
      status.put(t.stepId(), stepStatus(to));
      switch (to) {
        case RUNNING -> running.putIfAbsent(t.stepId(), t.ts());
        case SUCCEEDED, FAILED -> {
          Instant from = running.get(t.stepId());
          if (from != null) {
            durations.put(t.stepId(), Math.max(0, Duration.between(from, t.ts()).toMillis()));
          }
        }
        case PENDING, SKIPPED, ROLLED_BACK, ROLLBACK_FAILED -> {}
      }
    }
    List<RunsDoc.StepRow> out = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    if (plan.isPresent()) {
      for (JsonNode step : plan.get().path("steps")) {
        String id = step.path("id").asText();
        seen.add(id);
        out.add(
            stepRow(
                id,
                step.path("phase").asText(),
                step.path("title").asText(id),
                step.path("detail").asText(""),
                status.getOrDefault(id, "pending"),
                durations.get(id)));
      }
    }
    for (Map.Entry<String, String> e : status.entrySet()) {
      if (seen.add(e.getKey())) {
        out.add(
            stepRow(
                e.getKey(),
                phases.getOrDefault(e.getKey(), ""),
                e.getKey(),
                "",
                e.getValue(),
                durations.get(e.getKey())));
      }
    }
    return out;
  }

  static RunsDoc.StepRow stepRow(
      String id, String phase, String title, String why, String status, Long durationMs) {
    return new RunsDoc.StepRow(
        id, phase, title, why, status, Optional.ofNullable(durationMs));
  }
```

`failure` keeps its whole body up to the final construction. Change only the return type and the last four lines:

```java
  Optional<RunsDoc.Failure> failure(
      RunRecord run, List<Transition> transitions, List<SnapshotRecord> snapshots) {
    // ... every line from `String outcome = outcome(run);` down to the
    // `if (cause.isEmpty() && failedStep.isEmpty())` guard is unchanged ...
    return Optional.of(
        new RunsDoc.Failure(failedStep.orElse(""), cause, backups, nextAction));
  }
```

- [ ] **Step 4: Change the four delegates in `ConsoleViews`**

```java
  PlanDoc planResponse(StoredPlan stored, Plan plan) {
    return runViews.planResponse(stored, plan);
  }

  RunsDoc.RunList runList() {
    return runViews.runList();
  }

  RunsDoc.RunItem runItem(RunRecord run) {
    return runViews.runItem(run);
  }

  RunsDoc.RunDetail runDetail(RunRecord run) {
    return runViews.runDetail(run);
  }
```

- [ ] **Step 5: Compile and fix the callers**

Run: `scripts\mvn.cmd -q compile -pl app -am`
Expected: PASS. `ConsoleApi` and `SupportBundle` pass these values to parameters typed `Object`, so they should need no change. If `ConsoleApi` reads a key off `runItem` anywhere, replace the map access with the record accessor.

- [ ] **Step 6: Run the goldens**

Run: `scripts\mvn.cmd test -pl app -am -Dtest=ConsoleWireGoldenTest`
Expected: PASS. All four plan and run goldens byte-identical, `runs-show-failed.json` included.

- [ ] **Step 7: Run the whole console surface**

Run: `scripts\mvn.cmd test -pl app -am -Dtest=ConsoleServerTest+ConsoleHardeningTest+ConsoleSchemaTest`
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
scripts\mvn.cmd spotless:apply
git add app/src/main/java/com/jaspersoft/jrsctl/app/console/PlanDoc.java \
        app/src/main/java/com/jaspersoft/jrsctl/app/console/RunsDoc.java \
        app/src/main/java/com/jaspersoft/jrsctl/app/console/RunViews.java \
        app/src/main/java/com/jaspersoft/jrsctl/app/console/ConsoleViews.java
git commit -m "refactor(app): build the plan and run documents from records"
```

---

### Task 8: Validate live responses, correct the README, record the status

Closes the loop. The goldens prove the migration changed nothing; this task proves the schemas hold against a running server rather than only against fixtures, and brings the published contract into line.

**Files:**
- Modify: `app/src/test/java/com/jaspersoft/jrsctl/app/ConsoleServerTest.java`
- Modify: `app/src/main/resources/web/README.md`
- Modify: `docs/BUILD_STATUS.md`

**Interfaces:**
- Consumes: `JsonSchemas.forEndpoint(String)` from Task 3.
- Produces: nothing further tasks depend on.

- [ ] **Step 1: Add a schema assertion helper to `ConsoleServerTest`**

Place it next to the existing `json(HttpResponse)` helper.

```java
  /** Fails when a live response does not match the schema published for its endpoint. */
  private static JsonNode validated(String endpoint, HttpResponse<String> response)
      throws Exception {
    assertThat(response.statusCode()).as(endpoint).isEqualTo(200);
    JsonNode doc = json(response);
    String schemaName = JsonSchemas.forEndpoint(endpoint).orElseThrow();
    com.networknt.schema.JsonSchema schema =
        com.networknt.schema.JsonSchemaFactory.getInstance(
                com.networknt.schema.SpecVersion.VersionFlag.V202012)
            .getSchema(
                Json.mapper()
                    .readTree(
                        ConsoleServerTest.class.getResourceAsStream(
                            "/schema/json/" + schemaName)));
    assertThat(schema.validate(doc)).as(endpoint).isEmpty();
    return doc;
  }
```

Loading the schema from the classpath stream rather than by `$id` avoids configuring a resolver here. `api-runs-show.schema.json` and `api-health.schema.json` both `$ref` into `api-runs.schema.json` by absolute IRI, so if that resolution fails in this simpler setup, copy the resolver configuration from `JsonOutputSchemaTest` instead of inlining the factory.

- [ ] **Step 2: Route the existing reads through it**

In the tests that already fetch these endpoints, replace the `json(get(...))` call with `validated(...)`. There are seven sites, at roughly lines 299, 315, 332, 342, 349, 351, 405 and 419.

```java
    JsonNode s = validated("GET /api/server", get("/api/server"));
    JsonNode doctor = validated("GET /api/doctor", get("/api/doctor"));
    JsonNode hotfixes = validated("GET /api/hotfixes", get("/api/hotfixes"));
    JsonNode health = validated("GET /api/health", get("/api/health"));
    JsonNode detail = validated("GET /api/runs/{id}", get("/api/runs/" + runId));
    JsonNode runs = validated("GET /api/runs", get("/api/runs"));
```

Leave every existing assertion in place. They are the check that the migration did not change meaning; the schema check is the check that it did not change shape.

- [ ] **Step 3: Run the console tests**

Run: `scripts\mvn.cmd test -pl app -am -Dtest=ConsoleServerTest`
Expected: PASS.

- [ ] **Step 4: Correct `web/README.md`**

Three edits, all in the API contract section.

In the `GET /api/server` example, add `reachable` and note the empty version:

```json
  "keystore": {"present": true, "user": "jasperserver"},
  "networkMode": "isolated",
  "reachable": true
}
```

Add below that block: `database.version` is always the empty string today; the vendor comes from the configuration and no version probe runs.

In the `POST /api/plan` response example, delete the `"database"` and `"requires"` lines from `summary`, which nothing emits.

Replace the sentence beginning "Summary values are strings (objects and arrays are flattened for display); unknown string keys are shown too." with:

> Summary values are display strings; objects and arrays are flattened before they are sent, and
> `warnings` is the one list. The server emits a fixed key set, documented by
> `api-plan.schema.json`. The front-end renders any unknown string key it is given, so adding a
> key is a compatible change.

Add a line to the section header area recording where the schemas live:

> Every document below has a published JSON Schema in the jar under `schema/json/api-*.json`;
> `ConsoleSchemaTest` and `ConsoleServerTest` validate the real responses against them.

- [ ] **Step 5: Update `docs/BUILD_STATUS.md`**

Add to the Phase 6 row's acceptance column, or as a bullet under Phase 8's `--json` section, a sentence recording the new surface:

> The console API is typed and published too: every `/api/*` document is an immutable record in
> `app.console`, eight schemas ship under `schema/json/api-*.json`, and golden files in
> `app/src/test/resources/console-wire/` freeze the wire format that the record migration of
> 2026-09-12 had to preserve.

- [ ] **Step 6: Run the full phase 6 gate**

Run: `scripts\mvn.cmd verify -Dphase=6`
Expected: PASS, including `Phase6ConsoleTest` against the shaded jar.

- [ ] **Step 7: Run the whole build**

Run: `scripts\mvn.cmd clean verify`
Expected: BUILD SUCCESS. Use `clean`, because an incremental `verify` reuses `app/target/jrsctl.jar` and acceptance can otherwise pass against a jar built before this change. That trap is documented in `docs/BUILD_STATUS.md`.

Watch the `app` module's Jacoco line coverage. Records add generated accessors, and the floor is `jacoco.line.minimum` in `app/pom.xml`. If the build fails on coverage, the fix is a test that exercises the uncovered branch, not a lowered floor.

- [ ] **Step 8: Commit**

```bash
scripts\mvn.cmd spotless:apply
git add app/src/test/java/com/jaspersoft/jrsctl/app/ConsoleServerTest.java \
        app/src/main/resources/web/README.md docs/BUILD_STATUS.md
git commit -m "test(app): validate live console responses against the published schemas"
```

---

## Self-Review

**Spec coverage.** Every section of the design maps to a task. The record layout and component shapes are Tasks 4 to 7. The null-versus-absent table is enforced by the goldens of Tasks 1 and 2 and encoded in the schemas of Task 3. The two structural problems are Task 7, `RunDetail.of` for the inheritance and a closed `Summary` record for the plan summary. The schema registry and the eight files are Task 3. The four-step testing plan is Tasks 1, 2, 3 and 8 in that order. The three README corrections are Task 8. The golden-branch-coverage risk is Task 2, which captures a failed run, a run in flight, an empty and a populated hotfix list, and a plan with and without `resourcesTouched`. The Jacoco risk is Task 8 Step 7.

**Placeholder scan.** No TBDs. Every code step carries the code. Two steps name a fallback rather than one exact API call: the networknt loader configuration in Task 3 Step 1 and Task 8 Step 1, both of which point at `JsonOutputSchemaTest` as the working reference in this repository. Task 2 Step 4 names a fallback if `FakeHotfixOperations` does not fail on an `HF-0002` path. Those are genuine unknowns about existing code an implementer must read, not gaps in the plan.

**Type consistency.** `RunViews.stepRow` keeps its `Long durationMs` parameter and wraps it in `Optional.ofNullable`, so its one caller `steps` is unchanged. `RunViews.lastStep` returns `Optional<String>` and feeds `HealthDoc.PendingRun.stepId`, which is `Optional<String>`. `DoctorCache.Cached.report()` and `.ranAt()` feed both `HealthDoc.DoctorSummary` and `DoctorDoc`. `ReportItem.detail()` and `.remediation()` are plain `String` in both. `ServerViews.service` returns `ServerDoc.Service` and is called only from `ServerViews.server`. `JsonSchemas.forEndpoint` and `.endpoints()` are defined in Task 3 and used in Tasks 3 and 8 with the same signatures.
