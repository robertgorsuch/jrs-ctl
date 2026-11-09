package com.jaspersoft.jrsctl.ops.passwords;

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
import com.jaspersoft.jrsctl.ops.FakeJrsAdapter;
import com.jaspersoft.jrsctl.ops.FakeLayout;
import com.jaspersoft.jrsctl.ops.FakePlatform;
import com.jaspersoft.jrsctl.ops.FakeServices;
import com.jaspersoft.jrsctl.ops.Services;
import com.jaspersoft.jrsctl.ops.passwords.PasswordOperations.MigrateOptions;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A fake 10.1 installation (the Linux bundle layout of {@link FakeLayout}) whose buildomatic's
 * {@code js-ant} really runs through {@link DefaultProcessRunner}: it logs its arguments, prints
 * the counts the vendor's password utility prints, and keeps the migration's effect in a marker
 * file so {@code detect-password-strategy} answers differently before and after {@code
 * migrate-passwords}. The host OS decides between {@code .bat} and {@code .sh}; both are written.
 */
public final class PasswordFixture implements AutoCloseable {

  public static final String VERSION = "10.1.0";

  public final FakeServices fake;
  public Services services;
  public final Path root;
  public final Path installDir;
  public final Path webappDir;
  public final Path buildomaticDir;
  public final Path javaHome;
  public final Path vendorLog;
  public final Path migratedMarker;
  public final Path cleanedMarker;
  public final List<Event> events = new ArrayList<>();
  public final Platform.OsFamily os;

  private PasswordFixture(Path root, String version, String storageConfig) throws IOException {
    this.root = root;
    this.os = Platforms.osFamily(System.getProperty("os.name", "")).orElseThrow();
    this.installDir = FakeLayout.linux(root.resolve("jrs"));
    this.webappDir =
        installDir.resolve("apache-tomcat").resolve("webapps").resolve("jasperserver-pro");
    this.buildomaticDir = installDir.resolve("buildomatic");
    this.javaHome = installDir.resolve("java");
    this.vendorLog = root.resolve("vendor.log");
    this.migratedMarker = root.resolve("migrated");
    this.cleanedMarker = root.resolve("cleaned");
    write(
        webappDir.resolve("WEB-INF").resolve("lib").resolve("jasperserver-" + version + ".jar"),
        "jar");
    if (storageConfig != null) {
      write(webappDir.resolve("WEB-INF").resolve(PasswordInput.STORAGE_CONFIG), storageConfig);
    }
    write(javaHome.resolve("bin").resolve("java.exe"), "");
    antScripts();
    this.fake = FakeServices.in(root.resolve("home"), os);
    fake.platform.realFiles = true;
    fake.adapter.identity = FakeJrsAdapter.identity(version);
    for (String exe : List.of("java", "java.exe")) {
      fake.platform.on(
          List.of(javaHome.resolve("bin").resolve(exe).toString(), "-version"),
          new FakePlatform.Response(0, List.of("openjdk version \"17.0.2\" 2022-01-18")));
    }
    fake.yaml(
        """
        server:
          baseUrl: http://localhost:8081/jasperserver-pro
          webappName: jasperserver-pro
          installDir: %s
          runAsUser: jasperserver
          auth:
            mode: basic
            username: superuser
            passwordRef: env:JRS_PASSWORD
        service:
          kind: systemd
          name: jasperreports
        vendor:
          javaHome: %s
        network:
          mode: public
        """
            .formatted(slashes(installDir), slashes(javaHome)));
    this.services = fake.build();
  }

  /** A 10.1 server configured for modern password storage, nothing migrated yet. */
  public static PasswordFixture create(Path root) throws IOException {
    return new PasswordFixture(root, VERSION, "password.strategy=modern\n");
  }

  /** As {@link #create}, with the webapp's jars naming {@code version}. */
  public static PasswordFixture createWithVersion(Path root, String version) throws IOException {
    return new PasswordFixture(root, version, "password.strategy=modern\n");
  }

  /** As {@link #create}, with the storage settings file absent or set to {@code strategy}. */
  public static PasswordFixture createWithStrategy(Path root, String strategy) throws IOException {
    return new PasswordFixture(
        root, VERSION, strategy == null ? null : "password.strategy=" + strategy + "\n");
  }

  public MigrateOptions options() {
    return new MigrateOptions(true, false);
  }

  /** Makes the fake {@code js-ant <target>} report a failed build. */
  public void failTarget(String target) throws IOException {
    write(buildomaticDir.resolve("fail-" + target), "");
  }

  public boolean migrated() {
    return Files.exists(migratedMarker);
  }

  public boolean cleaned() {
    return Files.exists(cleanedMarker);
  }

