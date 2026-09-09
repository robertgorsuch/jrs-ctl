package com.jaspersoft.jrsctl.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jaspersoft.jrsctl.core.JrsctlHome;
import com.jaspersoft.jrsctl.core.crypto.Ed25519;
import com.jaspersoft.jrsctl.core.engine.Plan;
import com.jaspersoft.jrsctl.core.secrets.EncryptedSecretStore;
import com.jaspersoft.jrsctl.core.secrets.PassphraseSource;
import com.jaspersoft.jrsctl.core.secrets.Secret;
import com.jaspersoft.jrsctl.core.state.HotfixFile;
import com.jaspersoft.jrsctl.core.state.HotfixInstalled;
import com.jaspersoft.jrsctl.core.state.HotfixState;
import com.jaspersoft.jrsctl.core.state.StateStore;
import com.jaspersoft.jrsctl.core.state.StoredPlan;
import com.jaspersoft.jrsctl.core.state.TerminalState;
import com.jaspersoft.jrsctl.ops.hotfix.HotfixOperations;
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
import java.security.KeyPair;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/**
 * Spec §14 Phase 8: every leaf command honours {@code --json}, its output validates against the
 * schema {@link JsonSchemas} maps it to, standard error stays silent, and the picocli tree, the
 * schema registry and the scenario table below agree on the set of commands, so a new command
 * without a schema and a scenario fails here.
 */
class JsonOutputSchemaTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final Instant T0 = Instant.parse("2026-09-01T10:00:00Z");
  private static final String PASSPHRASE = "pp-for-tests";
  private static final Set<Integer> ANY_REPORT_EXIT = Set.of(0, 2, 6);

  /** Resolves every {@code https://jaspersoft.com/jrsctl/...} reference from the classpath. */
  private static final JsonSchemaFactory FACTORY =
      JsonSchemaFactory.getInstance(
          SpecVersion.VersionFlag.V202012,
          b -> b.schemaLoaders(l -> l.schemas(JsonOutputSchemaTest::schemaText)));

  @TempDir Path tmp;

  @AfterEach
  void resetFakes() {
    HotfixOps.factory = HotfixOps.DEFAULT_FACTORY;
    EximOps.factory = EximOps.DEFAULT_FACTORY;
    TestAdapterFactory.unreachable = false;
    TestAdapterFactory.adapter = new AppFakeAdapter();
    Env.reset();
  }

  // ---- the command tree -------------------------------------------------------------------------

  /** Leaf command paths ({@code "hotfix apply"}): commands that do work rather than print usage. */
  static Set<String> leafCommands() {
    Set<String> leaves = new TreeSet<>();
    collect(Main.commandLine(), "", leaves);
    return leaves;
  }

  private static void collect(CommandLine cmd, String prefix, Set<String> leaves) {
    boolean isLeaf = cmd.getCommandSpec().userObject() instanceof java.util.concurrent.Callable;
    if (isLeaf && !prefix.isEmpty()) {
      leaves.add(prefix);
    }
    for (Map.Entry<String, CommandLine> e : cmd.getSubcommands().entrySet()) {
      if (e.getKey().equals("help")) {
        continue;
      }
      collect(e.getValue(), prefix.isEmpty() ? e.getKey() : prefix + " " + e.getKey(), leaves);
    }
  }

  @Test
  void should_map_every_leaf_command_to_a_schema_and_to_at_least_one_scenario() {
    Set<String> leaves = leafCommands();
    assertThat(leaves).isNotEmpty();
    // every leaf must have a schema; the registry may run ahead of the tree for commands that
    // are being merged from another branch (their scenarios below are guarded the same way)
    assertThat(JsonSchemas.commands())
        .as("every leaf command of the picocli tree needs a schema in JsonSchemas")
        .containsAll(leaves);
    Set<String> exercised = new LinkedHashSet<>();
    for (Scenario s : scenarios()) {
      exercised.add(s.command());
    }
    assertThat(exercised)
        .as("every leaf command needs a --json scenario in this test")
        .containsAll(leaves);
  }

  @Test
  void should_bundle_every_schema_the_registry_names_and_each_one_must_load() {
    for (String name : JsonSchemas.schemas()) {
      assertThat(JsonSchemas.open(name)).as(name).isPresent();
      JsonSchema schema = FACTORY.getSchema(SchemaLocation.of(JsonSchemas.iri(name)));
      assertThat(schema).isNotNull();
    }
    assertThat(JsonSchemas.open(JsonSchemas.CONFIG)).isPresent();
    assertThat(JsonSchemas.resourcePath("https://example.com/other.json")).isEmpty();
  }

  // ---- scenarios --------------------------------------------------------------------------------

  @FunctionalInterface
  interface Body {
    InitCommandTest.Run run(Path dir) throws Exception;
  }

  /**
   * One {@code --json} invocation of {@code command} expected to exit with one of {@code exits}.
   */
  record Scenario(String name, String command, Set<Integer> exits, Body body) {}

  private static Scenario of(String name, String command, int exit, Body body) {
    return new Scenario(name, command, Set.of(exit), body);
  }

  @TestFactory
  Stream<DynamicTest> should_emit_schema_valid_json_for_every_command_path() {
    return scenarios().stream()
        .map(s -> DynamicTest.dynamicTest(s.command() + ": " + s.name(), () -> check(s)));
  }

  private void check(Scenario s) throws Exception {
    Path dir = Files.createDirectories(tmp.resolve(s.name().replaceAll("[^A-Za-z0-9]+", "_")));
    InitCommandTest.Run run;
    try {
      run = s.body().run(dir);
    } finally {
      resetFakes();
    }
    assertThat(run.code())
        .as("exit code\nstdout:\n%s\nstderr:\n%s", run.out(), run.err())
        .isIn(s.exits());
    assertThat(run.err()).as("--json keeps stderr silent").isBlank();
    List<JsonNode> docs = documents(run.out());
    assertThat(docs).as("at least one JSON document\nstdout:\n%s", run.out()).isNotEmpty();
    JsonSchemas.Shape shape = JsonSchemas.forCommand(s.command()).orElseThrow();
    switch (shape) {
      case JsonSchemas.Document d -> {
        assertThat(docs).as("exactly one JSON document\nstdout:\n%s", run.out()).hasSize(1);
        JsonNode doc = docs.get(0);
        if (doc.has("error")) {
          validate(JsonSchemas.ERROR, doc);
          assertThat(doc.get("error").get("exitCode").asInt()).isEqualTo(run.code());
        } else {
          validate(d.schema(), doc);
        }
      }
      case JsonSchemas.Stream st -> validateStream(st, docs, run.code());
    }
    assertThat(run.out()).doesNotContain(PASSPHRASE).doesNotContain("pw-for-tests");
  }

  /** Plan first (when the command shows one), then events, then exactly one outcome or error. */
  private static void validateStream(JsonSchemas.Stream shape, List<JsonNode> docs, int exit) {
    for (int i = 0; i < docs.size(); i++) {
      JsonNode doc = docs.get(i);
      boolean last = i == docs.size() - 1;
      if (doc.has("error")) {
        assertThat(last).as("an error document ends the stream").isTrue();
        validate(JsonSchemas.ERROR, doc);
        assertThat(doc.get("error").get("exitCode").asInt()).isEqualTo(exit);
      } else if (doc.has("outcome")) {
        assertThat(last).as("the outcome ends the stream").isTrue();
        validate(JsonSchemas.OUTCOME, doc);
        assertThat(doc.get("outcome").get("exitCode").asInt()).isEqualTo(exit);
      } else if (doc.has("type")) {
        validate(JsonSchemas.EVENTS, doc);
      } else {
        assertThat(i).as("the plan is the first line").isZero();
        assertThat(shape.planFirst()).as("this command does not print a plan").isTrue();
        validate(JsonSchemas.PLAN, doc);
      }
    }
  }

  /** Every top-level JSON value in {@code text}, in order (a root array is one document). */
  static List<JsonNode> documents(String text) throws IOException {
    List<JsonNode> docs = new ArrayList<>();
    try (JsonParser parser = MAPPER.createParser(text)) {
      while (parser.nextToken() != null) {
        docs.add(MAPPER.readTree(parser));
      }
    }
    return docs;
  }

  static void validate(String schemaName, JsonNode doc) {
    JsonSchema schema = FACTORY.getSchema(SchemaLocation.of(JsonSchemas.iri(schemaName)));
    Set<ValidationMessage> errors = schema.validate(doc);
    assertThat(errors).as("%s against %s", doc.toPrettyString(), schemaName).isEmpty();
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

  // ---- fixtures ---------------------------------------------------------------------------------

  private static InitCommandTest.Run run(String... args) {
    return InitCommandTest.run(args);
  }

  /** {@code command...} followed by the {@link #serverArgs} options (options come last). */
  private static String[] join(List<String> options, String... command) {
    List<String> all = new ArrayList<>(List.of(command));
    all.addAll(options);
    return all.toArray(String[]::new);
  }

  /** Empty home: defaults only, no server. */
  private static Path emptyHome(Path dir) throws IOException {
    return Files.createDirectories(dir.resolve("home"));
  }

  /** Home with a fake install, a server pointing at the fake adapter and an enc: password. */
  private static Path serverHome(Path dir) throws Exception {
    Path install = InitCommandTest.fakeLayout(dir.resolve("jrs"));
    Path home = Files.createDirectories(dir.resolve("home"));
    JrsctlHome jh = new JrsctlHome(home);
    EncryptedSecretStore store =
        new EncryptedSecretStore(
            jh.secretsFile(), new PassphraseSource.Fixed(Secret.fromString(PASSPHRASE)));
    store.init();
    store.set("jrs", Secret.fromString("pw-for-tests"));
    Files.writeString(dir.resolve("pp.txt"), PASSPHRASE, StandardCharsets.UTF_8);
    Files.writeString(
        jh.configFile(),
        """
        server:
          baseUrl: http://localhost:8080/jasperserver-pro
          webappName: jasperserver-pro
          installDir: %s
          tomcatDir: %s
          auth:
            username: jasperadmin
            passwordRef: enc:jrs
        service:
          kind: manual
        network:
          mode: public
        """
            .formatted(unix(install), unix(install.resolve("apache-tomcat"))),
        StandardCharsets.UTF_8);
    return home;
  }

  private static String unix(Path p) {
    return p.toString().replace("\\", "/");
  }

  private static List<String> serverArgs(Path dir) {
    return List.of(
        "--json",
        "--home",
        dir.resolve("home").toString(),
        "--passphrase-file",
        dir.resolve("pp.txt").toString());
  }

  private static FakeHotfixOperations fakeHotfix() {
    FakeHotfixOperations fake = new FakeHotfixOperations();
    HotfixOps.factory = services -> fake;
    return fake;
  }

  private static FakeExportImportOperations fakeExim() {
    FakeExportImportOperations fake = new FakeExportImportOperations();
    EximOps.factory = services -> fake;
    return fake;
  }

  private static Path bundle(Path dir) throws IOException {
    Path bundle = dir.resolve("hf.zip");
    Files.writeString(bundle, "zip");
    return bundle;
  }

  private static StateStore open(Path home) {
    return StateStore.open(new JrsctlHome(home), Clock.systemUTC());
  }

  private static void seedFinishedRun(Path home, FakeHotfixOperations fake, Path bundle) {
    try (StateStore store = open(home)) {
      Plan plan = fake.planApply(bundle, new HotfixOperations.ApplyOptions(false));
      store.savePlan(
          new StoredPlan(
              "plan-1",
              PlanRegistry.HOTFIX_APPLY,
              PlanRegistry.applyArgs(bundle, false),
              PlanPrinter.toJson(plan),
              plan.fingerprint().value(),
              T0,
              T0.plus(Duration.ofMinutes(30)),
              Optional.of("r-1")));
      store.recordRunStart("r-1", "hotfix.apply", Optional.of("plan-1"), T0);
      store.appendTransition(
          "r-1", "verify-signature", "verify", Optional.empty(), "PENDING", Optional.empty());
      store.appendTransition(
          "r-1", "verify-signature", "verify", Optional.of("PENDING"), "RUNNING", Optional.empty());
      store.appendTransition(
          "r-1",
          "verify-signature",
          "verify",
          Optional.of("RUNNING"),
          "SUCCEEDED",
          Optional.of("key customer"));
      store.recordRunEnd("r-1", T0.plusSeconds(42), TerminalState.SUCCEEDED, 0);
    }
  }

  private static void seedPendingRun(Path home, FakeHotfixOperations fake, Path bundle) {
    try (StateStore store = open(home)) {
      Plan plan = fake.planApply(bundle, new HotfixOperations.ApplyOptions(false));
      store.savePlan(
          new StoredPlan(
              "plan-2",
              PlanRegistry.HOTFIX_APPLY,
              PlanRegistry.applyArgs(bundle, false),
              PlanPrinter.toJson(plan),
              plan.fingerprint().value(),
              T0,
              T0.plus(Duration.ofMinutes(30)),
              Optional.of("r-2")));
      store.recordRunStart("r-2", "hotfix.apply", Optional.of("plan-2"), T0);
      store.appendTransition(
          "r-2", "verify-signature", "verify", Optional.empty(), "PENDING", Optional.empty());
      store.appendTransition(
          "r-2", "verify-signature", "verify", Optional.of("PENDING"), "RUNNING", Optional.empty());
      store.appendTransition(
          "r-2",
          "verify-signature",
          "verify",
          Optional.of("RUNNING"),
          "SUCCEEDED",
          Optional.empty());
      store.appendTransition(
          "r-2", "validate-manifest", "verify", Optional.empty(), "PENDING", Optional.empty());
      store.appendTransition(
          "r-2",
          "validate-manifest",
          "verify",
          Optional.of("PENDING"),
          "RUNNING",
          Optional.empty());
    }
  }

  private static void seedInstalledHotfix(Path home) {
    HotfixInstalled installed =
        new HotfixInstalled(
            FakeHotfixOperations.ID,
            "1",
            FakeHotfixOperations.TITLE,
            "r-1",
            Optional.of("snap-1"),
            HotfixState.INSTALLED,
            T0);
    try (StateStore store = open(home)) {
      store.recordHotfixInstalled(
          installed,
          List.of(
              new HotfixFile(
                  installed.id(), Path.of("a.js"), "replace", Optional.of("x"), Optional.of("y"))));
    }
  }

  /** A customised file under the fake install's webapp, registered or not. */
  private static Path customisedFile(Path dir) throws IOException {
    Path file =
        dir.resolve("jrs")
            .resolve("apache-tomcat")
            .resolve("webapps")
            .resolve("jasperserver-pro")
            .resolve("WEB-INF")
            .resolve("classes")
            .resolve("custom.properties");
    Files.writeString(file, "a=1\n", StandardCharsets.UTF_8);
    return file;
  }

  private static void withSecretsEnv() {
    Env.override(Map.of("JRSCTL_PASSPHRASE", PASSPHRASE, "MY_SECRET", "s3cret-value"));
  }

  // ---- the scenario table -----------------------------------------------------------------------

  static List<Scenario> scenarios() {
    List<Scenario> s = new ArrayList<>();

    s.add(of("selfcheck", "selfcheck", 0, dir -> run("selfcheck", "--json")));

    s.add(
        of(
            "init detects without writing",
            "init",
            0,
            dir -> {
              Path install = InitCommandTest.fakeLayout(dir.resolve("jrs"));
              InitCommandTest.Run r =
                  run(
                      "init",
                      "--json",
                      "--home",
                      dir.resolve("home").toString(),
                      "--install-dir",
                      install.toString());
              assertThat(dir.resolve("home").resolve("config.yaml")).doesNotExist();
              return r;
            }));
    s.add(
        of(
            "init --yes writes",
            "init",
            0,
            dir -> {
              Path install = InitCommandTest.fakeLayout(dir.resolve("jrs"));
              InitCommandTest.Run r =
                  run(
                      "init",
                      "--json",
                      "--yes",
                      "--home",
                      dir.resolve("home").toString(),
                      "--install-dir",
                      install.toString());
              assertThat(dir.resolve("home").resolve("config.yaml")).exists();
              assertThat(r.out()).contains("\"written\" : true");
              return r;
            }));
    s.add(
        of(
            "init refuses to overwrite",
            "init",
            2,
            dir -> {
              Path install = InitCommandTest.fakeLayout(dir.resolve("jrs"));
              String[] args = {
                "init",
                "--json",
                "--yes",
                "--home",
                dir.resolve("home").toString(),
                "--install-dir",
                install.toString()
              };
              run(args);
              return run(args);
            }));

    s.add(
        new Scenario(
            "doctor against the fake server",
            "doctor",
            ANY_REPORT_EXIT,
            dir -> {
              serverHome(dir);
              return run(join(serverArgs(dir), "doctor"));
            }));
    s.add(
        of(
            "doctor with unreachable server",
            "doctor",
            2,
            dir -> {
              serverHome(dir);
              TestAdapterFactory.unreachable = true;
              return run(join(serverArgs(dir), "doctor"));
            }));
    s.add(
        of(
            "doctor without configuration",
            "doctor",
            2,
            dir -> run("doctor", "--json", "--home", emptyHome(dir).toString())));

    s.add(
        of(
            "smoke against the fake server",
            "smoke",
            0,
            dir -> {
              serverHome(dir);
              return run(join(serverArgs(dir), "smoke"));
            }));

    s.add(
        of(
            "config show",
            "config show",
            0,
            dir -> {
              serverHome(dir);
              return run(join(serverArgs(dir), "config", "show"));
            }));
    s.add(
        of(
            "config show of defaults",
            "config show",
            0,
            dir -> run("config", "show", "--json", "--home", emptyHome(dir).toString())));

    s.add(
        of(
            "hotfix build writes the bundle",
            "hotfix build",
            0,
            dir -> {
              fakeHotfix();
              return run(
                  "hotfix",
                  "build",
                  dir.toString(),
                  "--key",
                  "file:" + dir.resolve("k.key"),
                  "--out",
                  dir.resolve("built.zip").toString(),
                  "--json",
                  "--home",
                  emptyHome(dir).toString());
            }));
    s.add(
        of(
            "hotfix build with a bad key reference",
            "hotfix build",
            1,
            dir -> {
              fakeHotfix();
              return run(
                  "hotfix",
                  "build",
                  dir.toString(),
                  "--key",
                  "nope",
                  "--out",
                  dir.resolve("built.zip").toString(),
                  "--json",
                  "--home",
                  emptyHome(dir).toString());
            }));
    s.add(
        of(
            "hotfix verify ok",
            "hotfix verify",
            0,
            dir -> {
              fakeHotfix();
              return run(
                  "hotfix",
                  "verify",
                  bundle(dir).toString(),
                  "--json",
                  "--home",
                  emptyHome(dir).toString());
            }));
    s.add(
        of(
            "hotfix verify rejects bad hashes",
            "hotfix verify",
            7,
            dir -> {
              fakeHotfix().hashesValid = false;
              return run(
                  "hotfix",
                  "verify",
                  bundle(dir).toString(),
                  "--json",
                  "--home",
                  emptyHome(dir).toString());
            }));

    s.add(
        of(
            "hotfix apply --plan",
            "hotfix apply",
            0,
            dir -> {
              fakeHotfix();
              return run(
                  "hotfix",
                  "apply",
                  bundle(dir).toString(),
                  "--plan",
                  "--json",
                  "--home",
                  emptyHome(dir).toString());
            }));
    s.add(
        of(
            "hotfix apply --yes succeeds",
            "hotfix apply",
            0,
            dir -> {
              fakeHotfix();
              return run(
                  "hotfix",
                  "apply",
                  bundle(dir).toString(),
                  "--yes",
                  "--json",
                  "--home",
                  emptyHome(dir).toString());
            }));
    s.add(
        of(
            "hotfix apply --yes fails and rolls back",
            "hotfix apply",
            3,
            dir -> {
              fakeHotfix().failStep = Optional.of("atomic-swap");
              return run(
                  "hotfix",
                  "apply",
                  bundle(dir).toString(),
                  "--yes",
                  "--json",
                  "--home",
                  emptyHome(dir).toString());
            }));
    s.add(
        of(
            "hotfix apply without --yes needs confirmation",
            "hotfix apply",
            2,
            dir -> {
              fakeHotfix();
              return run(
                  "hotfix",
                  "apply",
                  bundle(dir).toString(),
                  "--json",
                  "--home",
                  emptyHome(dir).toString());
            }));
    s.add(
        of(
            "hotfix apply refuses an unsigned bundle",
            "hotfix apply",
            7,
            dir -> {
              fakeHotfix().signatureValid = false;
              return run(
                  "hotfix",
                  "apply",
                  bundle(dir).toString(),
                  "--yes",
                  "--json",
                  "--home",
                  emptyHome(dir).toString());
            }));
    s.add(
        of(
            "hotfix apply blocked by a pending run",
            "hotfix apply",
            8,
            dir -> {
              fakeHotfix();
              Path home = emptyHome(dir);
              try (StateStore store = open(home)) {
                store.recordRunStart("r-pending", "hotfix.apply", Optional.empty(), T0);
              }
              return run(
                  "hotfix",
                  "apply",
                  bundle(dir).toString(),
                  "--yes",
                  "--json",
                  "--home",
                  home.toString());
            }));
    s.add(
        of(
            "hotfix apply planning failure",
            "hotfix apply",
            2,
            dir -> {
              fakeHotfix().planFailure = Optional.of(new IllegalStateException("manifest missing"));
              return run(
                  "hotfix",
                  "apply",
                  bundle(dir).toString(),
                  "--yes",
                  "--json",
                  "--home",
                  emptyHome(dir).toString());
            }));
    s.add(
        of(
            "hotfix apply usage error from picocli",
            "hotfix apply",
            1,
            dir -> run("hotfix", "apply", "--json", "--home", emptyHome(dir).toString())));

    s.add(
        of(
            "hotfix rollback --plan",
            "hotfix rollback",
            0,
            dir -> {
              fakeHotfix();
              return run(
                  "hotfix",
                  "rollback",
                  FakeHotfixOperations.ID,
                  "--plan",
                  "--json",
                  "--home",
                  emptyHome(dir).toString());
            }));
    s.add(
        of(
            "hotfix rollback --yes",
            "hotfix rollback",
            0,
            dir -> {
              fakeHotfix();
              return run(
                  "hotfix",
                  "rollback",
                  FakeHotfixOperations.ID,
                  "--yes",
                  "--json",
                  "--home",
                  emptyHome(dir).toString());
            }));

    s.add(
        of(
            "hotfix list with one installed",
            "hotfix list",
            0,
            dir -> {
              FakeHotfixOperations fake = fakeHotfix();
              Path home = emptyHome(dir);
              seedInstalledHotfix(home);
              fake.installed = List.of(installedFixture());
              return run("hotfix", "list", "--json", "--home", home.toString());
            }));
    s.add(
        of(
            "hotfix list empty",
            "hotfix list",
            0,
            dir -> {
              fakeHotfix();
              return run("hotfix", "list", "--json", "--home", emptyHome(dir).toString());
            }));

    s.add(
        of(
            "export --plan",
            "export",
            0,
            dir -> {
              fakeExim();
              serverHome(dir);
              return run(
                  join(
                      serverArgs(dir),
                      "export",
                      "--out",
                      dir.resolve("x.zip").toString(),
                      "--uri",
                      "/public",
                      "--plan"));
            }));
    s.add(
        of(
            "export --yes",
            "export",
            0,
            dir -> {
              fakeExim();
              serverHome(dir);
              return run(
                  join(
                      serverArgs(dir),
                      "export",
                      "--out",
                      dir.resolve("x.zip").toString(),
                      "--yes"));
            }));
    s.add(
        of(
            "export with a bad strategy",
            "export",
            1,
            dir -> {
              fakeExim();
              return run(
                  "export",
                  "--out",
                  dir.resolve("x.zip").toString(),
                  "--strategy",
                  "bogus",
                  "--json",
                  "--home",
                  emptyHome(dir).toString());
            }));

    s.add(
        of(
            "import --plan",
            "import",
            0,
            dir -> {
              fakeExim();
              serverHome(dir);
              Path archive = dir.resolve("in.zip");
              Files.writeString(archive, "zip");
              return run(join(serverArgs(dir), "import", archive.toString(), "--plan"));
            }));
    s.add(
        of(
            "import --yes",
            "import",
            0,
            dir -> {
              fakeExim();
              serverHome(dir);
              Path archive = dir.resolve("in.zip");
              Files.writeString(archive, "zip");
              return run(join(serverArgs(dir), "import", archive.toString(), "--yes"));
            }));
    s.add(
        of(
            "import with a bad secret reference",
            "import",
            1,
            dir -> {
              fakeExim();
              return run(
                  "import",
                  dir.resolve("in.zip").toString(),
                  "--source-keystore-password-ref",
                  "nope",
                  "--json",
                  "--home",
                  emptyHome(dir).toString());
            }));

    s.add(
        of(
            "upgrade without --to and --package",
            "upgrade",
            1,
            dir -> run("upgrade", "--json", "--home", emptyHome(dir).toString())));
    s.add(
        of(
            "upgrade samedb without the backup confirmation",
            "upgrade",
            2,
            dir ->
                run(
                    "upgrade",
                    "--to",
                    "9.0.0",
                    "--package",
                    dir.toString(),
                    "--mode",
                    "samedb",
                    "--json",
                    "--home",
                    emptyHome(dir).toString())));
    s.add(
        of(
            "upgrade rollback of an unknown run",
            "upgrade rollback",
            2,
            dir -> {
              serverHome(dir);
              return run(join(serverArgs(dir), "upgrade", "rollback", "r-nope", "--to-point", "B"));
            }));
    s.add(
        of(
            "upgrade rollback with a bad point",
            "upgrade rollback",
            1,
            dir ->
                run(
                    "upgrade",
                    "rollback",
                    "r-1",
                    "--to-point",
                    "X",
                    "--json",
                    "--home",
                    emptyHome(dir).toString())));

    s.add(
        of(
            "customizations register",
            "customizations register",
            0,
            dir -> {
              serverHome(dir);
              Path file = customisedFile(dir);
              return run(join(serverArgs(dir), "customizations", "register", file.toString()));
            }));
    s.add(
        of(
            "customizations register refuses a missing file",
            "customizations register",
            2,
            dir -> {
              serverHome(dir);
              return run(
                  join(
                      serverArgs(dir),
                      "customizations",
                      "register",
                      dir.resolve("missing.txt").toString()));
            }));
    s.add(
        of(
            "customizations list",
            "customizations list",
            0,
            dir -> {
              serverHome(dir);
              Path file = customisedFile(dir);
              run(join(serverArgs(dir), "customizations", "register", file.toString()));
              return run(join(serverArgs(dir), "customizations", "list"));
            }));
    s.add(
        of(
            "customizations diff identical",
            "customizations diff",
            0,
            dir -> {
              serverHome(dir);
              Path file = customisedFile(dir);
              run(join(serverArgs(dir), "customizations", "register", file.toString()));
              return run(join(serverArgs(dir), "customizations", "diff", file.toString()));
            }));
    s.add(
        of(
            "customizations diff changed",
            "customizations diff",
            1,
            dir -> {
              serverHome(dir);
              Path file = customisedFile(dir);
              run(join(serverArgs(dir), "customizations", "register", file.toString()));
              Files.writeString(file, "a=2\n", StandardCharsets.UTF_8);
              return run(join(serverArgs(dir), "customizations", "diff", file.toString()));
            }));
    s.add(
        of(
            "customizations unregister",
            "customizations unregister",
            0,
            dir -> {
              serverHome(dir);
              Path file = customisedFile(dir);
              run(join(serverArgs(dir), "customizations", "register", file.toString()));
              return run(join(serverArgs(dir), "customizations", "unregister", file.toString()));
            }));
    s.add(
        of(
            "customizations unregister of an unregistered file",
            "customizations unregister",
            2,
            dir -> {
              serverHome(dir);
              Path file = customisedFile(dir);
              return run(join(serverArgs(dir), "customizations", "unregister", file.toString()));
            }));

    s.add(
        of(
            "runs list",
            "runs list",
            0,
            dir -> {
              Path home = emptyHome(dir);
              seedFinishedRun(home, fakeHotfix(), bundle(dir));
              return run("runs", "list", "--json", "--home", home.toString());
            }));
    s.add(
        of(
            "runs list empty",
            "runs list",
            0,
            dir -> run("runs", "list", "--json", "--home", emptyHome(dir).toString())));
    s.add(
        of(
            "runs show",
            "runs show",
            0,
            dir -> {
              Path home = emptyHome(dir);
              seedFinishedRun(home, fakeHotfix(), bundle(dir));
              return run("runs", "show", "r-1", "--json", "--home", home.toString());
            }));
    s.add(
        of(
            "runs show unknown",
            "runs show",
            2,
            dir -> run("runs", "show", "r-nope", "--json", "--home", emptyHome(dir).toString())));
    s.add(
        of(
            "runs recover --resume",
            "runs recover",
            0,
            dir -> {
              Path home = emptyHome(dir);
              seedPendingRun(home, fakeHotfix(), bundle(dir));
              return run(
                  "runs",
                  "recover",
                  "r-2",
                  "--resume",
                  "--yes",
                  "--json",
                  "--home",
                  home.toString());
            }));
    s.add(
        of(
            "runs recover --rollback",
            "runs recover",
            3,
            dir -> {
              Path home = emptyHome(dir);
              seedPendingRun(home, fakeHotfix(), bundle(dir));
              return run(
                  "runs",
                  "recover",
                  "r-2",
                  "--rollback",
                  "--yes",
                  "--json",
                  "--home",
                  home.toString());
            }));
    s.add(
        of(
            "runs recover of a finished run",
            "runs recover",
            2,
            dir -> {
              Path home = emptyHome(dir);
              seedFinishedRun(home, fakeHotfix(), bundle(dir));
              return run("runs", "recover", "r-1", "--resume", "--json", "--home", home.toString());
            }));

    s.add(
        of(
            "keys list",
            "keys list",
            0,
            dir -> run("keys", "list", "--json", "--home", emptyHome(dir).toString())));
    s.add(
        of(
            "keys add",
            "keys add",
            0,
            dir -> {
              KeyPair pair = Ed25519.generate();
              Path pub = dir.resolve("partner.pub");
              Files.writeString(pub, Ed25519.encodePublic(pair.getPublic()) + "\n");
              return run(
                  "keys",
                  "add",
                  "partner",
                  pub.toString(),
                  "--json",
                  "--home",
                  emptyHome(dir).toString());
            }));
    s.add(
        of(
            "keys add of a non-key",
            "keys add",
            2,
            dir -> {
              Path bad = dir.resolve("bad.pub");
              Files.writeString(bad, "not a key");
              return run(
                  "keys",
                  "add",
                  "partner",
                  bad.toString(),
                  "--json",
                  "--home",
                  emptyHome(dir).toString());
            }));
    s.add(
        of(
            "keys remove",
            "keys remove",
            0,
            dir -> {
              Path home = emptyHome(dir);
              run(
                  "keys",
                  "generate",
                  "partner",
                  "--private-out",
                  dir.resolve("p.key").toString(),
                  "--home",
                  home.toString());
              return run("keys", "remove", "partner", "--json", "--home", home.toString());
            }));
    s.add(
        of(
            "keys remove unknown",
            "keys remove",
            2,
            dir -> run("keys", "remove", "nobody", "--json", "--home", emptyHome(dir).toString())));
    s.add(
        of(
            "keys generate",
            "keys generate",
            0,
            dir ->
                run(
                    "keys",
                    "generate",
                    "customer",
                    "--private-out",
                    dir.resolve("c.key").toString(),
                    "--json",
                    "--home",
                    emptyHome(dir).toString())));
    s.add(
        of(
            "keys generate refuses to overwrite",
            "keys generate",
            2,
            dir -> {
              Files.writeString(dir.resolve("c.key"), "keep");
              return run(
                  "keys",
                  "generate",
                  "customer",
                  "--private-out",
                  dir.resolve("c.key").toString(),
                  "--json",
                  "--home",
                  emptyHome(dir).toString());
            }));

    s.add(
        of(
            "secrets init",
            "secrets init",
            0,
            dir -> {
              withSecretsEnv();
              return run("secrets", "init", "--json", "--home", emptyHome(dir).toString());
            }));
    s.add(
        of(
            "secrets init without a passphrase",
            "secrets init",
            2,
            dir -> {
              Env.override(Map.of());
              return run("secrets", "init", "--json", "--home", emptyHome(dir).toString());
            }));
    s.add(
        of(
            "secrets set",
            "secrets set",
            0,
            dir -> {
              withSecretsEnv();
              Path home = emptyHome(dir);
              run("secrets", "init", "--home", home.toString());
              return run(
                  "secrets",
                  "set",
                  "db",
                  "--from-env",
                  "MY_SECRET",
                  "--json",
                  "--home",
                  home.toString());
            }));
    s.add(
        of(
            "secrets set from a missing variable",
            "secrets set",
            2,
            dir -> {
              withSecretsEnv();
              return run(
                  "secrets",
                  "set",
                  "db",
                  "--from-env",
                  "NOT_SET",
                  "--json",
                  "--home",
                  emptyHome(dir).toString());
            }));
    s.add(
        of(
            "secrets remove",
            "secrets remove",
            0,
            dir -> {
              withSecretsEnv();
              Path home = emptyHome(dir);
              run("secrets", "init", "--home", home.toString());
              run("secrets", "set", "db", "--from-env", "MY_SECRET", "--home", home.toString());
              return run("secrets", "remove", "db", "--json", "--home", home.toString());
            }));
    s.add(
        of(
            "secrets remove unknown",
            "secrets remove",
            2,
            dir -> {
              withSecretsEnv();
              Path home = emptyHome(dir);
              run("secrets", "init", "--home", home.toString());
              return run("secrets", "remove", "nope", "--json", "--home", home.toString());
            }));
    s.add(
        of(
            "secrets list",
            "secrets list",
            0,
            dir -> {
              withSecretsEnv();
              Path home = emptyHome(dir);
              run("secrets", "init", "--home", home.toString());
              run("secrets", "set", "db", "--from-env", "MY_SECRET", "--home", home.toString());
              return run("secrets", "list", "--json", "--home", home.toString());
            }));
    s.add(
        of(
            "secrets list before init",
            "secrets list",
            0,
            dir -> run("secrets", "list", "--json", "--home", emptyHome(dir).toString())));

    // commands arriving from other phase-8 branches: exercised as soon as they exist in the tree
    Set<String> present = leafCommands();
    if (present.contains("runs prune")) {
      s.add(
          of(
              "runs prune with nothing to prune",
              "runs prune",
              0,
              dir -> run("runs", "prune", "--json", "--home", emptyHome(dir).toString())));
    }
    if (present.contains("docs")) {
      s.add(of("docs lists the embedded documents", "docs", 0, dir -> run("docs", "--json")));
    }

    s.add(
        of(
            "console refuses a non-loopback bind without TLS",
            "console",
            2,
            dir ->
                run(
                    "console",
                    "--bind",
                    "0.0.0.0",
                    "--no-open",
                    "--json",
                    "--home",
                    emptyHome(dir).toString())));

    return s;
  }

  private static HotfixInstalled installedFixture() {
    return new HotfixInstalled(
        FakeHotfixOperations.ID,
        "1",
        FakeHotfixOperations.TITLE,
        "r-1",
        Optional.of("snap-1"),
        HotfixState.INSTALLED,
        T0);
  }
}
