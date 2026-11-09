package com.jaspersoft.jrsctl.ops.repository;

import com.jaspersoft.jrsctl.core.JrsctlHome;
import com.jaspersoft.jrsctl.core.engine.CancellationToken;
import com.jaspersoft.jrsctl.core.engine.Context;
import com.jaspersoft.jrsctl.core.engine.Plan;
import com.jaspersoft.jrsctl.core.engine.RunOptions;
import com.jaspersoft.jrsctl.core.engine.RunOutcome;
import com.jaspersoft.jrsctl.core.engine.Runner;
import com.jaspersoft.jrsctl.core.engine.Sleeper;
import com.jaspersoft.jrsctl.core.event.Event;
import com.jaspersoft.jrsctl.core.platform.DefaultProcessRunner;
import com.jaspersoft.jrsctl.core.platform.Platform;
import com.jaspersoft.jrsctl.core.platform.Platforms;
import com.jaspersoft.jrsctl.core.state.StateStore;
import com.jaspersoft.jrsctl.jrs.vendor.VendorTools;
import com.jaspersoft.jrsctl.ops.FakePlatform;
import com.jaspersoft.jrsctl.ops.FakeServices;
import com.jaspersoft.jrsctl.ops.Services;
import com.jaspersoft.jrsctl.ops.db.FakeJdbcConnector;
import com.jaspersoft.jrsctl.ops.repository.RepositoryOperations.LoadOptions;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A tools-only configuration (ADR-0052): a fake 10.1 buildomatic whose {@code js-ant} and {@code
 * js-import} really run through {@link DefaultProcessRunner} and log their arguments, a {@link
 * FakeJdbcConnector} that answers the catalogue from a marker file the fake {@code create-js-db}
 * writes (and a {@code DROP DATABASE} removes), an export archive, and a {@link FakeServices} whose
 * server is reachable unless a test says otherwise. The host OS decides between {@code .bat} and
 * {@code .sh}; both are written.
 */
public final class LoadFixture implements AutoCloseable {

  public static final String SCHEMA = "jrs_101";
  public static final String MASTER =
      """
      appServerType=skipAppServerCheck
      dbType=postgresql
      dbHost=db.example.internal
      dbPort=5432
      dbUsername=postgres
      dbPassword=TopSecret
      js.dbName=jrs_101
      """;

  public final FakeServices fake;
  public Services services;
  public final Path root;
  public final Path buildomaticDir;
  public final Path javaHome;
  public final Path vendorLog;
  public final Path export;
  public final Path createdMarker;
  public final FakeJdbcConnector jdbc = new FakeJdbcConnector();
  public final List<Event> events = new ArrayList<>();
  public final Platform.OsFamily os;

  private LoadFixture(Path root, String yamlDatabaseUrl, String master) throws IOException {
    this.root = root;
    this.os = Platforms.osFamily(System.getProperty("os.name", "")).orElseThrow();
    this.buildomaticDir = Files.createDirectories(root.resolve("jrs-10.1").resolve("buildomatic"));
    this.javaHome = Files.createDirectories(root.resolve("jdk17"));
    this.vendorLog = root.resolve("vendor.log");
    this.createdMarker = root.resolve("schema-exists");
    Files.createDirectories(javaHome.resolve("bin"));
    write(javaHome.resolve("bin").resolve("java"), "");
    write(javaHome.resolve("bin").resolve("java.exe"), "");
    buildomatic(master);
    this.export = fakeExport("js-8.2-export.zip");
    Path drivers = Files.createDirectories(root.resolve("drivers"));
    this.fake = FakeServices.in(root.resolve("home"), os);
    fake.platform.realFiles = true;
    fake.env.put("JRS_DB_PASSWORD", "db-pw-for-tests");
    for (String exe : List.of("java", "java.exe")) {
      fake.platform.on(
          List.of(javaHome.resolve("bin").resolve(exe).toString(), "-version"),
          new FakePlatform.Response(0, List.of("openjdk version \"17.0.2\" 2022-01-18")));
    }
    fake.yaml(
        """
        server:
          baseUrl: http://localhost:8080/jasperserver-pro
          webappName: jasperserver-pro
          buildomaticDir: %s
          auth:
            mode: basic
            username: superuser
            passwordRef: env:JRS_PASSWORD
        vendor:
          javaHome: %s
        network:
          mode: public
        database:
          type: postgresql
          url: %s
          username: postgres
          passwordRef: env:JRS_DB_PASSWORD
          driverDir: %s
        """
            .formatted(
                slashes(buildomaticDir), slashes(javaHome), yamlDatabaseUrl, slashes(drivers)));
    this.services = fake.build();
    jdbc.rowAnswer = sql -> !sql.contains("pg_database") || Files.exists(createdMarker);
    jdbc.onExecute =
        sql -> {
          if (sql.startsWith("DROP DATABASE")) {
            try {
              Files.deleteIfExists(createdMarker);
            } catch (IOException e) {
              throw new UncheckedIOException(e);
            }
          }
        };
  }

