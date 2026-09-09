package com.jaspersoft.jrsctl.jrs.vendor;

import static org.assertj.core.api.Assertions.assertThat;

import com.jaspersoft.jrsctl.core.event.Event;
import com.jaspersoft.jrsctl.core.platform.Platform;
import com.jaspersoft.jrsctl.core.platform.ProcessRunner;
import com.jaspersoft.jrsctl.core.redact.Redactor;
import com.jaspersoft.jrsctl.core.secrets.Secret;
import com.jaspersoft.jrsctl.jrs.CapturingRunner;
import com.jaspersoft.jrsctl.jrs.FakePlatform;
import com.jaspersoft.jrsctl.jrs.RecordingSink;
import com.jaspersoft.jrsctl.jrs.api.ExportRequest;
import com.jaspersoft.jrsctl.jrs.api.ImportRequest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VendorToolsTest {

  @TempDir Path tmp;

  private final CapturingRunner runner = new CapturingRunner();
  private final Redactor redactor = new Redactor();
  private final RecordingSink sink = new RecordingSink();
  private final VendorTools.LogScope scope =
      new VendorTools.LogScope("run-1", Optional.of("export.js-export"), "export");
  private Platform platform;
  private Buildomatic buildomatic;
  private Path javaHome;

  @BeforeEach
  void setUp() throws IOException {
    platform = new FakePlatform(Platform.OsFamily.LINUX, runner);
    Path dir = tmp.resolve("install").resolve("buildomatic");
    Files.createDirectories(dir);
    Files.writeString(dir.resolve("js-export.sh"), "#!/bin/sh\n");
    Files.writeString(dir.resolve("js-import.sh"), "#!/bin/sh\n");
    Files.writeString(dir.resolve("js-ant.sh"), "#!/bin/sh\n");
    buildomatic = new BuildomaticLocator(platform).locate(tmp.resolve("install")).orElseThrow();
    javaHome = tmp.resolve("jdk");
    Files.createDirectories(javaHome);
  }

  private VendorTools tools() {
    return new VendorTools(runner, platform.files(), redactor);
  }

  private static ExportRequest export(
      ExportRequest.Scope scope,
      Set<String> uris,
      boolean usersRoles,
      boolean access,
      boolean audit,
      boolean monitoring,
      boolean settings,
      boolean fullServer,
      Path out) {
    return new ExportRequest(
        scope, uris, usersRoles, access, audit, monitoring, settings, fullServer, out);
  }

  @Test
  void should_build_everything_export_args_when_full_server() {
    Path out = Path.of("full.zip");
    ExportRequest r =
        export(ExportRequest.Scope.REPOSITORY, Set.of(), true, true, true, true, true, true, out);

    assertThat(VendorTools.exportArgs(r, out))
        .containsExactly(
            "--output-zip",
            "full.zip",
            "--everything",
            "--users",
            "--roles",
            "--include-access-events",
            "--include-audit-events",
            "--include-monitoring-events",
            "--include-server-settings");
  }

  @Test
  void should_build_uri_export_args_when_repository_scope() {
    Path out = Path.of("part.zip");
    ExportRequest r =
        export(
            ExportRequest.Scope.REPOSITORY,
            Set.of("/public", "/organizations"),
            false,
            false,
            false,
            false,
            false,
            false,
            out);

    assertThat(VendorTools.exportArgs(r, out))
        .containsExactly(
            "--output-zip",
            "part.zip",
            "--uris",
            "/organizations,/public",
            "--repository-permissions");
  }

  @Test
  void should_export_root_when_no_uris_given() {
    Path out = Path.of("root.zip");
    ExportRequest r =
        export(
            ExportRequest.Scope.REPOSITORY,
            Set.of(),
            false,
            false,
            false,
            false,
            false,
            false,
            out);

    assertThat(VendorTools.exportArgs(r, out)).containsSequence("--uris", "/");
  }

  @Test
  void should_build_all_import_args_when_every_flag_set() {
    ImportRequest r =
        new ImportRequest(
            Path.of("in.zip"),
            true,
            true,
            true,
            true,
            true,
            true,
            true,
            Optional.empty(),
            Optional.empty());

    assertThat(VendorTools.importArgs(r))
        .containsExactly(
            "--input-zip",
            "in.zip",
            "--update",
            "--skip-user-update",
            "--include-access-events",
            "--include-audit-events",
            "--include-monitoring-events",
            "--include-server-settings",
            "--skip-themes");
  }

  @Test
  void should_build_minimal_import_args_when_no_flag_set() {
    ImportRequest r =
        new ImportRequest(
            Path.of("in.zip"),
            false,
            false,
            false,
            false,
            false,
            false,
            false,
            Optional.empty(),
            Optional.empty());

    assertThat(VendorTools.importArgs(r)).containsExactly("--input-zip", "in.zip");
  }

  @Test
  void should_append_keystore_args_when_source_keystore_and_password_present() {
    ImportRequest r =
        new ImportRequest(
            Path.of("in.zip"),
            false,
            false,
            false,
            false,
            false,
            false,
            false,
            Optional.of(Path.of("src.jrsks")),
            Optional.empty());
    try (Secret pw = Secret.fromString("st0re-pass")) {
      assertThat(VendorTools.importArgs(r, Optional.of(pw)))
          .containsExactly(
              "--input-zip", "in.zip", "--keystore", "src.jrsks", "--storepass", "st0re-pass");
    }
    assertThat(VendorTools.keystoreArgs(Path.of("k.jrsks"), Optional.empty()))
        .containsExactly("--keystore", "k.jrsks");
  }

  @Test
  void should_pass_java_home_and_buildomatic_working_dir_when_running_export() {
    runner.exit(0, "Export finished");
    Path out = tmp.resolve("x.zip");
    ExportRequest r =
        export(
            ExportRequest.Scope.REPOSITORY,
            Set.of("/a"),
            false,
            false,
            false,
            false,
            false,
            false,
            out);

    VendorRun run = tools().export(buildomatic, r, out, Optional.of(javaHome), sink, scope);

    assertThat(run).isInstanceOf(VendorRun.Completed.class);
    assertThat(((VendorRun.Completed) run).ok()).isTrue();
    ProcessRunner.Request req = runner.last();
    assertThat(req.command().get(0))
        .isEqualTo(buildomatic.dir().resolve("js-export.sh").toString());
    assertThat(req.command().subList(1, req.command().size()))
        .isEqualTo(VendorTools.exportArgs(r, out));
    assertThat(req.workingDir()).contains(buildomatic.dir());
    assertThat(req.environment()).containsEntry("JAVA_HOME", javaHome.toString());
    assertThat(req.timeout()).isEqualTo(VendorTools.DEFAULT_TIMEOUT);
    assertThat(sink.logMessages()).contains("Export finished");
    assertThat(sink.of(Event.Log.class))
        .allSatisfy(
            l -> {
              assertThat(l.runId()).isEqualTo("run-1");
              assertThat(l.stepId()).contains("export.js-export");
              assertThat(l.phase()).isEqualTo("export");
            });
  }

  @Test
  void should_redact_streamed_lines_when_they_contain_registered_secret() {
    redactor.register("s3cretValue");
    runner.answer(
        (request, onLine) -> {
          onLine.accept(
              new ProcessRunner.OutputLine(
                  ProcessRunner.OutputLine.Stream.STDERR, "dbPassword=s3cretValue failed"));
          return new ProcessRunner.Result(1, false, Duration.ofMillis(3));
        });

    VendorRun run =
        tools()
            .ant(buildomatic, "import-minimal-ce", List.of(), Optional.of(javaHome), sink, scope);

    assertThat(run).isInstanceOf(VendorRun.Completed.class);
    VendorRun.Completed c = (VendorRun.Completed) run;
    assertThat(c.ok()).isFalse();
    assertThat(c.exitCode()).isEqualTo(1);
    assertThat(c.tail()).hasSize(1);
    assertThat(String.join("\n", sink.logMessages()) + String.join("\n", c.tail()))
        .doesNotContain("s3cretValue")
        .contains(Redactor.MASK);
    assertThat(sink.of(Event.Log.class).get(1).level()).isEqualTo(Event.Log.Level.WARN);
  }

  @Test
  void should_mask_storepass_on_logged_command_line_when_importing_keystore() {
    runner.exit(0);
    try (Secret pw = Secret.fromString("ks-pass-123")) {
      tools()
          .importKeystore(
              buildomatic,
              tmp.resolve("src.jrsks"),
              Optional.of(pw),
              Optional.of(javaHome),
              sink,
              scope);
    }

    assertThat(runner.last().command()).contains("--storepass", "ks-pass-123");
    assertThat(String.join("\n", sink.logMessages())).doesNotContain("ks-pass-123");
  }

  @Test
  void should_not_start_when_java_home_absent() {
    VendorRun run =
        tools().ant(buildomatic, "deploy-webapp-ce", List.of(), Optional.empty(), sink, scope);

    assertThat(run).isInstanceOf(VendorRun.NotStarted.class);
    assertThat(((VendorRun.NotStarted) run).reason()).contains("vendor.javaHome");
    assertThat(runner.requests()).isEmpty();
  }

  @Test
  void should_not_start_when_script_missing() {
    Buildomatic incomplete =
        new Buildomatic(
            buildomatic.dir(), java.util.Map.of(), Optional.empty(), java.util.Map.of());

    VendorRun run =
        tools().ant(incomplete, "deploy-webapp-ce", List.of(), Optional.of(javaHome), sink, scope);

    assertThat(run).isInstanceOf(VendorRun.NotStarted.class);
    assertThat(((VendorRun.NotStarted) run).reason()).contains("js-ant");
    assertThat(runner.requests()).isEmpty();
  }

  @Test
  void should_report_timeout_when_runner_kills_process() {
    runner.timeOut();
    VendorTools tools = new VendorTools(runner, platform.files(), redactor, Duration.ofMinutes(3));

    VendorRun run =
        tools.ant(buildomatic, "deploy-webapp-ce", List.of(), Optional.of(javaHome), sink, scope);

    assertThat(run).isInstanceOf(VendorRun.TimedOut.class);
    assertThat(((VendorRun.TimedOut) run).timeout()).isEqualTo(Duration.ofMinutes(3));
    assertThat(runner.last().timeout()).isEqualTo(Duration.ofMinutes(3));
    assertThat(sink.logMessages()).anyMatch(m -> m.contains("killed after 180s"));
  }
}
