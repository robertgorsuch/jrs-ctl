package com.jaspersoft.jrsctl.ops.hotfix;

import com.jaspersoft.jrsctl.core.crypto.Ed25519;
import com.jaspersoft.jrsctl.core.engine.CancellationToken;
import com.jaspersoft.jrsctl.core.engine.Context;
import com.jaspersoft.jrsctl.core.engine.Plan;
import com.jaspersoft.jrsctl.core.engine.RunOptions;
import com.jaspersoft.jrsctl.core.engine.RunOutcome;
import com.jaspersoft.jrsctl.core.engine.Runner;
import com.jaspersoft.jrsctl.core.engine.Sleeper;
import com.jaspersoft.jrsctl.core.engine.Step;
import com.jaspersoft.jrsctl.core.event.Event;
import com.jaspersoft.jrsctl.core.keys.KeyRing;
import com.jaspersoft.jrsctl.core.platform.Platforms;
import com.jaspersoft.jrsctl.core.secrets.SecretRef;
import com.jaspersoft.jrsctl.core.snapshot.SnapshotStore;
import com.jaspersoft.jrsctl.core.state.StateStore;
import com.jaspersoft.jrsctl.ops.FakeLayout;
import com.jaspersoft.jrsctl.ops.FakeServices;
import com.jaspersoft.jrsctl.ops.Services;
import com.jaspersoft.jrsctl.ops.db.FakeJdbcConnector;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A complete hotfix test bed in a temp directory: a fake server layout with the files the sample
 * manifests touch, a jrsctl home with a trusted test key, a signing key behind {@code env:HF_KEY},
 * fake JDBC and HTTP, and helpers to build bundles and run plans through the real {@link Runner}.
 */
public final class HotfixFixture implements AutoCloseable {

  static final String ID = "JRS-8.2.0-HF-0007";
  static final String ID2 = "JRS-8.2.0-HF-0008";
  static final String SIGNER = "test-signer";
  static final String LIB = "webapps/jasperserver-pro/WEB-INF/lib/";
  static final String CLASSES = "webapps/jasperserver-pro/WEB-INF/classes/";
  static final String FOO = LIB + "foo-1.2.3.jar";
  static final String FOO_OLDER = LIB + "foo-1.2.2.jar";
  static final String BAR = LIB + "bar-0.9.jar";
  static final String FIX = CLASSES + "fix.properties";
  static final String SCRIPT = "webapps/jasperserver-pro/scripts/fix.js";

  static final String OLD_FOO = "old foo bytes";
  static final String OLDER_FOO = "older foo bytes";
  static final String BAR_BYTES = "bar bytes";
  static final String NEW_FOO = "new foo bytes";
  static final String NEWER_FOO = "newer foo bytes";
  static final String FIX_BYTES = "fix=1\n";
  static final String SCRIPT_BYTES = "console.log('fix');\n";

  static final String WEBINF_MANIFEST =
      """
      {
        "id": "%s",
        "version": "1",
        "title": "Fix scheduler NPE",
        "applies": { "versions": [">=8.0.0 <9.0.0"], "editions": ["PRO"], "tenancy": ["SINGLE", "MULTI"] },
        "requires": [],
        "conflicts": [],
        "files": [
          { "action": "replace", "path": "%s", "replaces": ["foo-1.2.2.jar"] },
          { "action": "add", "path": "%s" },
          { "action": "delete", "path": "%s" }
        ],
        "restart": "required",
        "prechecks": [ { "type": "fileExists", "path": "%s" } ],
        "postchecks": [ { "type": "fileExists", "path": "%s" } ],
        "rollback": "snapshot"
      }
      """
          .formatted(ID, FOO, FIX, BAR, FOO, FIX);

  static final String NONE_MANIFEST =
      """
      {
        "id": "%s",
        "version": "1",
        "title": "Patch a script",
        "applies": { "versions": [">=8.0.0 <9.0.0"] },
        "files": [ { "action": "add", "path": "%s" } ],
        "restart": "none",
        "rollback": "snapshot"
      }
      """
          .formatted(ID, SCRIPT);

  static final String SECOND_MANIFEST =
      """
      {
        "id": "%s",
        "version": "1",
        "title": "Newer foo",
        "applies": { "versions": [">=8.0.0 <9.0.0"] },
        "requires": %s,
        "files": [ { "action": "replace", "path": "%s" } ],
        "restart": "required",
        "rollback": "snapshot"
      }
      """;

  static final Map<String, String> WEBINF_FILES =
      Map.of("payload/" + FOO, NEW_FOO, "payload/" + FIX, FIX_BYTES);

  public final FakeServices fake;
  public final Services services;
  public final Path root;
  public final Path installDir;
  public final Path tomcatDir;
  public final FakeJdbcConnector jdbc = new FakeJdbcConnector();
  public final Map<String, Integer> httpStatuses = new HashMap<>();
  public final List<Event> events = new ArrayList<>();
  public final SecretRef keyRef = new SecretRef.Env("HF_KEY");
  public final KeyPair keyPair = Ed25519.generate();

