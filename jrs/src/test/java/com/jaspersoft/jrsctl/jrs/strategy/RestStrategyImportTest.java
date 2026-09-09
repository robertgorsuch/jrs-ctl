package com.jaspersoft.jrsctl.jrs.strategy;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.jaspersoft.jrsctl.core.config.Config;
import com.jaspersoft.jrsctl.core.engine.CheckResult;
import com.jaspersoft.jrsctl.core.engine.Context;
import com.jaspersoft.jrsctl.core.engine.RunOutcome;
import com.jaspersoft.jrsctl.core.engine.Step;
import com.jaspersoft.jrsctl.core.engine.StepResult;
import com.jaspersoft.jrsctl.core.event.EventSink;
import com.jaspersoft.jrsctl.jrs.FakeJrsAdapter;
import com.jaspersoft.jrsctl.jrs.RecordingSink;
import com.jaspersoft.jrsctl.jrs.TestConfigs;
import com.jaspersoft.jrsctl.jrs.api.ExportImportStrategy;
import com.jaspersoft.jrsctl.jrs.api.ExportRequest;
import com.jaspersoft.jrsctl.jrs.api.ImportRequest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

class RestStrategyImportTest {

  private static final String FP_A = "a".repeat(64);
  private static final String FP_B = "b".repeat(64);

  @RegisterExtension
  WireMockExtension wm =
      WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

  @TempDir Path tmp;
  private StrategyFixture fx;
  private Path archive;

  @BeforeEach
  void setUp() throws IOException {
    fx = new StrategyFixture(tmp);
    archive = tmp.resolve("in.zip");
    Files.writeString(archive, "PK".repeat(1000));
  }

  @AfterEach
  void tearDown() {
    fx.close();
  }

  private ImportRequest request(Optional<Path> sourceKeystore) {
    return new ImportRequest(
        archive, true, false, false, false, false, false, false, sourceKeystore, Optional.empty());
  }

  private void sidecarWith(Optional<String> fingerprint) throws IOException {
    Sidecar.write(
        Sidecar.pathFor(archive),
        new Sidecar(
            StrategyFixture.NOW,
            "srv",
            "8.2.0",
            fingerprint,
            new Sidecar.Flags(
                ExportRequest.Scope.REPOSITORY,
                List.of("/public"),
                false,
                false,
                false,
                false,
                false,
                false),
            "deadbeef",
            ExportImportStrategy.Kind.REST));
  }

  private CheckResult precheck(FakeJrsAdapter adapter, ImportRequest request) {
    Step check = new RestStrategy(fx.polling).importSteps(request).get(0);
    assertThat(check.id()).isEqualTo(CheckKeystoreFingerprint.ID);
    return check.precheck(
        fx.context(TestConfigs.server(StrategyFixture.BASE, Config.AuthMode.BASIC), adapter));
  }

  @Test
  void should_fail_precheck_with_remediation_when_keystore_fingerprints_differ()
      throws IOException {
    sidecarWith(Optional.of(FP_A));
    FakeJrsAdapter adapter =
        new FakeJrsAdapter().withKeystore(FakeJrsAdapter.keystoreWith(FP_B, Optional.empty()));

    CheckResult result = precheck(adapter, request(Optional.empty()));

    assertThat(result).isInstanceOf(CheckResult.Fail.class);
    CheckResult.Fail fail = (CheckResult.Fail) result;
    assertThat(fail.message()).contains("mismatch");
    assertThat(fail.remediation())
        .isEqualTo(CheckKeystoreFingerprint.REMEDIATION)
        .contains("--source-keystore")
        .contains("--source-keystore-password-ref");
  }

  @Test
  void should_pass_precheck_when_keystore_fingerprints_match() throws IOException {
    sidecarWith(Optional.of(FP_A));
    FakeJrsAdapter adapter =
        new FakeJrsAdapter().withKeystore(FakeJrsAdapter.keystoreWith(FP_A, Optional.empty()));

    CheckResult result = precheck(adapter, request(Optional.empty()));

    assertThat(result).isInstanceOf(CheckResult.Pass.class);
  }

  @Test
  void should_not_fail_precheck_when_fingerprints_differ_but_source_keystore_supplied()
      throws IOException {
    sidecarWith(Optional.of(FP_A));
    Path source = tmp.resolve("source.jrsks");
    Files.writeString(source, "ks");
    FakeJrsAdapter adapter =
        new FakeJrsAdapter().withKeystore(FakeJrsAdapter.keystoreWith(FP_B, Optional.empty()));

    CheckResult result = precheck(adapter, request(Optional.of(source)));

    assertThat(result).isInstanceOf(CheckResult.Warn.class);
    assertThat(((CheckResult.Warn) result).message()).contains("will be imported first");
  }