  /** The standard shape: config.yaml agrees with default_master.properties. */
  public static LoadFixture create(Path root) throws IOException {
    return new LoadFixture(root, "jdbc:postgresql://db.example.internal:5432/" + SCHEMA, MASTER);
  }

  /** As {@link #create}, with config.yaml's database.url pointing at {@code otherDatabase}. */
  public static LoadFixture createWithConfiguredUrl(Path root, String otherDatabase)
      throws IOException {
    return new LoadFixture(
        root, "jdbc:postgresql://db.example.internal:5432/" + otherDatabase, MASTER);
  }

  /** As {@link #create}, with another {@code default_master.properties}. */
  public static LoadFixture createWithMaster(Path root, String master) throws IOException {
    return new LoadFixture(root, "jdbc:postgresql://db.example.internal:5432/" + SCHEMA, master);
  }

  public LoadOptions options() {
    return new LoadOptions(SCHEMA, export, false, Optional.empty());
  }

  /** Makes the fake {@code js-ant <target>} report a failed build. */
  public void failTarget(String target) throws IOException {
    write(buildomaticDir.resolve("fail-" + target), "");
  }

  /** Makes the fake {@code js-ant gen-config} render {@code url} as {@code metadata.jdbc.url}. */
  public void renderUrl(String url) throws IOException {
    write(buildomaticDir.resolve("rendered-url"), url);
  }

  /** Makes the fake {@code js-ant gen-config} write nothing at all. */
  public void renderNothing() throws IOException {
    write(buildomaticDir.resolve("render-nothing"), "");
  }

  /** Where the vendor's {@code gen-config} renders the configuration. */
  public Path renderedDir() {
    return buildomaticDir.resolve("build_conf").resolve("default");
  }

  /** Makes the fake {@code js-import} stop before printing Done. */
  public void failImport() throws IOException {
    write(buildomaticDir.resolve("fail-js-import"), "");
  }

  public void master(String content) throws IOException {
    write(buildomaticDir.resolve("default_master.properties"), content);
  }

  public boolean schemaExists() {
    return Files.exists(createdMarker);
  }

  public void schemaExists(boolean exists) throws IOException {
    if (exists) {
      write(createdMarker, "");
    } else {
      Files.deleteIfExists(createdMarker);
    }
  }

  public String vendorLogText() throws IOException {
    return Files.exists(vendorLog) ? Files.readString(vendorLog) : "";
  }

  public DefaultRepositoryOperations ops() {
    return new DefaultRepositoryOperations(runtime());
  }

  public LoadRuntime runtime() {
    return new LoadRuntime(
        services,
        s ->
            new VendorTools(
                new DefaultProcessRunner(),
                s.platform().files(),
                s.redactor(),
                Duration.ofMinutes(2)),
        jdbc);
  }

  public StateStore store() {
    return fake.stateStore();
  }

  public JrsctlHome home() {
    return fake.home;
  }

  public Context ctx(String runId) {
    return new Context(runId, fake.home, services.platform(), new CancellationToken(), Map.of());
  }