  public String vendorLogText() throws IOException {
    return Files.exists(vendorLog) ? Files.readString(vendorLog) : "";
  }

  public DefaultPasswordOperations ops() {
    return new DefaultPasswordOperations(runtime());
  }

  public PasswordRuntime runtime() {
    return new PasswordRuntime(
        services,
        s ->
            new VendorTools(
                new DefaultProcessRunner(),
                s.platform().files(),
                s.redactor(),
                Duration.ofMinutes(2)));
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

  private void antScripts() throws IOException {
    String log = vendorLog.toString();
    String migrated = migratedMarker.toString();
    String cleaned = cleanedMarker.toString();
    write(
        buildomaticDir.resolve("js-ant.bat"),
        "@echo off\r\n"
            + "echo js-ant %* >> \""
            + log
            + "\"\r\n"
            + "if exist \"%~dp0fail-%1\" (echo BUILD FAILED & exit /b 1)\r\n"
            + "if \"%1\"==\"detect-password-strategy\" (\r\n"
            + "  echo Total users: 6\r\n"
            + "  if exist \""
            + migrated
            + "\" (echo Modern passwords: 5 & echo Legacy passwords: 1) else (echo Modern"
            + " passwords: 0 & echo Legacy passwords: 6)\r\n"
            + ")\r\n"
            + "if \"%1\"==\"migrate-passwords-dry-run\" (echo Migration statistics: & echo - Total"
            + " users: 6 & echo - Users needing migration: 6 & echo - Users already modern: 0 &"
            + " echo - Dry run: true)\r\n"
            + "if \"%1\"==\"migrate-passwords\" (echo migrated > \""
            + migrated
            + "\" & echo Migration summary: & echo - Successfully migrated: 5 & echo - Skipped: 1"
            + " & echo - Failed: 0 & echo Final migration statistics: & echo - Total users: 6 &"
            + " echo - Modern passwords: 5 & echo - Legacy passwords: 1 & echo Migration completed"
            + " successfully!)\r\n"
            + "if \"%1\"==\"cleanup-password-migration\" (echo cleaned > \""
            + cleaned
            + "\" & echo Removed 5 completed records)\r\n"
            + "echo BUILD SUCCESSFUL\r\n"
            + "exit /b 0\r\n");
    write(
        buildomaticDir.resolve("js-ant.sh"),
        "#!/bin/sh\n"
            + "echo \"js-ant $@\" >> \""
            + log
            + "\"\n"
            + "if [ -f \"$(dirname \"$0\")/fail-$1\" ]; then echo BUILD FAILED; exit 1; fi\n"
            + "case \"$1\" in\n"
            + "  detect-password-strategy)\n"
            + "    echo \"Total users: 6\"\n"
            + "    if [ -f \""
            + migrated
            + "\" ]; then echo \"Modern passwords: 5\"; echo \"Legacy passwords: 1\";\n"
            + "    else echo \"Modern passwords: 0\"; echo \"Legacy passwords: 6\"; fi ;;\n"
            + "  migrate-passwords-dry-run)\n"
            + "    echo \"Migration statistics:\"; echo \"- Total users: 6\"\n"
            + "    echo \"- Users needing migration: 6\"; echo \"- Users already modern: 0\"\n"
            + "    echo \"- Dry run: true\" ;;\n"
            + "  migrate-passwords)\n"
            + "    echo migrated > \""
            + migrated
            + "\"\n"
            + "    echo \"Migration summary:\"; echo \"- Successfully migrated: 5\"\n"
            + "    echo \"- Skipped: 1\"; echo \"- Failed: 0\"\n"
            + "    echo \"Final migration statistics:\"; echo \"- Total users: 6\"\n"
            + "    echo \"- Modern passwords: 5\"; echo \"- Legacy passwords: 1\"\n"
            + "    echo \"Migration completed successfully!\" ;;\n"
            + "  cleanup-password-migration)\n"
            + "    echo cleaned > \""
            + cleaned
            + "\"; echo \"Removed 5 completed records\" ;;\n"
            + "esac\n"
            + "echo BUILD SUCCESSFUL\n"
            + "exit 0\n");
    // the doctor's vendor item wants all three scripts in the host's extension
    for (String script : List.of("js-export", "js-import")) {
      write(buildomaticDir.resolve(script + ".bat"), "@echo off\r\nexit /b 0\r\n");
      write(buildomaticDir.resolve(script + ".sh"), "#!/bin/sh\nexit 0\n");
    }
    for (String script : List.of("js-ant.sh", "js-export.sh", "js-import.sh")) {
      try {
        Files.setPosixFilePermissions(
            buildomaticDir.resolve(script), PosixFilePermissions.fromString("rwxr-xr-x"));
      } catch (UnsupportedOperationException e) {
        // Windows
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