  private HotfixFixture(Path root, String extraYaml) throws IOException {
    this.root = root;
    this.installDir = FakeLayout.linux(Files.createDirectories(root.resolve("jrs")));
    this.tomcatDir = installDir.resolve("apache-tomcat");
    write(target(FOO), OLD_FOO);
    write(target(FOO_OLDER), OLDER_FOO);
    write(target(BAR), BAR_BYTES);
    Files.createDirectories(root.resolve("drivers"));
    this.fake =
        FakeServices.in(
            root.resolve("home"),
            Platforms.osFamily(System.getProperty("os.name", "")).orElseThrow());
    fake.platform.realFiles = true;
    fake.env.put("HF_KEY", Ed25519.encodePrivate(keyPair.getPrivate()));
    fake.env.put("DB_PW", "db-secret");
    fake.yaml(
        """
        server:
          baseUrl: http://localhost:8080/jasperserver-pro
          webappName: jasperserver-pro
          installDir: %s
          tomcatDir: %s
          auth:
            mode: basic
            username: jasperadmin
            passwordRef: env:JRS_PASSWORD
        service:
          kind: systemd
          name: jasperreports
          stopTimeoutSeconds: 180
        %s
        """
            .formatted(slashes(installDir), slashes(tomcatDir), extraYaml));
    this.services = fake.build();
    new KeyRing(fake.home).add(SIGNER, keyPair.getPublic());
  }

  public static HotfixFixture create(Path root) throws IOException {
    return new HotfixFixture(root, "");
  }

  public static HotfixFixture create(Path root, String extraYaml) throws IOException {
    return new HotfixFixture(root, extraYaml);
  }

  /** Database section pointing at the (empty) driver directory a fixture at {@code root} has. */
  public static String databaseYaml(Path root) {
    return """
        database:
          type: postgresql
          url: jdbc:postgresql://db.example/jrs
          username: jasperdb
          passwordRef: env:DB_PW
          driverDir: %s
        """
        .formatted(slashes(root.resolve("drivers")));
  }

  public DefaultHotfixOperations ops() {
    return new DefaultHotfixOperations(
        new HotfixRuntime(
            services,
            new SnapshotStore(fake.home, services.platform().files(), fake.clock),
            jdbc,
            new KeyRing(fake.home),
            path -> httpStatuses.getOrDefault(path, 200),
            Sleeper.none()));
  }

  public StateStore store() {
    return fake.stateStore();
  }

  public Path target(String manifestPath) {
    return manifestPath.startsWith("webapps/")
        ? tomcatDir.resolve(manifestPath)
        : installDir.resolve(manifestPath);
  }

  public String sha(Path file) throws IOException {
    return services.platform().files().sha256(file);
  }

  public String sha(String manifestPath) throws IOException {
    return sha(target(manifestPath));
  }

  /** Writes a bundle directory: manifest plus bundle-relative files. */
  public Path bundleDir(String name, String manifestJson, Map<String, String> files)
      throws IOException {
    Path dir = Files.createDirectories(root.resolve("bundles").resolve(name));
    Files.writeString(dir.resolve("manifest.json"), manifestJson, StandardCharsets.UTF_8);
    for (Map.Entry<String, String> e : files.entrySet()) {
      write(dir.resolve(e.getKey()), e.getValue());
    }
    return dir;
  }

  public Path build(Path dir) throws IOException {
    Path out = Files.createDirectories(root.resolve("out")).resolve(dir.getFileName() + ".zip");
    return ops().build(dir, keyRef, out);
  }

  public Path buildWebInf() throws IOException {
    return build(bundleDir("webinf", WEBINF_MANIFEST, WEBINF_FILES));
  }

  public Path buildNone() throws IOException {
    return build(bundleDir("none", NONE_MANIFEST, Map.of("payload/" + SCRIPT, SCRIPT_BYTES)));
  }

  public Path buildSecond(String requiresJson) throws IOException {
    return build(
        bundleDir(
            "second",
            SECOND_MANIFEST.formatted(ID2, requiresJson, FOO),
            Map.of("payload/" + FOO, NEWER_FOO)));
  }

  public Context ctx(String runId) {
    return new Context(runId, fake.home, services.platform(), new CancellationToken(), Map.of());
  }

  public RunOutcome run(Plan plan, String runId) {
    Runner runner = new Runner(store(), events::add, fake.clock, Sleeper.none());
    return runner.run(plan, ctx(runId), plan.fingerprint(), RunOptions.DEFAULT);
  }

  public static List<String> ids(Plan plan) {
    return plan.steps().stream().map(Step::id).toList();
  }

  public static Step step(Plan plan, String id) {
    return plan.steps().stream().filter(s -> s.id().equals(id)).findFirst().orElseThrow();
  }

  public static void write(Path file, String content) throws IOException {
    Files.createDirectories(file.getParent());
    Files.writeString(file, content, StandardCharsets.UTF_8);
  }

  static String slashes(Path path) {
    return path.toAbsolutePath().toString().replace('\\', '/');
  }

  @Override
  public void close() {
    fake.close();
  }
}
