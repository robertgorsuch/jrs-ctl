package com.jaspersoft.jrsctl.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.jaspersoft.jrsctl.core.json.Json;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Every value-exact console golden validates against the schema published for its endpoint.
 * Invariants: {@link #goldens()} is checked against the golden directory itself (see {@code
 * should_schema_validate_every_value_exact_golden_when_goldens_are_listed}), so a new value-exact
 * golden that nobody adds here fails the build instead of silently going unchecked; a golden that
 * no schema accepts also fails the build, so the records, the schemas and the wire cannot drift
 * apart. The two structural goldens ({@code doctor}, {@code health-doctor-cached}) are scrubbed to
 * type tokens rather than values and so cannot be schema-validated here; {@link ConsoleServerTest}
 * validates those two endpoints against the same schemas using live responses instead.
 */
class ConsoleSchemaTest {

  /** Where {@link ConsoleWireGoldenTest} writes and reads the golden files. */
  private static final String DIR = "src/test/resources/console-wire/";

  /**
   * Golden names that probe the real host and so are captured as type tokens rather than values
   * ({@link ConsoleWireGoldenTest}); they cannot be schema-validated from a file and are proven
   * live instead, by {@link ConsoleServerTest}.
   */
  private static final Set<String> STRUCTURAL_GOLDENS = Set.of("doctor", "health-doctor-cached");

  /** Resolves every {@code https://jaspersoft.com/jrsctl/...} reference from the classpath. */
  static final JsonSchemaFactory FACTORY =
      JsonSchemaFactory.getInstance(
          SpecVersion.VersionFlag.V202012,
          b -> b.schemaLoaders(l -> l.schemas(ConsoleSchemaTest::schemaText)));

  static Stream<Arguments> goldens() {
    return Stream.of(
        Arguments.of("health-fresh", "api-health.schema.json"),
        Arguments.of("health-after-apply", "api-health.schema.json"),
        Arguments.of("health-run-in-flight", "api-health.schema.json"),
        Arguments.of("health-pending-no-steps", "api-health.schema.json"),
        Arguments.of("server-unreachable", "api-server.schema.json"),
        Arguments.of("server-reachable", "api-server.schema.json"),
        Arguments.of("hotfixes-empty", "api-hotfixes.schema.json"),
        Arguments.of("hotfixes-installed", "api-hotfixes.schema.json"),
        Arguments.of("plan-hotfix-apply", "api-plan.schema.json"),
        Arguments.of("plan-import", "api-plan.schema.json"),
        Arguments.of("run-started", "api-run-started.schema.json"),
        Arguments.of("runs-after-apply", "api-runs.schema.json"),
        Arguments.of("runs-show-succeeded", "api-runs-show.schema.json"),
        Arguments.of("runs-show-failed", "api-runs-show.schema.json"),
        Arguments.of("runs-show-running", "api-runs-show.schema.json"),
        Arguments.of("runs-show-interrupted", "api-runs-show.schema.json"));
  }

  @ParameterizedTest(name = "{0} validates against {1}")
  @MethodSource("goldens")
  void should_validate_the_golden_against_its_schema(String golden, String schemaName)
      throws Exception {
    JsonNode doc = Json.mapper().readTree(Files.readString(Path.of(DIR + golden + ".json")));
    JsonSchema schema = FACTORY.getSchema(SchemaLocation.of(JsonSchemas.iri(schemaName)));
    Set<ValidationMessage> errors = schema.validate(doc);
    assertThat(errors).as(golden + " against " + schemaName).isEmpty();
  }

  /**
   * {@link #goldens()} is a hand-maintained list; nothing else ties it to the golden directory, so
   * a new value-exact golden that nobody adds to it would otherwise pass the build unvalidated.
   * This lists the directory itself and asserts the two agree, once the known structural goldens
   * are set aside.
   */
  @Test
  void should_schema_validate_every_value_exact_golden_when_goldens_are_listed()
      throws IOException {
    Set<String> onDisk = new TreeSet<>();
    try (Stream<Path> files = Files.list(Path.of(DIR))) {
      files
          .map(p -> p.getFileName().toString())
          .filter(name -> name.endsWith(".json"))
          .map(name -> name.substring(0, name.length() - ".json".length()))
          .forEach(onDisk::add);
    }
    onDisk.removeAll(STRUCTURAL_GOLDENS);

    Set<String> listed = new HashSet<>();
    goldens().forEach(args -> listed.add((String) args.get()[0]));

    assertThat(listed)
        .as("goldens() must list exactly the value-exact files under " + DIR)
        .isEqualTo(onDisk);
  }

  @Test
  void should_publish_a_schema_for_every_console_endpoint() {
    for (String endpoint : JsonSchemas.endpoints()) {
      String name = JsonSchemas.forEndpoint(endpoint).orElseThrow();
      assertThat(JsonSchemas.open(name)).as(endpoint).isPresent();
    }
  }

  /**
   * The check above walks the map, so a route registered in {@code ConsoleApi} without a map entry
   * was never noticed (assessment item B5). This one walks the router: every {@code app.get} and
   * {@code app.post} on {@code /api} must have a schema, except the support bundle, which is a zip.
   */
  @Test
  void should_map_every_json_route_the_console_registers() throws IOException {
    Path source =
        Path.of("src", "main", "java", "com", "jaspersoft", "jrsctl", "app", "console")
            .resolve("ConsoleApi.java");
    String text = Files.readString(source, StandardCharsets.UTF_8);
    Matcher routes = Pattern.compile("app\\.(get|post)\\(\"(/api/[^\"]+)\"").matcher(text);
    List<String> registered = new ArrayList<>();
    List<String> missing = new ArrayList<>();
    while (routes.find()) {
      String endpoint = routes.group(1).toUpperCase(Locale.ROOT) + " " + routes.group(2);
      registered.add(endpoint);
      if (!endpoint.equals("GET /api/runs/{id}/support-bundle")
          && JsonSchemas.forEndpoint(endpoint).isEmpty()) {
        missing.add(endpoint);
      }
    }
    assertThat(registered).as("routes found in ConsoleApi.java").hasSizeGreaterThan(20);
    assertThat(missing).as("routes without a published schema").isEmpty();
  }

  static String schemaText(String iri) {
    Optional<InputStream> in = JsonSchemas.open(iri);
    if (in.isEmpty()) {
      return null;
    }
    try (InputStream s = in.get()) {
      return new String(s.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
