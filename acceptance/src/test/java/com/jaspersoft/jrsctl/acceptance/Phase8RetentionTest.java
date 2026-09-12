package com.jaspersoft.jrsctl.acceptance;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.jaspersoft.jrsctl.core.JrsctlHome;
import com.jaspersoft.jrsctl.core.engine.RunLock;
import com.jaspersoft.jrsctl.core.engine.TerminalState;
import com.jaspersoft.jrsctl.core.platform.FileOps;
import com.jaspersoft.jrsctl.core.platform.Platforms;
import com.jaspersoft.jrsctl.core.snapshot.Snapshot;
import com.jaspersoft.jrsctl.core.snapshot.SnapshotStore;
import com.jaspersoft.jrsctl.core.state.SnapshotRecord;
import com.jaspersoft.jrsctl.core.state.StateStore;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.io.TempDir;

/**
 * Spec §14 Phase 8, retention pruning through the packaged jar (spec §5.6): two successful upgrade
 * runs are seeded through the state store and snapshot store (the documented recording path {@code
 * RecordUpgrade} uses, with {@code referenced_by = 'upgrade'}), hotfix A is applied and rolled
 * back, hotfix B is applied, then {@code runs prune} with {@code backups.maxSnapshots=1} removes
 * A's snapshot, the rollback's pre-rollback snapshot and the older upgrade's set while B's (an
 * installed hotfix) and the latest upgrade's survive, on disk and in the {@code snapshots} table.
 * {@code --dry-run} changes nothing and the command exits 9 while another process holds the run
 * lock. The steps share one home, so they run in order.
 */