  public RunOutcome run(Plan plan, String runId) {
    Runner runner = new Runner(store(), events::add, fake.clock, Sleeper.none());
    return runner.run(plan, ctx(runId), plan.fingerprint(), RunOptions.DEFAULT);
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

  /** A JasperReports Server export archive: index.xml naming the version, plus a resource. */
  public Path fakeExport(String name) throws IOException {
    Path zip = Files.createDirectories(root.resolve("exports")).resolve(name);
    try (java.io.OutputStream out = Files.newOutputStream(zip);
        java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(out)) {
      zos.putNextEntry(new java.util.zip.ZipEntry("index.xml"));
      zos.write(
          ("<export>"
                  + "<property name=\"jsVersion\" value=\"8.2.0 PRO\"/>"
                  + "<module id=\"tenants\"/><module id=\"authority\"/>"
                  + "<module id=\"repositoryResources\"/><module id=\"reportJobs\"/>"
                  + "</export>")
              .getBytes(StandardCharsets.UTF_8));
      zos.closeEntry();
      zos.putNextEntry(new java.util.zip.ZipEntry("resources/public/x.xml"));
      zos.write("<x/>".getBytes(StandardCharsets.UTF_8));
      zos.closeEntry();
    }
    return zip;
  }

  private void buildomatic(String master) throws IOException {
    write(buildomaticDir.resolve("default_master.properties"), master);
    Files.createDirectories(buildomaticDir.resolve("conf_source").resolve("iePro"));
    Files.createDirectories(
        buildomaticDir.resolve("conf_source").resolve("db").resolve("postgresql").resolve("jdbc"));
    Path antBin =
        Files.createDirectories(buildomaticDir.getParent().resolve("apache-ant").resolve("bin"));
    write(antBin.resolve("ant.bat"), "@echo off\r\n");
    write(antBin.resolve("ant"), "#!/bin/sh\n");
    String log = vendorLog.toString();
    String created = createdMarker.toString();
    write(
        buildomaticDir.resolve("js-ant.bat"),
        "@echo off\r\n"
            + "echo js-ant fake target=%1 JAVA_HOME=%JAVA_HOME%\r\n"
            + "echo js-ant %* >> \""
            + log
            + "\"\r\n"
            + "if exist \"%~dp0fail-%1\" (echo BUILD FAILED & exit /b 1)\r\n"
            + "if \"%1\"==\"create-js-db\" echo created > \""
            + created
            + "\"\r\n"
            + "if \"%1\"==\"clean-config\" if exist \"%~dp0build_conf\\default\" rmdir /s /q"
            + " \"%~dp0build_conf\\default\"\r\n"
            + "if not \"%1\"==\"gen-config\" goto :done\r\n"
            + "if exist \"%~dp0render-nothing\" goto :done\r\n"
            + "if not exist \"%~dp0build_conf\\default\" mkdir \"%~dp0build_conf\\default\"\r\n"
            + "set \"URL=jdbc:postgresql://db.example.internal:5432/jrs_101\"\r\n"
            + "if exist \"%~dp0rendered-url\" set /p URL=<\"%~dp0rendered-url\"\r\n"
            + "echo metadata.jdbc.url=%URL%> \"%~dp0build_conf\\default\\js.jdbc.properties\"\r\n"
            + "echo metadata.jdbc.username=postgres>>"
            + " \"%~dp0build_conf\\default\\js.jdbc.properties\"\r\n"
            + "echo metadata.jdbc.password=TopSecret>>"
            + " \"%~dp0build_conf\\default\\js.jdbc.properties\"\r\n"
            + "echo org.quartz.scheduler.instanceName=JasperServerScheduler>"
            + " \"%~dp0build_conf\\default\\js.quartz.properties\"\r\n"
            + ":done\r\n"
            + "echo BUILD SUCCESSFUL\r\n"
            + "exit /b 0\r\n");
    write(
        buildomaticDir.resolve("js-ant.sh"),
        "#!/bin/sh\n"
            + "echo \"js-ant fake target=$1 JAVA_HOME=$JAVA_HOME\"\n"
            + "echo \"js-ant $@\" >> \""
            + log
            + "\"\n"
            + "if [ -f \"$(dirname \"$0\")/fail-$1\" ]; then echo BUILD FAILED; exit 1; fi\n"
            + "if [ \"$1\" = \"create-js-db\" ]; then echo created > \""
            + created
            + "\"; fi\n"
            + "here=\"$(dirname \"$0\")\"\n"
            + "if [ \"$1\" = \"clean-config\" ]; then rm -rf \"$here/build_conf/default\"; fi\n"
            + "if [ \"$1\" = \"gen-config\" ] && [ ! -f \"$here/render-nothing\" ]; then\n"
            + "  d=\"$here/build_conf/default\"; mkdir -p \"$d\"\n"
            + "  url=\"jdbc:postgresql://db.example.internal:5432/jrs_101\"\n"
            + "  if [ -f \"$here/rendered-url\" ]; then url=\"$(cat \"$here/rendered-url\")\"; fi\n"
            + "  printf 'metadata.jdbc.url=%s\\nmetadata.jdbc.username=postgres\\n' \"$url\" >"
            + " \"$d/js.jdbc.properties\"\n"
            + "  echo metadata.jdbc.password=TopSecret >> \"$d/js.jdbc.properties\"\n"
            + "  echo org.quartz.scheduler.instanceName=JasperServerScheduler >"
            + " \"$d/js.quartz.properties\"\n"
            + "fi\n"
            + "echo BUILD SUCCESSFUL\n"
            + "exit 0\n");
    write(
        buildomaticDir.resolve("js-import.bat"),
        "@echo off\r\n"
            + "echo js-import %* >> \""
            + log
            + "\"\r\n"
            + "echo Processing started\r\n"
            + "if exist \"%~dp0fail-js-import\" (echo ERROR BaseExportImportCommand boom & exit /b 1)\r\n"
            + "echo VALIDATION COMPLETED\r\n"
            + "echo Done\r\n"
            + "exit /b 0\r\n");
    write(
        buildomaticDir.resolve("js-import.sh"),
        "#!/bin/sh\n"
            + "echo \"js-import $@\" >> \""
            + log
            + "\"\n"
            + "echo Processing started\n"
            + "if [ -f \"$(dirname \"$0\")/fail-js-import\" ]; then echo \"ERROR"
            + " BaseExportImportCommand boom\"; exit 1; fi\n"
            + "echo VALIDATION COMPLETED\n"
            + "echo Done\n"
            + "exit 0\n");
    write(buildomaticDir.resolve("js-export.bat"), "@echo off\r\nexit /b 0\r\n");
    write(buildomaticDir.resolve("js-export.sh"), "#!/bin/sh\nexit 0\n");
    executable(buildomaticDir);
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

  private static void write(Path file, String content) throws IOException {
    Files.createDirectories(file.getParent());
    Files.writeString(file, content, StandardCharsets.UTF_8);
  }

  private static String slashes(Path p) {
    return p.toString().replace('\\', '/');
  }

  @Override
  public void close() {
    fake.close();
  }
}
