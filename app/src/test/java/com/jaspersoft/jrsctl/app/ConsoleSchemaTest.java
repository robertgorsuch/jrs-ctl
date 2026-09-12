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
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Every console document the golden files hold validates against the schema published for its
 * endpoint. Invariants: each endpoint in {@link JsonSchemas#endpoints()} has at least one golden; a
 * golden that no schema accepts fails the build, so the records, the schemas and the wire cannot
 * drift apart. The two structural goldens ({@code doctor}, {@code health-doctor-cached}) are
 * scrubbed to type tokens rather than values and so cannot be schema-validated; those endpoints are
 * proven live in Task 8 instead.
 */
class ConsoleSchemaTest {

  /** Resolves every {@code https://jaspersoft.com/jrsctl/...} reference from the classpath. */
  private static final JsonSchemaFactory FACTORY =
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
        Arguments.of("run-started", "api-run-started.schema.json"),
        Arguments.of("runs-after-apply", "api-runs.schema.json"),
        Arguments.of("runs-show-succeeded", "api-runs-show.schema.json"),
        Arguments.of("runs-show-failed", "api-runs-show.schema.json"),
        Arguments.of("runs-show-running", "api-runs-show.schema.json"));
  }

  @ParameterizedTest(name = "{0} validates against {1}")
  @MethodSource("goldens")
  void should_validate_the_golden_against_its_schema(String golden, String schemaName)
      throws Exception {
    JsonNode doc =
        Json.mapper()
            .readTree(
                Files.readString(Path.of("src/test/resources/console-wire/" + golden + ".json")));
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

  private static String schemaText(String iri) {
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
