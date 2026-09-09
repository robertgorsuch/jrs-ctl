package com.jaspersoft.jrsctl.acceptance;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
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
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.io.TempDir;

/**
 * Spec §14 Phase 8, "{@code --json} for every command validated against schema", exercised through
 * the packaged jar: each command's standard output is parsed as JSON (one document, or JSONL for a
 * plan-running command) and validated against the schema read out of {@code jrsctl.jar} itself
 * ({@code schema/json/<name>.schema.json}, {@code schema/config.schema.json}); standard error stays
 * empty. The hotfix flow (generate a key, build, apply, refuse an unsigned copy) shares one home,
 * so the class runs in order.
 */
@Tag("phase8")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Phase8JsonSchemaTest {

  private static final String IRI_PREFIX = "https://jaspersoft.com/jrsctl/";
  private static final String WEBAPP = "/jasperserver-pro";
  private static final String HOTFIX_ID = "JRS-8.2.0-HF-0801";
  private static final String REPLACED = "webapps/jasperserver-pro/scripts/jrsctl-json.js";
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

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static WireMockServer server;
  private static Cli cli;
  private static ZipFile jar;
  private static JsonSchemaFactory factory;

  @TempDir static Path tmp;

  private static Path home;
  private static Path install;
  private static Path bundle;
  private static Path privateKey;

  @BeforeAll
  static void setUp() throws Exception {
    jar = new ZipFile(Path.of(System.getProperty("jrsctl.jar")).toFile());
    factory =
        JsonSchemaFactory.getInstance(
            SpecVersion.VersionFlag.V202012,
            b -> b.schemaLoaders(l -> l.schemas(Phase8JsonSchemaTest::schemaFromJar)));

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
            .willReturn(aResponse().withStatus(404)));
    server.stubFor(
        get(urlPathEqualTo(WEBAPP + "/rest_v2/import/jrsctl-probe/state"))
            .willReturn(aResponse().withStatus(404)));
    server.stubFor(
        post(urlPathEqualTo(WEBAPP + "/rest_v2/login"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Set-Cookie", "JSESSIONID=ABCDEF0123456789; Path=/; HttpOnly")));

    cli = new Cli(Map.of("JRS_PASSWORD", "jasperadmin"));
    home = Files.createDirectories(tmp.resolve("home"));
    install = fakeLayout(tmp.resolve("jrs"));
    bundle = tmp.resolve(HOTFIX_ID + ".zip");
    privateKey = tmp.resolve("keys").resolve("customer.key");
    Files.writeString(
        home.resolve("config.yaml"),
        """
        server:
          baseUrl: http://localhost:%d%s
          webappName: jasperserver-pro
          installDir: %s
          tomcatDir: %s
          auth:
            mode: basic
            username: jasperadmin
            passwordRef: env:JRS_PASSWORD
        service:
          kind: manual
        network:
          mode: public
        """
            .formatted(
                server.port(), WEBAPP, unix(install), unix(install.resolve("apache-tomcat-9"))),
        StandardCharsets.UTF_8);
  }

  @AfterAll
  static void tearDown() throws IOException {
    if (server != null) {
      server.stop();
    }
    if (jar != null) {
      jar.close();
    }
  }

  // ---- schema plumbing (the jar is the only source) --------------------------------------------

  /** {@code https://jaspersoft.com/jrsctl/X} resolves to {@code schema/json/X} in the jar. */
  private static String schemaFromJar(String iri) {
    if (!iri.startsWith(IRI_PREFIX)) {
      return null;
    }
    String name = iri.substring(IRI_PREFIX.length());
    String entryName = name.equals("config.schema.json") ? "schema/" + name : "schema/json/" + name;
    ZipEntry entry = jar.getEntry(entryName);
    if (entry == null) {
      return null;
    }
    try (InputStream in = jar.getInputStream(entry)) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static void validate(String schemaName, JsonNode doc) {
    JsonSchema schema = factory.getSchema(SchemaLocation.of(IRI_PREFIX + schemaName));
    Set<ValidationMessage> errors = schema.validate(doc);
    assertThat(errors).as("%s against %s", doc.toPrettyString(), schemaName).isEmpty();
  }

  /** Every top-level JSON value on standard output, in order. */
  private static List<JsonNode> documents(String text) throws IOException {
    List<JsonNode> docs = new ArrayList<>();
    try (JsonParser parser = MAPPER.createParser(text)) {
      while (parser.nextToken() != null) {
        docs.add(MAPPER.readTree(parser));
      }
    }
    return docs;
  }

  /** One document on stdout, nothing on stderr, validating against {@code schema}. */
  private static JsonNode document(Cli.Result result, String schema) throws IOException {
    assertThat(result.stderr()).as("--json keeps stderr silent").isBlank();
    List<JsonNode> docs = documents(result.stdout());
    assertThat(docs).as("exactly one document\n%s", result.stdout()).hasSize(1);
    validate(schema, docs.get(0));
    return docs.get(0);
  }

  /** One {@code {"error": ...}} document whose exit code matches the process's. */
  private static JsonNode error(Cli.Result result, int exit) throws IOException {
    result.assertExit(exit);
    JsonNode doc = document(result, "error.schema.json");
    assertThat(doc.get("error").get("exitCode").asInt()).isEqualTo(exit);
    return doc;
  }

  /** Plan line, event lines, then {@code outcome} or {@code error}; returns the documents. */
  private static List<JsonNode> stream(Cli.Result result) throws IOException {
    assertThat(result.stderr()).as("--json keeps stderr silent").isBlank();
    List<JsonNode> docs = documents(result.stdout());
    assertThat(docs).isNotEmpty();
    for (int i = 0; i < docs.size(); i++) {
      JsonNode doc = docs.get(i);
      boolean last = i == docs.size() - 1;
      if (doc.has("error")) {
        assertThat(last).isTrue();
        validate("error.schema.json", doc);
        assertThat(doc.get("error").get("exitCode").asInt()).isEqualTo(result.exitCode());
      } else if (doc.has("outcome")) {
        assertThat(last).isTrue();
        validate("outcome.schema.json", doc);
        assertThat(doc.get("outcome").get("exitCode").asInt()).isEqualTo(result.exitCode());
      } else if (doc.has("type")) {
        validate("events.schema.json", doc);
      } else {
        assertThat(i).as("the plan is the first line").isZero();
        validate("plan.schema.json", doc);
      }
    }
    return docs;
  }

  // ---- fixtures (as Phase 3) --------------------------------------------------------------------

  private static String unix(Path p) {
    return p.toString().replace("\\", "/");
  }

  private static Path fakeLayout(Path install) throws IOException {
    Path tomcat = install.resolve("apache-tomcat-9");
    Path webapp = tomcat.resolve("webapps").resolve("jasperserver-pro");
    Files.createDirectories(webapp.resolve("WEB-INF").resolve("lib"));
    Files.createDirectories(webapp.resolve("WEB-INF").resolve("classes"));
    Files.createDirectories(webapp.resolve("scripts"));
    Files.write(webapp.resolve("WEB-INF").resolve("lib").resolve("x.jar"), new byte[128]);
    Files.writeString(
        webapp.resolve("scripts").resolve("jrsctl-json.js"),
        "// original\n",
        StandardCharsets.UTF_8);
    Files.createDirectories(tomcat.resolve("conf"));
    Files.writeString(
        tomcat.resolve("conf").resolve("server.xml"),
        "<Server><Service><Connector port=\"8080\" protocol=\"HTTP/1.1\"/></Service></Server>",
        StandardCharsets.UTF_8);
    Path buildomatic = Files.createDirectories(install.resolve("buildomatic"));
    for (String ext : new String[] {".sh", ".bat"}) {
      Files.writeString(install.resolve("ctlscript" + ext), "", StandardCharsets.UTF_8);
      for (String script : new String[] {"js-export", "js-import", "js-ant"}) {
        Files.writeString(buildomatic.resolve(script + ext), "", StandardCharsets.UTF_8);
      }
    }
    Files.writeString(
        buildomatic.resolve("default_master.properties"),
        "dbType=postgresql\ndbHost=localhost\ndbPort=5432\ndbUsername=jasperdb\n"
            + "js.dbName=jasperserver\n",
        StandardCharsets.UTF_8);
    return install;
  }

  private static Path fixtureDir(Path dir) throws Exception {
    Path replaced = dir.resolve("payload").resolve(REPLACED);
    Files.createDirectories(replaced.getParent());
    Files.writeString(replaced, "// fixed by " + HOTFIX_ID + "\n", StandardCharsets.UTF_8);
    Files.writeString(
        dir.resolve("manifest.json"),
        """
        {
          "id": "%s",
          "version": "1",
          "title": "Phase 8 json acceptance fix",
          "applies": { "versions": [">=8.2.0 <8.3.0"], "editions": ["PRO"] },
          "requires": [],
          "conflicts": [],
          "files": [ { "action": "replace", "path": "%s", "sha256": "%s" } ],
          "restart": "none",
          "prechecks": [ { "type": "fileExists", "path": "%s" } ],
          "postchecks": [],
          "rollback": "snapshot"
        }
        """
            .formatted(HOTFIX_ID, REPLACED, sha256(replaced), REPLACED),
        StandardCharsets.UTF_8);
    return dir;
  }

  private static String sha256(Path file) throws Exception {
    MessageDigest md = MessageDigest.getInstance("SHA-256");
    byte[] buf = new byte[8192];
    try (InputStream in = Files.newInputStream(file)) {
      int n;
      while ((n = in.read(buf)) != -1) {
        md.update(buf, 0, n);
      }
    }
    return HexFormat.of().formatHex(md.digest());
  }

  private static Path rewriteZip(Path source, Path target, Predicate<String> keep)
      throws IOException {
    try (ZipInputStream in = new ZipInputStream(Files.newInputStream(source));
        ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(target))) {
      ZipEntry entry;
      while ((entry = in.getNextEntry()) != null) {
        byte[] bytes = in.readAllBytes();
        if (keep.test(entry.getName())) {
          out.putNextEntry(new ZipEntry(entry.getName()));
          out.write(bytes);
          out.closeEntry();
        }
      }
    }
    return target;
  }

  private static Cli.Result jrsctl(String... args) throws Exception {
    List<String> all = new ArrayList<>(List.of(args));
    all.addAll(List.of("--json", "--home", home.toString(), "--no-color"));
    return cli.run(all.toArray(String[]::new));
  }

  // ---- criteria -------------------------------------------------------------------------------

  @Test
  @Order(1)
  void read_only_commands_emit_one_schema_valid_document() throws Exception {
    document(cli.run("selfcheck", "--json").assertExit(0), "selfcheck.schema.json");
    JsonNode runs = document(jrsctl("runs", "list").assertExit(0), "runs-list.schema.json");
    assertThat(runs.isArray()).isTrue();
    document(jrsctl("keys", "list").assertExit(0), "keys-list.schema.json");
    JsonNode config = document(jrsctl("config", "show").assertExit(0), "config.schema.json");
    assertThat(config.get("server").get("auth").get("passwordRef").asText())
        .isEqualTo("env:JRS_PASSWORD");
    document(jrsctl("hotfix", "list").assertExit(0), "hotfix-list.schema.json");
  }

  @Test
  @Order(2)
  void init_json_reports_the_detected_layout_without_writing() throws Exception {
    Path freshHome = tmp.resolve("init-home");
    Cli.Result result =
        new Cli()
            .run(
                "init",
                "--json",
                "--home",
                freshHome.toString(),
                "--install-dir",
                install.toString())
            .assertExit(0);
    JsonNode doc = document(result, "init.schema.json");
    assertThat(doc.get("detectedInstall").asBoolean()).isTrue();
    assertThat(doc.get("written").asBoolean()).isFalse();
    assertThat(freshHome.resolve("config.yaml")).doesNotExist();
  }

  @Test
  @Order(3)
  void doctor_json_validates_against_wiremock_and_when_the_server_is_unreachable()
      throws Exception {
    Cli.Result ok = jrsctl("doctor");
    assertThat(ok.exitCode()).isIn(0, 2, 6);
    JsonNode report = document(ok, "doctor.schema.json");
    assertThat(report.get("exitCode").asInt()).isEqualTo(ok.exitCode());

    Path unreachableHome = Files.createDirectories(tmp.resolve("unreachable-home"));
    Files.writeString(
        unreachableHome.resolve("config.yaml"),
        """
        server:
          baseUrl: http://localhost:1%s
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
            .formatted(WEBAPP, unix(install)),
        StandardCharsets.UTF_8);
    Cli.Result down =
        cli.run("doctor", "--json", "--home", unreachableHome.toString()).assertExit(2);
    JsonNode downReport = document(down, "doctor.schema.json");
    assertThat(downReport.get("exitCode").asInt()).isEqualTo(2);
  }

  @Test
  @Order(4)
  void hotfix_apply_json_is_a_plan_then_events_then_an_outcome() throws Exception {
    JsonNode generated =
        document(
            jrsctl("keys", "generate", "customer", "--private-out", privateKey.toString())
                .assertExit(0),
            "keys-generate.schema.json");
    assertThat(generated.get("keyRef").asText()).startsWith("file:");

    Path fixture = fixtureDir(tmp.resolve("fixture"));
    JsonNode built =
        document(
            jrsctl(
                    "hotfix",
                    "build",
                    fixture.toString(),
                    "--key",
                    "file:" + privateKey,
                    "--out",
                    bundle.toString())
                .assertExit(0),
            "hotfix-build.schema.json");
    assertThat(Path.of(built.get("bundle").asText())).exists();

    document(
        jrsctl("hotfix", "verify", bundle.toString()).assertExit(0), "hotfix-verify.schema.json");

    List<JsonNode> planOnly =
        stream(jrsctl("hotfix", "apply", bundle.toString(), "--plan").assertExit(0));
    assertThat(planOnly).hasSize(1);

    List<JsonNode> docs =
        stream(jrsctl("hotfix", "apply", bundle.toString(), "--yes").assertExit(0));
    assertThat(docs.size()).isGreaterThan(3);
    assertThat(docs.get(0).get("operation").asText()).isEqualTo("hotfix.apply");
    List<String> types = new ArrayList<>();
    for (JsonNode d : docs.subList(1, docs.size() - 1)) {
      types.add(d.get("type").asText());
    }
    assertThat(types).contains("PlanCreated", "StepRunning", "StepSucceeded", "RunSucceeded");
    assertThat(docs.get(docs.size() - 1).get("outcome").get("type").asText())
        .isEqualTo("Succeeded");

    JsonNode listed = document(jrsctl("hotfix", "list").assertExit(0), "hotfix-list.schema.json");
    assertThat(listed.get(0).get("id").asText()).isEqualTo(HOTFIX_ID);
    JsonNode runs = document(jrsctl("runs", "list").assertExit(0), "runs-list.schema.json");
    String runId = runs.get(0).get("runId").asText();
    JsonNode shown = document(jrsctl("runs", "show", runId).assertExit(0), "runs-show.schema.json");
    assertThat(shown.get("plan").get("planId").asText())
        .isEqualTo(docs.get(0).get("planId").asText());
  }

  @Test
  @Order(5)
  void refusals_and_failures_are_one_error_document_on_stdout() throws Exception {
    Path unsigned =
        rewriteZip(bundle, tmp.resolve("unsigned.zip"), name -> !name.equals("SIGNATURE"));
    JsonNode refused = error(jrsctl("hotfix", "apply", unsigned.toString(), "--yes"), 7);
    assertThat(refused.get("error").get("class").asText()).isEqualTo("SignatureFailed");
    assertThat(refused.get("error").get("remediation").asText()).contains("--allow-unsigned");

    JsonNode unknown = error(jrsctl("runs", "show", "r-nope"), 2);
    assertThat(unknown.get("error").get("message").asText()).contains("r-nope");

    JsonNode usage = error(jrsctl("hotfix", "apply"), 1);
    assertThat(usage.get("error").get("class").asText()).contains("Exception");

    // without --yes a non-interactive caller gets the plan, then the refusal (exit 2); when the
    // bundle is already installed planning itself refuses, which is the single error document
    Cli.Result noYes = jrsctl("hotfix", "apply", bundle.toString()).assertExit(2);
    List<JsonNode> docs = stream(noYes);
    assertThat(docs.get(docs.size() - 1).has("error")).isTrue();
  }
}
