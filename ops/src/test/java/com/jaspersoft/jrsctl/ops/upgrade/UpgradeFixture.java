package com.jaspersoft.jrsctl.ops.upgrade;

import com.jaspersoft.jrsctl.core.engine.CancellationToken;
import com.jaspersoft.jrsctl.core.engine.Context;
import com.jaspersoft.jrsctl.core.engine.Plan;
import com.jaspersoft.jrsctl.core.engine.RunOptions;
import com.jaspersoft.jrsctl.core.engine.RunOutcome;
import com.jaspersoft.jrsctl.core.engine.Runner;
import com.jaspersoft.jrsctl.core.engine.Sleeper;
import com.jaspersoft.jrsctl.core.engine.Step;
import com.jaspersoft.jrsctl.core.event.Event;
import com.jaspersoft.jrsctl.core.platform.DefaultProcessRunner;
import com.jaspersoft.jrsctl.core.platform.Platform;
import com.jaspersoft.jrsctl.core.platform.Platforms;
import com.jaspersoft.jrsctl.core.snapshot.SnapshotStore;
import com.jaspersoft.jrsctl.core.state.StateStore;
import com.jaspersoft.jrsctl.jrs.api.KeystoreInfo;
import com.jaspersoft.jrsctl.jrs.vendor.VendorTools;
import com.jaspersoft.jrsctl.ops.FakePlatform;
import com.jaspersoft.jrsctl.ops.FakeServices;
import com.jaspersoft.jrsctl.ops.Services;
import com.jaspersoft.jrsctl.ops.hotfix.DefaultHotfixOperations;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * A fake JRS 8.2.0 installation, a fake 9.0.0 target package whose {@code js-ant} really runs
 * (through {@link DefaultProcessRunner}) and copies {@code webapp-new/} over the webapp, a fake
 * keystore, and a {@link FakeServices} whose scripted process runner answers the vendor Java probe.
 * The host OS decides between {@code .bat} and {@code .sh} scripts; both are written.
 */
public final class UpgradeFixture implements AutoCloseable {

  public static final String OLD_VERSION = "8.2.0";
  public static final String NEW_VERSION = "9.0.0";
  public static final String OLD_SCRIPT = "console.log('old');\n";
  public static final String NEW_SCRIPT = "console.log('new');\n";

  /** Marker file in the package: the fake js-ant copies the new webapp, then exits 3. */
  static final String FAIL_AFTER_COPY = "fail-after-copy";

  public final FakeServices fake;
  public final Services services;
  public final Path root;
  public final Path installDir;
  public final Path tomcatDir;
  public final Path webappDir;
  public final Path packageDir;
  public final Path javaHome;
  public final Path keystoreDir;
  public final Path vendorLog;
  public final List<Event> events = new ArrayList<>();
  public final Platform.OsFamily os;
  public String javaBanner = "openjdk version \"17.0.2\" 2022-01-18";

