package com.jaspersoft.jrsctl.acceptance;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * Phase 8 crash injection (spec §6.1, §6.6, §14): the packaged jar runs {@code export --strategy
 * vendor} against a fake Tomcat layout whose {@code js-export} blocks until a sentinel file
 * appears, so the run hangs inside a mutating step after the service was stopped. The test then
 * kills the jar with the operating system's own command ({@code taskkill /F /PID} on Windows,
 * {@code kill -9} elsewhere), never {@code Process.destroyForcibly}, and checks that: the run is
 * left pending and blocks every non-interactive mutation with exit 8 and the exact {@code runs
 * recover} command while the OS file lock is released; {@code runs recover --resume} re-executes
 * the interrupted step and ends in the same file, service and journal state as a clean run on an
 * identical fresh fixture; {@code runs recover --rollback} compensates back to the pre-run fixture
 * (archive gone, service running again, exit 3 = rolled back); and a second crash during the resume
 * itself still converges after one more recovery. Each scenario starts from its own JRSCTL_HOME and
 * install tree; every fake {@code js-export} invocation registers itself and waits for its own
 * sentinel, so an orphaned script from a killed run can never write the archive of a later one.
 */
@Tag("phase8")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Phase8CrashRecoveryTest {

  private static final String WEBAPP = "/jasperserver-pro";
  private static final String HUNG_STEP = "export.js-export";
  private static final String STOP_STEP = "export.stop-service";
  private static final Duration STEP_TIMEOUT = Duration.ofMinutes(3);
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

  private static final boolean WINDOWS =
      System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
  private static final ObjectMapper JSON = new ObjectMapper();

  private static WireMockServer server;
  private static Cli cli;
  private static Path root;
  private static Path vendorJdk;
  private static final List<Fixture> fixtures = new ArrayList<>();

  private static Fixture crashed;
  private static String crashedRunId;
  private static Fixture clean;

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
    // A private temp root instead of @TempDir: orphaned fake scripts and the Tomcat stand-in may
    // outlive a scenario by a second or two, and their cleanup must never fail the suite.
    root = Files.createTempDirectory("jrsctl-phase8-");
    vendorJdk = Files.createDirectories(root.resolve("jdk"));
    cli = new Cli(Map.of("JRS_PASSWORD", "jasperadmin"));
  }

  @AfterAll
  static void tearDown() throws Exception {
    for (Fixture f : fixtures) {
      f.abortOrphans();
    }
    for (Fixture f : fixtures) {
      f.stopTomcat();
    }
    Thread.sleep(3000);
    if (server != null) {
      server.stop();
    }
    if (root != null) {
      deleteBestEffort(root);
    }
  }

  // ---------------------------------------------------------------- scenarios

  @Test
  @Order(1)
  void crash_mid_step_leaves_a_pending_run_that_blocks_mutations_and_frees_the_lock()
      throws Exception {
    crashed = new Fixture("crash-resume");

    Cli.Running run = crashed.startExport(crashed.output);
    crashedRunId = crashMidExport(crashed, 1, run);

    JsonNode row = runRow(crashed, crashedRunId);
    assertThat(row.get("pending").asBoolean()).as(row.toString()).isTrue();
    assertThat(terminalState(crashed, crashedRunId)).isEqualTo("PENDING");
    assertThat(lastState(crashed, crashedRunId, HUNG_STEP)).isEqualTo("RUNNING");
    assertThat(lastState(crashed, crashedRunId, STOP_STEP)).isEqualTo("SUCCEEDED");
    assertThat(crashed.tomcatRunning()).as("service stopped by the killed run").isFalse();
    assertThat(crashed.output).doesNotExist();

    Cli.Result blocked = crashed.jrsctl(exportArgs(crashed.outDir.resolve("other.zip")));
    blocked.assertExit(8);
    assertThat(blocked.stderr())
        .contains("needs recovery")
        .contains("jrsctl runs recover " + crashedRunId + " --resume")
        .contains("jrsctl runs recover " + crashedRunId + " --rollback");
    assertThat(crashed.outDir.resolve("other.zip")).doesNotExist();

    assertThat(lockIsFree(crashed.home.resolve("runs.lock")))
        .as("the OS file lock died with the process")
        .isTrue();
  }

  @Test
  @Order(2)
  void resume_after_crash_ends_in_the_same_state_as_a_clean_run() throws Exception {
    assertThat(crashedRunId).as("order 1 left a pending run").isNotNull();

    crashed.release(2);
    Cli.Result resumed = crashed.jrsctl("runs", "recover", crashedRunId, "--resume");
    resumed.assertExit(0);

    Fixture reference = cleanFixture();

    assertThat(terminalState(crashed, crashedRunId)).isEqualTo("SUCCEEDED");
    assertThat(runningCount(crashed, crashedRunId, HUNG_STEP))
        .as("the hung step ran once before the crash and once on resume")
        .isEqualTo(2);
    assertThat(lastState(crashed, crashedRunId, HUNG_STEP)).isEqualTo("SUCCEEDED");
    assertThat(crashed.output).exists();
    assertThat(partOf(crashed.output)).doesNotExist();
    assertThat(sidecarOf(crashed.output)).exists();
    assertThat(crashed.tomcatRunning()).isTrue();
    assertThat(fileState(crashed)).isEqualTo(fileState(reference));
    assertThat(runRow(crashed, crashedRunId).get("pending").asBoolean()).isFalse();
  }

  @Test
  @Order(3)
  void rollback_after_crash_restores_the_pre_run_fixture() throws Exception {
    Fixture f = new Fixture("crash-rollback");

    Cli.Running run = f.startExport(f.output);
    String runId = crashMidExport(f, 1, run);

    Cli.Result rolled = f.jrsctl("runs", "recover", runId, "--rollback");
    rolled.assertExit(3);

    assertThat(terminalState(f, runId)).isEqualTo("ROLLED_BACK");
    assertThat(lastState(f, runId, HUNG_STEP)).isEqualTo("ROLLED_BACK");
    assertThat(lastState(f, runId, STOP_STEP)).isEqualTo("ROLLED_BACK");
    assertThat(f.output).doesNotExist();
    assertThat(partOf(f.output)).doesNotExist();
    assertThat(sidecarOf(f.output)).doesNotExist();
    assertThat(f.tomcatRunning()).as("the service stopped by the run is started again").isTrue();
    assertThat(fileState(f)).isEqualTo(f.pristine);
    List<String> planOnly = new ArrayList<>(List.of(exportArgs(f.outDir.resolve("after.zip"))));
    planOnly.add("--plan");
    assertThat(f.jrsctl(planOnly.toArray(String[]::new)).exitCode())
        .as("no pending run blocks new work any more")
        .isNotEqualTo(8);
  }

  @Test
  @Order(4)
  void second_crash_during_resume_still_converges_after_another_recovery() throws Exception {
    Fixture f = new Fixture("crash-twice");

    Cli.Running first = f.startExport(f.output);
    String runId = crashMidExport(f, 1, first);
    Cli.Running resume = f.start("runs", "recover", runId, "--resume");
    crashMidExport(f, 2, resume);

    assertThat(runRow(f, runId).get("pending").asBoolean()).isTrue();
    assertThat(runningCount(f, runId, HUNG_STEP)).isEqualTo(2);
    assertThat(lockIsFree(f.home.resolve("runs.lock"))).isTrue();

    f.release(3);
    f.jrsctl("runs", "recover", runId, "--resume").assertExit(0);

    assertThat(terminalState(f, runId)).isEqualTo("SUCCEEDED");
    assertThat(runningCount(f, runId, HUNG_STEP)).isEqualTo(3);
    assertThat(lastState(f, runId, HUNG_STEP)).isEqualTo("SUCCEEDED");
    assertThat(fileState(f)).isEqualTo(fileState(cleanFixture()));
    assertThat(pendingRuns(f)).isEmpty();
  }

  // ---------------------------------------------------------------- crash mechanics

  /**
   * Waits until fake js-export invocation {@code n} is blocked inside the run and the journal shows
   * the step RUNNING for the n-th time, then kills the jar with the OS command. Returns the run id.
   */
  private static String crashMidExport(Fixture f, int invocation, Cli.Running running)
      throws Exception {
    f.waitForInvocation(invocation, running);
    String runId = waitForPendingRun(f);
    waitUntil(
        "journal of " + runId + " shows " + HUNG_STEP + " RUNNING (" + invocation + "x)",
        STEP_TIMEOUT,
        () -> runningCount(f, runId, HUNG_STEP) >= invocation);
    assertThat(running.alive()).as("jrsctl still blocked in " + HUNG_STEP).isTrue();
    killHard(running.pid());
    assertThat(running.process().waitFor(30, TimeUnit.SECONDS))
        .as("jrsctl died after the OS kill")
        .isTrue();
    for (Path capture : List.of(running.out(), running.err())) {
      try {
        Files.deleteIfExists(capture);
      } catch (IOException e) {
        // the orphaned fake script inherited the handle; the file lives in the OS temp directory
      }
    }
    return runId;
  }

  /** {@code taskkill /F /PID} on Windows, {@code kill -9} elsewhere: the literal OS command. */
  private static void killHard(long pid) throws Exception {
    List<String> cmd =
        WINDOWS
            ? List.of("taskkill", "/F", "/PID", Long.toString(pid))
            : List.of("kill", "-9", Long.toString(pid));
    Process k = new ProcessBuilder(cmd).redirectErrorStream(true).start();
    String out = new String(k.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertThat(k.waitFor(30, TimeUnit.SECONDS)).isTrue();
    assertThat(k.exitValue()).as(String.join(" ", cmd) + ": " + out).isEqualTo(0);
  }

  private static boolean lockIsFree(Path lockFile) throws IOException {
    if (!Files.exists(lockFile)) {
      return true;
    }
    try (FileChannel ch =
        FileChannel.open(lockFile, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
      FileLock lock = ch.tryLock(1L << 40, 1, false);
      if (lock == null) {
        return false;
      }
      lock.release();
      return true;
    }
  }

  // ---------------------------------------------------------------- journal queries

  private static List<JsonNode> runs(Fixture f) throws Exception {
    Cli.Result list = f.jrsctl("runs", "list", "--json");
    if (list.exitCode() != 0) {
      return List.of();
    }
    List<JsonNode> rows = new ArrayList<>();
    JSON.readTree(list.stdout()).forEach(rows::add);
    return rows;
  }

  private static List<String> pendingRuns(Fixture f) throws Exception {
    List<String> ids = new ArrayList<>();
    for (JsonNode row : runs(f)) {
      if (row.path("pending").asBoolean(false)) {
        ids.add(row.get("runId").asText());
      }
    }
    return ids;
  }

  private static String waitForPendingRun(Fixture f) throws Exception {
    waitUntil("a pending run in " + f.home, STEP_TIMEOUT, () -> !pendingRuns(f).isEmpty());
    List<String> pending = pendingRuns(f);
    assertThat(pending).hasSize(1);
    return pending.get(0);
  }

  private static JsonNode runRow(Fixture f, String runId) throws Exception {
    for (JsonNode row : runs(f)) {
      if (row.get("runId").asText().equals(runId)) {
        return row;
      }
    }
    throw new AssertionError("run " + runId + " not listed by runs list --json");
  }

  private static String terminalState(Fixture f, String runId) throws Exception {
    JsonNode state = runRow(f, runId).path("terminalState");
    return state.isMissingNode() || state.isNull() ? "PENDING" : state.asText();
  }

  private static List<JsonNode> transitions(Fixture f, String runId) throws Exception {
    Cli.Result show = f.jrsctl("runs", "show", runId, "--json");
    if (show.exitCode() != 0) {
      return List.of();
    }
    List<JsonNode> rows = new ArrayList<>();
    JSON.readTree(show.stdout()).path("transitions").forEach(rows::add);
    return rows;
  }

  private static long runningCount(Fixture f, String runId, String stepId) throws Exception {
    return transitions(f, runId).stream()
        .filter(t -> t.path("stepId").asText().equals(stepId))
        .filter(t -> t.path("toState").asText().equals("RUNNING"))
        .count();
  }

  private static String lastState(Fixture f, String runId, String stepId) throws Exception {
    String last = "";
    for (JsonNode t : transitions(f, runId)) {
      if (t.path("stepId").asText().equals(stepId)) {
        last = t.path("toState").asText();
      }
    }
    return last;
  }

  // ---------------------------------------------------------------- end state

  /**
   * Everything the export may have changed: every file under the install tree (the fake scripts'
   * control directory excepted), every file in the output directory (the sidecar without its
   * timestamp), and whether the Tomcat stand-in is running.
   */
  private static Map<String, String> fileState(Fixture f) throws Exception {
    Map<String, String> state = new TreeMap<>();
    state.putAll(tree("install", f.install, p -> !p.startsWith(f.ctrl)));
    state.putAll(tree("out", f.outDir, p -> true));
    state.put("service.running", Boolean.toString(f.tomcatRunning()));
    return state;
  }

  private static Map<String, String> tree(String prefix, Path dir, Predicate<Path> include)
      throws IOException {
    Map<String, String> hashes = new TreeMap<>();
    if (!Files.isDirectory(dir)) {
      return hashes;
    }
    try (Stream<Path> files = Files.walk(dir)) {
      for (Path p : files.filter(Files::isRegularFile).filter(include).toList()) {
        String key = prefix + "/" + dir.relativize(p).toString().replace('\\', '/');
        hashes.put(key, key.endsWith(".jrsctl.json") ? sidecarWithoutTimestamp(p) : sha256(p));
      }
    }
    return hashes;
  }

  private static String sidecarWithoutTimestamp(Path sidecar) throws IOException {
    ObjectNode node = (ObjectNode) JSON.readTree(Files.readString(sidecar, StandardCharsets.UTF_8));
    node.remove("exportedAt");
    return node.toString();
  }

  private static String sha256(Path file) throws IOException {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      byte[] buf = new byte[64 * 1024];
      try (InputStream in = new DigestInputStream(Files.newInputStream(file), md)) {
        while (in.read(buf) != -1) {
          // digest updated by the stream
        }
      }
      return HexFormat.of().formatHex(md.digest());
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static Path partOf(Path file) {
    return file.resolveSibling(file.getFileName() + ".part");
  }

  private static Path sidecarOf(Path zip) {
    return zip.resolveSibling(zip.getFileName() + ".jrsctl.json");
  }

  private static String[] exportArgs(Path out) {
    return new String[] {
      "export", "--strategy", "vendor", "--uri", "/public", "--out", out.toString(), "--yes"
    };
  }

  /** The clean reference run: an identical fresh fixture whose js-export is released up front. */
  private static Fixture cleanFixture() throws Exception {
    if (clean == null) {
      Fixture f = new Fixture("clean");
      f.release(1);
      f.jrsctl(exportArgs(f.output)).assertExit(0);
      List<JsonNode> rows = runs(f);
      assertThat(rows).hasSize(1);
      assertThat(rows.get(0).get("terminalState").asText()).isEqualTo("SUCCEEDED");
      assertThat(f.tomcatRunning()).isTrue();
      clean = f;
    }
    return clean;
  }

  private static void waitUntil(String what, Duration timeout, Check condition) throws Exception {
    Instant deadline = Instant.now().plus(timeout);
    while (!condition.test()) {
      if (Instant.now().isAfter(deadline)) {
        throw new AssertionError("timed out after " + timeout + " waiting for " + what);
      }
      Thread.sleep(250);
    }
  }

  /** A poll that may itself run jrsctl. */
  private interface Check {
    boolean test() throws Exception;
  }

  private static void deleteBestEffort(Path dir) throws InterruptedException {
    for (int attempt = 0; attempt < 10 && Files.exists(dir); attempt++) {
      try (Stream<Path> walk = Files.walk(dir)) {
        for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
          try {
            Files.deleteIfExists(p);
          } catch (IOException e) {
            // still held by a stand-in or orphan; retried below
          }
        }
      } catch (IOException e) {
        // retried below
      }
      if (Files.exists(dir)) {
        Thread.sleep(500);
      }
    }
  }

  // ---------------------------------------------------------------- fixture

  /**
   * One fresh JRSCTL_HOME plus fake install tree. The fake {@code catalina} script starts and stops
   * a detectable stand-in process (a renamed {@code PING.EXE} on Windows, a marked {@code sleep}
   * elsewhere); the fake {@code js-export} numbers its invocations through {@code ctrl/invoked.N},
   * blocks until {@code ctrl/release.N} exists and exits 1 on {@code ctrl/abort}. Both scripts
   * derive every path from their own location, so two fixtures have byte-identical trees.
   */
  private static final class Fixture {
    final Path dir;
    final Path home;
    final Path install;
    final Path tomcat;
    final Path buildomatic;
    final Path ctrl;
    final Path outDir;
    final Path output;
    final Map<String, String> pristine;

    Fixture(String name) throws Exception {
      dir = Files.createDirectories(root.resolve(name));
      home = Files.createDirectories(dir.resolve("home"));
      install = dir.resolve("jrs");
      tomcat = install.resolve("apache-tomcat-9");
      buildomatic = install.resolve("buildomatic");
      ctrl = buildomatic.resolve("ctrl");
      outDir = Files.createDirectories(dir.resolve("out"));
      output = outDir.resolve("export.zip");
      layout();
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
            kind: catalina
            scriptPath: %s
            stopTimeoutSeconds: 60
          vendor:
            javaHome: %s
          network:
            mode: public
          """
              .formatted(
                  server.port(),
                  WEBAPP,
                  unix(install),
                  unix(tomcat),
                  unix(catalina()),
                  unix(vendorJdk)),
          StandardCharsets.UTF_8);
      fixtures.add(this);
      startTomcat();
      pristine = fileState(this);
      assertThat(pristine.get("service.running"))
          .as("stand-in running before the run")
          .isEqualTo("true");
    }

    private void layout() throws IOException {
      Path webapp = tomcat.resolve("webapps").resolve("jasperserver-pro");
      Files.createDirectories(webapp.resolve("WEB-INF").resolve("lib"));
      Files.createDirectories(webapp.resolve("WEB-INF").resolve("classes"));
      Files.write(webapp.resolve("WEB-INF").resolve("lib").resolve("x.jar"), new byte[128]);
      write(webapp.resolve("version.txt"), "8.2.0");
      write(webapp.resolve("META-INF").resolve("context.xml"), "<Context/>");
      write(
          tomcat.resolve("conf").resolve("server.xml"),
          "<Server><Service><Connector port=\"8080\" protocol=\"HTTP/1.1\"/></Service></Server>");
      write(
          tomcat
              .resolve("conf")
              .resolve("Catalina")
              .resolve("localhost")
              .resolve("jasperserver-pro.xml"),
          "<Context docBase=\"jasperserver-pro\"/>");
      Files.createDirectories(ctrl);
      write(
          buildomatic.resolve("default_master.properties"),
          "appServerType=tomcat\ndbType=postgresql\ndbHost=localhost\ndbPort=5432\n"
              + "dbUsername=jasperdb\ndbPassword=TopSecret\njs.dbName=jasperserver\n");
      write(buildomatic.resolve("js-import.bat"), "@echo off\r\nexit /b 0\r\n");
      write(buildomatic.resolve("js-import.sh"), "#!/bin/sh\nexit 0\n");
      write(buildomatic.resolve("js-ant.bat"), "@echo off\r\nexit /b 0\r\n");
      write(buildomatic.resolve("js-ant.sh"), "#!/bin/sh\nexit 0\n");
      write(
          buildomatic.resolve("js-export.bat"),
          "@echo off\r\n"
              + "setlocal EnableExtensions\r\n"
              + "set \"CTRL=%~dp0ctrl\"\r\n"
              + "set /a N=0\r\n"
              + ":count\r\n"
              + "set /a N+=1\r\n"
              + "if exist \"%CTRL%\\invoked.%N%\" goto count\r\n"
              + "echo %N% > \"%CTRL%\\invoked.%N%\"\r\n"
              + ":wait\r\n"
              + "if exist \"%CTRL%\\abort\" exit /b 1\r\n"
              + "if exist \"%CTRL%\\release.%N%\" goto go\r\n"
              + "ping -n 2 127.0.0.1 >nul\r\n"
              + "goto wait\r\n"
              + ":go\r\n"
              + "echo fake export > \"%~2\"\r\n"
              + "exit /b 0\r\n");
      write(
          buildomatic.resolve("js-export.sh"),
          "#!/bin/sh\n"
              + "CTRL=\"$(dirname \"$0\")/ctrl\"\n"
              + "N=0\n"
              + "while :; do N=$((N+1)); [ -e \"$CTRL/invoked.$N\" ] || break; done\n"
              + "echo \"$N\" > \"$CTRL/invoked.$N\"\n"
              + "while :; do\n"
              + "  [ -e \"$CTRL/abort\" ] && exit 1\n"
              + "  [ -e \"$CTRL/release.$N\" ] && break\n"
              + "  sleep 1\n"
              + "done\n"
              + "echo fake export > \"$2\"\n"
              + "exit 0\n");
      Path bin = Files.createDirectories(tomcat.resolve("bin"));
      if (WINDOWS) {
        Files.copy(
            Path.of(System.getenv("SystemRoot"), "System32", "PING.EXE"),
            bin.resolve("tomcat9.exe"),
            StandardCopyOption.REPLACE_EXISTING);
        write(
            bin.resolve("catalina.bat"),
            "@echo off\r\n"
                + "set \"EXE=%~dp0tomcat9.exe\"\r\n"
                + "if \"%1\"==\"start\" (\r\n"
                + "  powershell -NoProfile -Command \"Start-Process -FilePath '%EXE%' -ArgumentList"
                + " '-n','900','127.0.0.1' -WindowStyle Hidden\"\r\n"
                + "  echo started\r\n"
                + "  exit /b 0\r\n"
                + ")\r\n"
                + "if \"%1\"==\"stop\" (\r\n"
                + "  powershell -NoProfile -Command \"Get-CimInstance Win32_Process -Filter"
                + " \\\"Name='tomcat9.exe'\\\" | Where-Object { $_.ExecutablePath -eq '%EXE%' } |"
                + " ForEach-Object { Stop-Process -Id $_.ProcessId -Force }\"\r\n"
                + "  echo stopped\r\n"
                + "  exit /b 0\r\n"
                + ")\r\n"
                + "exit /b 0\r\n");
      } else {
        write(
            bin.resolve("catalina.sh"),
            "#!/bin/sh\n"
                + "TC=\"$(cd \"$(dirname \"$0\")/..\" && pwd)\"\n"
                + "MARKER=\"-Dcatalina.base=$TC\"\n"
                + "case \"$1\" in\n"
                + "  start) nohup sh -c \"sleep 900 # $MARKER\" >/dev/null 2>&1 </dev/null & ;;\n"
                + "  stop) pkill -f -- \"$MARKER\" || true ;;\n"
                + "esac\n"
                + "exit 0\n");
      }
    }

    Path catalina() {
      return tomcat.resolve("bin").resolve(WINDOWS ? "catalina.bat" : "catalina.sh");
    }

    Cli.Result jrsctl(String... args) throws Exception {
      return cli.run(withGlobals(args));
    }

    Cli.Running start(String... args) throws IOException {
      return cli.start(withGlobals(args));
    }

    Cli.Running startExport(Path out) throws IOException {
      return start(exportArgs(out));
    }

    private String[] withGlobals(String... args) {
      String[] all = new String[args.length + 3];
      System.arraycopy(args, 0, all, 0, args.length);
      all[args.length] = "--home";
      all[args.length + 1] = home.toString();
      all[args.length + 2] = "--no-color";
      return all;
    }

    void release(int invocation) throws IOException {
      Files.writeString(ctrl.resolve("release." + invocation), "go", StandardCharsets.UTF_8);
    }

    void abortOrphans() {
      try {
        Files.writeString(ctrl.resolve("abort"), "abort", StandardCharsets.UTF_8);
      } catch (IOException e) {
        // the tree is gone already
      }
    }

    void waitForInvocation(int n, Cli.Running running) throws Exception {
      Path marker = ctrl.resolve("invoked." + n);
      Instant deadline = Instant.now().plus(STEP_TIMEOUT);
      while (!Files.exists(marker)) {
        if (!running.alive() || Instant.now().isAfter(deadline)) {
          String out = Files.exists(running.out()) ? Files.readString(running.out()) : "";
          String err = Files.exists(running.err()) ? Files.readString(running.err()) : "";
          throw new AssertionError(
              "js-export invocation "
                  + n
                  + " never started (jrsctl alive="
                  + running.alive()
                  + ")\nstdout:\n"
                  + out
                  + "\nstderr:\n"
                  + err);
        }
        Thread.sleep(250);
      }
    }

    /** The stand-in is visible to the OS exactly as the real service controller sees it. */
    boolean tomcatRunning() {
      if (WINDOWS) {
        String exe = tomcat.resolve("bin").resolve("tomcat9.exe").toString();
        return ProcessHandle.allProcesses()
            .map(ProcessHandle::info)
            .map(ProcessHandle.Info::command)
            .anyMatch(c -> c.map(exe::equalsIgnoreCase).orElse(false));
      }
      String marker = "-Dcatalina.base=" + tomcat.toAbsolutePath().normalize();
      return ProcessHandle.allProcesses()
          .map(ProcessHandle::info)
          .map(ProcessHandle.Info::commandLine)
          .anyMatch(c -> c.map(line -> line.contains(marker)).orElse(false));
    }

    void startTomcat() throws Exception {
      script("start");
      waitUntil(
          "stand-in Tomcat of " + dir.getFileName(), Duration.ofSeconds(60), this::tomcatRunning);
    }

    void stopTomcat() throws Exception {
      if (!Files.exists(catalina())) {
        return;
      }
      script("stop");
      if (WINDOWS) {
        Path exe = tomcat.resolve("bin").resolve("tomcat9.exe");
        for (int i = 0; i < 50 && Files.exists(exe); i++) {
          try {
            Files.delete(exe);
          } catch (IOException e) {
            Thread.sleep(200);
          }
        }
      }
    }

    private void script(String verb) throws Exception {
      Process p =
          new ProcessBuilder(List.of(catalina().toString(), verb))
              .redirectErrorStream(true)
              .start();
      p.getInputStream().readAllBytes();
      p.waitFor(30, TimeUnit.SECONDS);
    }
  }

  private static String unix(Path p) {
    return p.toString().replace("\\", "/");
  }

  private static void write(Path file, String content) throws IOException {
    Files.createDirectories(file.getParent());
    Files.writeString(file, content, StandardCharsets.UTF_8);
    if (!WINDOWS && file.toString().endsWith(".sh")) {
      Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxr-xr-x"));
    }
  }
}