@Tag("phase8")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Phase8RetentionTest {

  private static final String WEBAPP = "/jasperserver-pro";
  private static final String HOTFIX_A = "JRS-8.2.0-HF-0101";
  private static final String HOTFIX_B = "JRS-8.2.0-HF-0102";
  private static final String REPLACED = "webapps/jasperserver-pro/scripts/jrsctl-fix.js";
  private static final String ORIGINAL_JS = "// original\nconsole.log('before');\n";
  private static final String UPGRADE_OLD = "r-20260801-000000-upg1";
  private static final String UPGRADE_NEW = "r-20260802-000000-upg2";
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

  private static WireMockServer server;
  private static Cli cli;

  @TempDir static Path tmp;

  private static Path home;
  private static Path privateKey;
  private static Path bundleA;
  private static Path bundleB;
  private static String applyARun;
  private static String applyBRun;

  @BeforeAll
  static void setUp() throws Exception {
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
            .willReturn(
                aResponse()
                    .withStatus(404)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"errorCode\":\"no.such.export.process\",\"message\":\"No task jrsctl-probe\"}")));
    server.stubFor(
        get(urlPathEqualTo(WEBAPP + "/rest_v2/import/jrsctl-probe/state"))
            .willReturn(
                aResponse()
                    .withStatus(404)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"errorCode\":\"no.such.export.process\",\"message\":\"No task jrsctl-probe\"}")));
    server.stubFor(
        post(urlPathEqualTo(WEBAPP + "/rest_v2/login"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Set-Cookie", "JSESSIONID=ABCDEF0123456789; Path=/; HttpOnly")));

    cli = new Cli(Map.of("JRS_PASSWORD", "jasperadmin"));
    home = Files.createDirectories(tmp.resolve("home"));
    Path install = fakeLayout(tmp.resolve("jrs"));
    Path tomcat = install.resolve("apache-tomcat-9");
    privateKey = tmp.resolve("keys").resolve("customer.key");
    bundleA = tmp.resolve(HOTFIX_A + ".zip");
    bundleB = tmp.resolve(HOTFIX_B + ".zip");
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
            .formatted(server.port(), WEBAPP, unix(install), unix(tomcat)),
        StandardCharsets.UTF_8);
  }

  @AfterAll
  static void tearDown() {
    if (server != null) {
      server.stop();
    }
  }

  // ---- fixtures -------------------------------------------------------------------------------

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
        webapp.resolve("scripts").resolve("jrsctl-fix.js"), ORIGINAL_JS, StandardCharsets.UTF_8);
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

  /** A bundle directory replacing {@code REPLACED} with a hotfix-specific script. */
  private static Path fixtureDir(Path dir, String hotfixId) throws Exception {
    Path payload = dir.resolve("payload");
    Path replaced = payload.resolve(REPLACED);
    Files.createDirectories(replaced.getParent());
    Files.writeString(
        replaced, "// fixed by " + hotfixId + "\nconsole.log('after');\n", StandardCharsets.UTF_8);
    Files.writeString(
        dir.resolve("manifest.json"),
        """
        {
          "id": "%s",
          "version": "1",
          "title": "Phase 8 retention fixture %s",
          "applies": { "versions": [">=8.2.0 <8.3.0"], "editions": ["PRO"] },
          "requires": [],
          "conflicts": [],
          "files": [
            { "action": "replace", "path": "%s", "sha256": "%s" }
          ],
          "restart": "none",
          "prechecks": [ { "type": "fileExists", "path": "%s" } ],
          "postchecks": [],
          "rollback": "snapshot"
        }
        """
            .formatted(hotfixId, hotfixId, REPLACED, sha256(replaced), REPLACED),
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

  /**
   * Records a {@code SUCCEEDED} upgrade run with one snapshot in its {@code referenced_by =
   * 'upgrade'} set, the way {@code RecordUpgrade} does, created {@code age} ago.
   */
  private static Snapshot seedUpgrade(String runId, Duration age) throws Exception {
    JrsctlHome jrsctlHome = new JrsctlHome(home);
    FileOps files = Platforms.detect().files();
    Instant started = Instant.now().minus(age);
    Path src = Files.createDirectories(tmp.resolve("upgrade-src").resolve(runId));
    Path file = src.resolve("jasperserver.properties");
    Files.writeString(file, "upgraded=" + runId + "\n", StandardCharsets.UTF_8);
    Snapshot snapshot =
        new SnapshotStore(jrsctlHome, files, Clock.fixed(started, ZoneOffset.UTC))
            .create(runId, "config", List.of(file), src);
    try (StateStore store = StateStore.open(jrsctlHome, Clock.systemUTC())) {
      store.recordRunStart(runId, "upgrade", Optional.empty(), started);
      store.recordSnapshot(
          new SnapshotRecord(
              runId + "/config",
              runId,
              "config",
              snapshot.dir(),
              files.sha256(snapshot.manifestFile()),
              Optional.of("upgrade")));
      store.recordRunEnd(runId, started.plusSeconds(300), TerminalState.SUCCEEDED, 0);
    }
    return snapshot;
  }

  private static Path snapshotDir(String runId) {
    return home.resolve("snapshots").resolve(runId);
  }

  private static Cli.Result jrsctl(String... args) throws Exception {
    // --ascii as well as --no-color: since review 3.5 the two are separate decisions, so a
    // UTF-8 terminal without colour still gets the tick glyphs. Assertions on the words need
    // to ask for the words.
    String[] all = new String[args.length + 4];
    System.arraycopy(args, 0, all, 0, args.length);
    all[args.length] = "--home";
    all[args.length + 1] = home.toString();
    all[args.length + 2] = "--no-color";
    all[args.length + 3] = "--ascii";
    return cli.run(all);
  }

  /** Run ids of every {@code hotfix.apply} run, most recent first. */
  private static List<String> hotfixApplyRuns() throws Exception {
    JsonNode rows = new ObjectMapper().readTree(jrsctl("runs", "list", "--json").stdout());
    List<String> ids = new ArrayList<>();
    for (JsonNode row : rows) {
      if (row.get("operation").asText().equals("hotfix.apply")) {
        ids.add(row.get("runId").asText());
      }
    }
    return ids;
  }

  private static List<String> removedRuns(JsonNode root) {
    List<String> ids = new ArrayList<>();
    for (JsonNode r : root.get("removed")) {
      ids.add(r.get("runId").asText());
    }
    return ids;
  }

  // ---- criteria -------------------------------------------------------------------------------

  @Test
  @Order(1)
  void two_upgrades_are_seeded_then_hotfix_a_is_applied_and_rolled_back_and_b_applied()
      throws Exception {
    seedUpgrade(UPGRADE_OLD, Duration.ofDays(10));
    seedUpgrade(UPGRADE_NEW, Duration.ofDays(9));

    jrsctl("keys", "generate", "customer", "--private-out", privateKey.toString()).assertExit(0);
    for (Map.Entry<String, Path> pair :
        List.of(Map.entry(HOTFIX_A, bundleA), Map.entry(HOTFIX_B, bundleB))) {
      Path fixture = fixtureDir(tmp.resolve("fixture-" + pair.getKey()), pair.getKey());
      jrsctl(
              "hotfix",
              "build",
              fixture.toString(),
              "--key",
              "file:" + privateKey,
              "--out",
              pair.getValue().toString())
          .assertExit(0);
    }

    jrsctl("hotfix", "apply", bundleA.toString(), "--yes").assertExit(0);
    jrsctl("hotfix", "rollback", HOTFIX_A, "--yes").assertExit(0);
    jrsctl("hotfix", "apply", bundleB.toString(), "--yes").assertExit(0);

    List<String> applies = hotfixApplyRuns();
    assertThat(applies).hasSize(2);
    applyBRun = applies.get(0);
    applyARun = applies.get(1);
    assertThat(snapshotDir(applyARun).resolve("snapshot").resolve("manifest.json")).exists();
    assertThat(snapshotDir(applyBRun).resolve("snapshot").resolve("manifest.json")).exists();
    JsonNode hotfixes = new ObjectMapper().readTree(jrsctl("hotfix", "list", "--json").stdout());
    assertThat(hotfixes).hasSize(2);
  }

  @Test
  @Order(2)
  void runs_prune_dry_run_lists_the_unreferenced_snapshots_and_changes_nothing() throws Exception {
    Cli.Result dry =
        jrsctl("runs", "prune", "--dry-run", "--json", "--set", "backups.maxSnapshots=1")
            .assertExit(0);

    JsonNode root = new ObjectMapper().readTree(dry.stdout());
    assertThat(root.get("dryRun").asBoolean()).isTrue();
    assertThat(removedRuns(root))
        .contains(applyARun, UPGRADE_OLD)
        .doesNotContain(applyBRun, UPGRADE_NEW);
    assertThat(root.get("kept").asInt()).isEqualTo(2);
    assertThat(root.get("protected").asInt()).isEqualTo(2);
    assertThat(snapshotDir(applyARun).resolve("snapshot").resolve("manifest.json")).exists();
    assertThat(snapshotDir(UPGRADE_OLD).resolve("config").resolve("manifest.json")).exists();
    JsonNode shown =
        new ObjectMapper().readTree(jrsctl("runs", "show", applyARun, "--json").stdout());
    assertThat(shown.get("snapshots")).hasSize(1);
  }

  @Test
  @Order(3)
  void runs_prune_exits_9_while_another_process_holds_the_run_lock() throws Exception {
    try (RunLock held = new RunLock(new JrsctlHome(home), "r-acceptance-holder", Instant.now())) {
      Cli.Result refused = jrsctl("runs", "prune", "--set", "backups.maxSnapshots=1").assertExit(9);
      assertThat(refused.stderr()).contains("r-acceptance-holder").contains("pid");
    }
    assertThat(snapshotDir(applyARun).resolve("snapshot").resolve("manifest.json")).exists();
  }

  @Test
  @Order(4)
  void runs_prune_removes_unreferenced_snapshots_and_keeps_installed_hotfix_and_latest_upgrade()
      throws Exception {
    Cli.Result pruned =
        jrsctl("runs", "prune", "--json", "--set", "backups.maxSnapshots=1").assertExit(0);

    JsonNode root = new ObjectMapper().readTree(pruned.stdout());
    List<String> fields = new ArrayList<>();
    root.fieldNames().forEachRemaining(fields::add);
    assertThat(fields).containsExactly("dryRun", "removed", "kept", "protected");
    assertThat(root.get("dryRun").asBoolean()).isFalse();
    assertThat(removedRuns(root))
        .contains(applyARun, UPGRADE_OLD)
        .doesNotContain(applyBRun, UPGRADE_NEW);
    for (JsonNode r : root.get("removed")) {
      List<String> keys = new ArrayList<>();
      r.fieldNames().forEachRemaining(keys::add);
      assertThat(keys).containsExactly("id", "runId", "stepId", "path");
      assertThat(Path.of(r.get("path").asText())).doesNotExist();
    }
    assertThat(root.get("kept").asInt()).isEqualTo(2);
    assertThat(root.get("protected").asInt()).isEqualTo(2);

    assertThat(snapshotDir(applyARun)).doesNotExist();
    assertThat(snapshotDir(UPGRADE_OLD)).doesNotExist();
    assertThat(snapshotDir(applyBRun).resolve("snapshot").resolve("manifest.json")).exists();
    assertThat(snapshotDir(UPGRADE_NEW).resolve("config").resolve("manifest.json")).exists();

    ObjectMapper mapper = new ObjectMapper();
    assertThat(
            mapper.readTree(jrsctl("runs", "show", applyARun, "--json").stdout()).get("snapshots"))
        .isEmpty();
    assertThat(
            mapper.readTree(jrsctl("runs", "show", applyBRun, "--json").stdout()).get("snapshots"))
        .hasSize(1);
    assertThat(
            mapper
                .readTree(jrsctl("runs", "show", UPGRADE_OLD, "--json").stdout())
                .get("snapshots"))
        .isEmpty();
    assertThat(
            mapper
                .readTree(jrsctl("runs", "show", UPGRADE_NEW, "--json").stdout())
                .get("snapshots"))
        .hasSize(1);

    Cli.Result again = jrsctl("runs", "prune", "--set", "backups.maxSnapshots=1").assertExit(0);
    assertThat(again.stdout()).contains("nothing to prune").contains("2 protected");
    assertThat(hotfixApplyRuns()).as("pruning is not a journaled run").hasSize(2);
  }
}