  @Test
  void should_warn_when_no_sidecar_exists() {
    FakeJrsAdapter adapter =
        new FakeJrsAdapter().withKeystore(FakeJrsAdapter.keystoreWith(FP_B, Optional.empty()));

    CheckResult result = precheck(adapter, request(Optional.empty()));

    assertThat(result).isInstanceOf(CheckResult.Warn.class);
  }

  @Test
  void should_pass_verify_when_server_purges_task_after_poll_observed_ready() throws IOException {
    RestFixture rest = new RestFixture(wm, fx.platform, fx.redactor, tmp.resolve("userhome"));
    wm.stubFor(
        post(urlPathEqualTo(rest.path("/rest_v2/import")))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withBody("{\"id\":\"imp-1\",\"phase\":\"inprogress\"}")));
    wm.stubFor(
        get(urlPathEqualTo(rest.path("/rest_v2/import/imp-1/state")))
            .inScenario("purge")
            .whenScenarioStateIs(com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED)
            .willReturn(aResponse().withStatus(200).withBody("{\"phase\":\"ready\"}"))
            .willSetStateTo("purged"));
    wm.stubFor(
        get(urlPathEqualTo(rest.path("/rest_v2/import/imp-1/state")))
            .inScenario("purge")
            .whenScenarioStateIs("purged")
            .willReturn(
                aResponse()
                    .withStatus(404)
                    .withBody(
                        "{\"message\":\"No export task with id imp-1.\","
                            + "\"errorCode\":\"no.such.export.process\"}")));
    List<Step> steps = new RestStrategy(fx.polling).importSteps(request(Optional.empty()));
    Context ctx = fx.context(rest.config, rest.adapter);

    RunOutcome outcome = fx.run(steps, ctx);

    assertThat(outcome).isInstanceOf(RunOutcome.Succeeded.class);
    assertThat(fx.journal())
        .contains("import.start:SUCCEEDED", "import.poll:SUCCEEDED", "import.verify:SUCCEEDED");
  }

  @Test
  void should_include_source_keystore_step_only_when_request_names_one() throws IOException {
    Path source = tmp.resolve("source.jrsks");
    Files.writeString(source, "ks");
    RestStrategy strategy = new RestStrategy(fx.polling);

    List<String> without =
        strategy.importSteps(request(Optional.empty())).stream().map(Step::id).toList();
    List<String> with =
        strategy.importSteps(request(Optional.of(source))).stream().map(Step::id).toList();

    assertThat(without)
        .containsExactly(
            CheckKeystoreFingerprint.ID, StartImport.ID, PollImport.ID, VerifyImport.ID);
    assertThat(with)
        .containsExactly(
            CheckKeystoreFingerprint.ID,
            ImportSourceKeystore.ID,
            StartImport.ID,
            PollImport.ID,
            VerifyImport.ID);
    assertThat(strategy.requiresServiceStop()).isFalse();
    assertThat(strategy.kind()).isEqualTo(ExportImportStrategy.Kind.REST);
  }

  @Test
  void should_import_through_runner_and_post_once_when_start_executes_twice() throws IOException {
    RestFixture rest = new RestFixture(wm, fx.platform, fx.redactor, tmp.resolve("userhome"));
    wm.stubFor(
        post(urlPathEqualTo(rest.path("/rest_v2/import")))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withBody("{\"id\":\"imp-1\",\"phase\":\"inprogress\"}")));
    wm.stubFor(
        get(urlPathEqualTo(rest.path("/rest_v2/import/imp-1/state")))
            .willReturn(aResponse().withStatus(200).withBody("{\"phase\":\"ready\"}")));
    List<Step> steps = new RestStrategy(fx.polling).importSteps(request(Optional.empty()));
    Context ctx = fx.context(rest.config, rest.adapter);

    RunOutcome outcome = fx.run(steps, ctx);
    StepResult again = steps.get(1).execute(ctx, EventSink.discard());

    assertThat(outcome).isInstanceOf(RunOutcome.Succeeded.class);
    assertThat(again).isInstanceOf(StepResult.Ok.class);
    wm.verify(1, postRequestedFor(urlPathEqualTo(rest.path("/rest_v2/import"))));
    assertThat(RunFiles.read(RunFiles.in(ctx, RunFiles.IMPORT_HANDLE))).contains("imp-1");
    assertThat(fx.journal())
        .contains("import.start:SUCCEEDED", "import.poll:SUCCEEDED", "import.verify:SUCCEEDED");
  }

  @Test
  void should_log_snapshot_note_when_start_import_compensates() {
    Step start = new StartImport(request(Optional.empty()));
    RecordingSink sink = new RecordingSink();

    StepResult result =
        start.compensate(
            fx.context(
                TestConfigs.server(StrategyFixture.BASE, Config.AuthMode.BASIC),
                new FakeJrsAdapter()),
            sink);

    assertThat(result).isInstanceOf(StepResult.Ok.class);
    assertThat(sink.logMessages()).containsExactly(StartImport.ROLLBACK_NOTE);
  }
}
