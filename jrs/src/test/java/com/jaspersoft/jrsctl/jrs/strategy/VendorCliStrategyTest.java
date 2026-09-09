package com.jaspersoft.jrsctl.jrs.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import com.jaspersoft.jrsctl.core.config.Config;
import com.jaspersoft.jrsctl.core.engine.Context;
import com.jaspersoft.jrsctl.core.engine.RunOutcome;
import com.jaspersoft.jrsctl.core.engine.Step;
import com.jaspersoft.jrsctl.core.platform.ProcessRunner;
import com.jaspersoft.jrsctl.jrs.FakeJrsAdapter;
import com.jaspersoft.jrsctl.jrs.api.ExportImportStrategy;
import com.jaspersoft.jrsctl.jrs.api.ExportRequest;
import com.jaspersoft.jrsctl.jrs.api.ImportRequest;
import com.jaspersoft.jrsctl.jrs.vendor.BuildomaticLocator;
import com.jaspersoft.jrsctl.jrs.vendor.VendorTools;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VendorCliStrategyTest {

  @TempDir Path tmp;
  private StrategyFixture fx;
  private Path installDir;
  private Path javaHome;
  private Path output;
  private VendorCliStrategy strategy;

  @BeforeEach
  void setUp() throws IOException {
    fx = new StrategyFixture(tmp);
    installDir = tmp.resolve("jrs");
    Path buildomatic = installDir.resolve("buildomatic");
    Files.createDirectories(buildomatic);
    Files.writeString(buildomatic.resolve("js-export.sh"), "#!/bin/sh\n");
    Files.writeString(buildomatic.resolve("js-import.sh"), "#!/bin/sh\n");
    javaHome = tmp.resolve("jdk");
    Files.createDirectories(javaHome);
    output = tmp.resolve("out").resolve("full.zip");
    strategy =
        new VendorCliStrategy(
            new BuildomaticLocator(fx.platform),
            new VendorTools(fx.processes, fx.platform.files(), fx.redactor),
            fx.polling);
  }

  @AfterEach
  void tearDown() {
    fx.close();
  }

  private ExportRequest exportRequest() {
    return new ExportRequest(
        ExportRequest.Scope.EVERYTHING, Set.of(), true, false, false, false, true, true, output);
  }

  private ImportRequest importRequest(Optional<Path> sourceKeystore) {
    return new ImportRequest(
        tmp.resolve("in.zip"),
        true,
        false,
        false,
        false,
        false,
        false,
        false,
        sourceKeystore,
        Optional.empty());
  }

  private static List<String> ids(List<Step> steps) {
    return steps.stream().map(Step::id).toList();
  }

  @Test
  void should_order_export_steps_locate_stop_run_start_wait_sidecar() {
    assertThat(ids(strategy.exportSteps(exportRequest())))
        .containsExactly(
            "export.locate-vendor-tools",
            "export.stop-service",
            "export.js-export",
            "export.start-service",
            "export.wait-for-server",
            "export.sidecar");
    assertThat(strategy.requiresServiceStop()).isTrue();
    assertThat(strategy.kind()).isEqualTo(ExportImportStrategy.Kind.VENDOR_CLI);
  }

  @Test
  void should_order_import_steps_with_keystore_step_when_source_keystore_present() {
    assertThat(ids(strategy.importSteps(importRequest(Optional.of(tmp.resolve("s.jrsks"))))))
        .containsExactly(
            "import.check-keystore",
            "import.locate-vendor-tools",
            "import.stop-service",
            "import.source-keystore",
            "import.js-import",
            "import.start-service",
            "import.wait-for-server");
    assertThat(ids(strategy.importSteps(importRequest(Optional.empty()))))
        .doesNotContain("import.source-keystore");
  }

  @Test
  void should_stop_run_js_export_and_start_when_export_runs_through_runner() throws IOException {
    fx.processes.answer(
        (request, onLine) -> {
          int i = request.command().indexOf("--output-zip");
          try {
            Files.writeString(Path.of(request.command().get(i + 1)), "PK-archive-bytes");
          } catch (IOException e) {
            throw new IllegalStateException(e);
          }
          onLine.accept(
              new ProcessRunner.OutputLine(ProcessRunner.OutputLine.Stream.STDOUT, "done"));
          return new ProcessRunner.Result(0, false, Duration.ofSeconds(2));
        });
    Config config = StrategyFixture.vendorConfig(installDir, Optional.of(javaHome));
    Context ctx = fx.context(config, new FakeJrsAdapter());

    RunOutcome outcome = fx.run(strategy.exportSteps(exportRequest()), ctx);

    assertThat(outcome).isInstanceOf(RunOutcome.Succeeded.class);
    assertThat(fx.service.calls()).containsExactly("stop", "start");
    assertThat(Files.readString(output)).isEqualTo("PK-archive-bytes");
    assertThat(Files.exists(RunFiles.partOf(output))).isFalse();
    assertThat(Sidecar.read(Sidecar.pathFor(output)))
        .isPresent()
        .get()
        .satisfies(s -> assertThat(s.strategy()).isEqualTo(ExportImportStrategy.Kind.VENDOR_CLI));
    ProcessRunner.Request req = fx.processes.last();
    assertThat(req.command().get(0))
        .isEqualTo(installDir.resolve("buildomatic").resolve("js-export.sh").toString());
    assertThat(req.command())
        .contains("--everything", "--users", "--roles", "--include-server-settings");
    assertThat(req.environment()).containsEntry("JAVA_HOME", javaHome.toString());
    assertThat(req.workingDir()).contains(installDir.resolve("buildomatic"));
    assertThat(fx.sink.logMessages()).contains("done");
  }

  @Test
  void should_restart_service_and_remove_output_when_js_export_fails() {
    fx.processes.exit(2, "BUILD FAILED");
    Config config = StrategyFixture.vendorConfig(installDir, Optional.of(javaHome));
    Context ctx = fx.context(config, new FakeJrsAdapter());

    RunOutcome outcome = fx.run(strategy.exportSteps(exportRequest()), ctx);

    assertThat(outcome).isInstanceOf(RunOutcome.RolledBack.class);
    assertThat(((RunOutcome.RolledBack) outcome).cause()).contains("BUILD FAILED");
    assertThat(fx.service.calls()).containsExactly("stop", "start");
    assertThat(Files.exists(output)).isFalse();
  }

  @Test
  void should_fail_precheck_without_mutation_when_java_home_missing() {
    Config config = StrategyFixture.vendorConfig(installDir, Optional.empty());
    Context ctx = fx.context(config, new FakeJrsAdapter());

    RunOutcome outcome = fx.run(strategy.exportSteps(exportRequest()), ctx);

    assertThat(outcome).isInstanceOf(RunOutcome.PrecheckFailed.class);
    assertThat(((RunOutcome.PrecheckFailed) outcome).message()).contains("vendor.javaHome");
    assertThat(fx.service.calls()).isEmpty();
    assertThat(fx.processes.requests()).isEmpty();
  }

  @Test
  void should_run_js_import_between_stop_and_start_when_import_runs_through_runner()
      throws IOException {
    Files.writeString(tmp.resolve("in.zip"), "PK");
    fx.processes.exit(0, "Import finished");
    Config config = StrategyFixture.vendorConfig(installDir, Optional.of(javaHome));
    Context ctx = fx.context(config, new FakeJrsAdapter());

    RunOutcome outcome = fx.run(strategy.importSteps(importRequest(Optional.empty())), ctx);

    assertThat(outcome).isInstanceOf(RunOutcome.Succeeded.class);
    assertThat(fx.service.calls()).containsExactly("stop", "start");
    assertThat(fx.processes.last().command())
        .containsSequence("--input-zip", tmp.resolve("in.zip").toString(), "--update");
    assertThat(fx.journal())
        .containsSubsequence(
            "import.stop-service:SUCCEEDED",
            "import.js-import:SUCCEEDED",
            "import.start-service:SUCCEEDED",
            "import.wait-for-server:SUCCEEDED");
  }
}