  private UpgradeFixture(Path root) throws IOException {
    this.root = root;
    this.os = Platforms.osFamily(System.getProperty("os.name", "")).orElseThrow();
    this.installDir = Files.createDirectories(root.resolve("jrs"));
    this.tomcatDir = installDir.resolve("apache-tomcat");
    this.webappDir = tomcatDir.resolve("webapps").resolve("jasperserver-pro");
    this.packageDir = Files.createDirectories(root.resolve("pkg-9.0.0"));
    this.javaHome = Files.createDirectories(root.resolve("jdk17"));
    this.keystoreDir = Files.createDirectories(root.resolve("jrs-home"));
    this.vendorLog = packageDir.resolve("js-ant.log");
    layout();
    targetPackage();
    Files.createDirectories(javaHome.resolve("bin"));
    write(javaHome.resolve("bin").resolve("java"), "");
    write(javaHome.resolve("bin").resolve("java.exe"), "");
    write(keystoreDir.resolve(".jrsks"), "keystore-bytes");
    write(keystoreDir.resolve(".jrsksp"), "keystore-properties");
    this.fake = FakeServices.in(root.resolve("home"), os);
    fake.platform.realFiles = true;
    for (String exe : List.of("java", "java.exe")) {
      fake.platform.on(
          List.of(javaHome.resolve("bin").resolve(exe).toString(), "-version"),
          new FakePlatform.Response(0, List.of(javaBanner)));
    }
    fake.adapter.keystore =
        new KeystoreInfo(
            true,
            Optional.of(keystoreDir.resolve(".jrsks")),
            Optional.of(keystoreDir.resolve(".jrsksp")),
            Optional.of("abc"),
            Optional.empty());
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
          stopTimeoutSeconds: 30
        vendor:
          javaHome: %s
        network:
          mode: public
        """
            .formatted(slashes(installDir), slashes(tomcatDir), slashes(javaHome)));
    this.services = fake.build();
  }

  public static UpgradeFixture create(Path root) throws IOException {
    return new UpgradeFixture(root);
  }

  /**
   * Makes the fake vendor upgrade fail part-way: it still copies the new webapp over the old one,
   * then exits 3, the shape of a migration that dies after touching the files.
   */
  public void failVendorScriptAfterCopy() throws IOException {
    write(packageDir.resolve(FAIL_AFTER_COPY), "");
  }

  /** Re-scripts the vendor Java probe, e.g. to simulate a JDK 11. */
  public void javaVersion(String banner) {
    for (String exe : List.of("java", "java.exe")) {
      fake.platform.on(
          List.of(javaHome.resolve("bin").resolve(exe).toString(), "-version"),
          new FakePlatform.Response(0, List.of(banner)));
    }
  }

  private void layout() throws IOException {
    Files.createDirectories(webappDir.resolve("WEB-INF").resolve("lib"));
    Files.createDirectories(webappDir.resolve("WEB-INF").resolve("classes"));
    Files.createDirectories(webappDir.resolve("scripts"));
    Files.createDirectories(webappDir.resolve("META-INF"));
    Files.createDirectories(tomcatDir.resolve("bin"));
    Files.createDirectories(tomcatDir.resolve("conf").resolve("Catalina").resolve("localhost"));
    write(webappDir.resolve("WEB-INF").resolve("lib").resolve("jasperserver-8.2.0.jar"), "old jar");
    write(webappDir.resolve("scripts").resolve("app.js"), OLD_SCRIPT);
    write(webappDir.resolve("version.txt"), OLD_VERSION);
    write(webappDir.resolve("META-INF").resolve("context.xml"), "<Context/>");
    write(
        tomcatDir.resolve("conf").resolve("server.xml"),
        "<Server><Service><Connector port=\"8080\" protocol=\"HTTP/1.1\"/></Service></Server>");
    write(
        tomcatDir
            .resolve("conf")
            .resolve("Catalina")
            .resolve("localhost")
            .resolve("jasperserver-pro.xml"),
        "<Context docBase=\"jasperserver-pro\"/>");
    Path buildomatic = Files.createDirectories(installDir.resolve("buildomatic"));
    write(
        buildomatic.resolve("default_master.properties"),
        "appServerType=tomcat\ndbType=postgresql\ndbHost=localhost\ndbPort=5432\ndbUsername=jasperdb\n"
            + "dbPassword=TopSecret\njs.dbName=jasperserver\n");
    exportScripts(buildomatic);
    write(buildomatic.resolve("js-import.bat"), "@echo off\r\nexit /b 0\r\n");
    write(buildomatic.resolve("js-import.sh"), "#!/bin/sh\nexit 0\n");
    write(buildomatic.resolve("js-ant.bat"), "@echo off\r\nexit /b 0\r\n");
    write(buildomatic.resolve("js-ant.sh"), "#!/bin/sh\nexit 0\n");
    write(installDir.resolve("ctlscript.sh"), "#!/bin/sh\n");
    write(installDir.resolve("ctlscript.bat"), "@echo off\r\n");
    executable(buildomatic);
  }

  private void exportScripts(Path buildomatic) throws IOException {
    write(
        buildomatic.resolve("js-export.bat"),
        "@echo off\r\necho fake export > \"%2\"\r\nexit /b 0\r\n");
    write(buildomatic.resolve("js-export.sh"), "#!/bin/sh\necho fake export > \"$2\"\nexit 0\n");
  }

  private void targetPackage() throws IOException {
    Path buildomatic = Files.createDirectories(packageDir.resolve("buildomatic"));
    Path webapp =
        Files.createDirectories(
            packageDir.resolve("jasperserver-pro").resolve("WEB-INF").resolve("lib"));
    write(webapp.resolve("some-lib.jar"), "lib");
    Path webappNew = Files.createDirectories(packageDir.resolve("webapp-new"));
    write(webappNew.resolve("version.txt"), NEW_VERSION);
    write(webappNew.resolve("scripts").resolve("app.js"), NEW_SCRIPT);
    write(webappNew.resolve("WEB-INF").resolve("lib").resolve("jasperserver-9.0.0.jar"), "new jar");
    String target = webappDir.toString();
    write(
        buildomatic.resolve("js-ant.bat"),
        "@echo off\r\n"
            + "echo js-ant fake target=%1 JAVA_HOME=%JAVA_HOME%\r\n"
            + "xcopy /E /Y /I /Q \"%~dp0..\\webapp-new\" \""
            + target
            + "\" >nul\r\n"
            + "if errorlevel 1 exit /b 1\r\n"
            + "if exist \"%~dp0..\\"
            + FAIL_AFTER_COPY
            + "\" (echo BUILD FAILED after copying the webapp & exit /b 3)\r\n"
            + "echo %1 >> \"%~dp0..\\js-ant.log\"\r\n"
            + "exit /b 0\r\n");
    write(
        buildomatic.resolve("js-ant.sh"),
        "#!/bin/sh\n"
            + "echo \"js-ant fake target=$1 JAVA_HOME=$JAVA_HOME\"\n"
            + "cp -R \"$(dirname \"$0\")/../webapp-new/.\" \""
            + target
            + "/\" || exit 1\n"
            + "if [ -f \"$(dirname \"$0\")/../"
            + FAIL_AFTER_COPY
            + "\" ]; then echo \"BUILD FAILED after copying the webapp\"; exit 3; fi\n"
            + "echo \"$1\" >> \"$(dirname \"$0\")/../js-ant.log\"\n"
            + "exit 0\n");
    exportScripts(buildomatic);
    write(buildomatic.resolve("js-import.bat"), "@echo off\r\nexit /b 0\r\n");
    write(buildomatic.resolve("js-import.sh"), "#!/bin/sh\nexit 0\n");
    executable(buildomatic);
  }

  private static void executable(Path dir) throws IOException {
    try (var files = Files.list(dir)) {
      for (Path f : files.toList()) {
        if (f.getFileName().toString().endsWith(".sh")) {
          try {
            Files.setPosixFilePermissions(f, PosixFilePermissions.fromString("rwxr-xr-x"));
          } catch (UnsupportedOperationException e) {
            // Windows
          }
        }
      }
    }
  }

  public DefaultUpgradeOperations ops() {
    return new DefaultUpgradeOperations(runtime());
  }

  public UpgradeRuntime runtime() {
    return new UpgradeRuntime(
        services,
        snapshots(),
        s ->
            new VendorTools(
                new DefaultProcessRunner(),
                s.platform().files(),
                s.redactor(),
                Duration.ofMinutes(2)),
        DefaultHotfixOperations::new,
        Sleeper.none());
  }

  public SnapshotStore snapshots() {
    return new SnapshotStore(fake.home, services.platform().files(), fake.clock);
  }

  public StateStore store() {
    return fake.stateStore();
  }

  public Context ctx(String runId) {
    return new Context(
        runId, fake.home, services.platform(), new CancellationToken(), java.util.Map.of());
  }

  public RunOutcome run(Plan plan, String runId, RunOptions options) {
    Runner runner = new Runner(store(), events::add, fake.clock, Sleeper.none());
    return runner.run(plan, ctx(runId), plan.fingerprint(), options);
  }

  public String sha(Path file) throws IOException {
    return services.platform().files().sha256(file);
  }

  public List<String> logs() {
    List<String> out = new ArrayList<>();
    for (Event e : events) {
      if (e instanceof Event.Log l) {
        out.add(l.message());
      }
    }
    return out;
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

  public static String read(Path file) throws IOException {
    return Files.readString(file, StandardCharsets.UTF_8);
  }

  static String slashes(Path path) {
    return path.toAbsolutePath().toString().replace('\\', '/');
  }

  @Override
  public void close() {
    fake.close();
  }
}
