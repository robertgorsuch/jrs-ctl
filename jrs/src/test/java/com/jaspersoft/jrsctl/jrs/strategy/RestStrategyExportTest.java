package com.jaspersoft.jrsctl.jrs.strategy;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.jaspersoft.jrsctl.core.engine.Context;
import com.jaspersoft.jrsctl.core.engine.RunOutcome;
import com.jaspersoft.jrsctl.core.engine.Step;
import com.jaspersoft.jrsctl.core.engine.StepResult;
import com.jaspersoft.jrsctl.core.event.EventSink;
import com.jaspersoft.jrsctl.jrs.api.ExportImportStrategy;
import com.jaspersoft.jrsctl.jrs.api.ExportRequest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

class RestStrategyExportTest {

  private static final int TWO_MB = 2 * 1024 * 1024;

  @RegisterExtension
  WireMockExtension wm =
      WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

  @TempDir Path tmp;
  private StrategyFixture fx;
  private RestFixture rest;
  private Path output;

  @BeforeEach
  void setUp() throws IOException {
    fx = new StrategyFixture(tmp);
    rest = new RestFixture(wm, fx.platform, fx.redactor, tmp.resolve("userhome"));
    output = tmp.resolve("out").resolve("export.zip");
  }

  @AfterEach
  void tearDown() {
    fx.close();
  }

  private ExportRequest request() {
    return new ExportRequest(
        ExportRequest.Scope.REPOSITORY,
        Set.of("/public"),
        true,
        false,
        false,
        false,
        false,
        false,
        output);
  }

  private void stubStart() {
    wm.stubFor(
        post(urlPathEqualTo(rest.path("/rest_v2/export")))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"id\":\"exp-1\",\"phase\":\"inprogress\"}")));
  }

  private void stubStates(String... phases) {
    String state = Scenario.STARTED;
    for (int i = 0; i < phases.length; i++) {
      String next = "s" + (i + 1);
      boolean last = i == phases.length - 1;
      wm.stubFor(
          get(urlPathEqualTo(rest.path("/rest_v2/export/exp-1/state")))
              .inScenario("export")
              .whenScenarioStateIs(state)
              .willReturn(aResponse().withStatus(200).withBody(phases[i]))
              .willSetStateTo(last ? state : next));
      state = next;
    }
  }

  private byte[] stubDownload() {
    byte[] archive = new byte[TWO_MB];
    Arrays.fill(archive, (byte) 'z');
    archive[0] = 'P';
    archive[1] = 'K';
    wm.stubFor(
        get(urlPathEqualTo(rest.path("/rest_v2/export/exp-1/export.zip")))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/zip")
                    .withBody(archive)));
    return archive;
  }

  @Test
  void should_export_download_and_write_sidecar_when_server_finishes() throws IOException {
    stubStart();
    stubStates(
        "{\"phase\":\"inprogress\",\"message\":\"10%\"}",
        "{\"phase\":\"inprogress\",\"message\":\"60%\"}",
        "{\"phase\":\"ready\",\"message\":\"Export succeeded\"}");
    byte[] archive = stubDownload();
    List<Step> steps = new RestStrategy(fx.polling).exportSteps(request());
    Context ctx = fx.context(rest.config, rest.adapter);

    RunOutcome outcome = fx.run(steps, ctx);

    assertThat(outcome).isInstanceOf(RunOutcome.Succeeded.class);
    assertThat(Files.size(output)).isEqualTo(archive.length);
    assertThat(Files.exists(RunFiles.partOf(output))).isFalse();
    Optional<Sidecar> sidecar = Sidecar.read(Sidecar.pathFor(output));
    assertThat(sidecar).isPresent();
    assertThat(sidecar.get().keystoreFingerprint())
        .contains(fx.platform.files().sha256(rest.keystoreFile));
    assertThat(sidecar.get().sha256()).isEqualTo(fx.platform.files().sha256(output));
    assertThat(sidecar.get().serverVersion()).isEqualTo("8.2.0");
    assertThat(sidecar.get().strategy()).isEqualTo(ExportImportStrategy.Kind.REST);
    assertThat(sidecar.get().flags().uris()).containsExactly("/public");
    assertThat(sidecar.get().flags().includeUsersRoles()).isTrue();
    assertThat(sidecar.get().exportedAt()).isEqualTo(StrategyFixture.NOW);
    wm.verify(1, postRequestedFor(urlPathEqualTo(rest.path("/rest_v2/export"))));
    wm.verify(3, getRequestedFor(urlPathEqualTo(rest.path("/rest_v2/export/exp-1/state"))));
    assertThat(fx.journal())
        .contains(
            "export.start:SUCCEEDED",
            "export.poll:SUCCEEDED",
            "export.download:SUCCEEDED",
            "export.sidecar:SUCCEEDED");
  }

  @Test
  void should_post_export_once_when_start_step_executes_twice() {
    stubStart();
    Step start = new RestStrategy(fx.polling).exportSteps(request()).get(0);
    Context ctx = fx.context(rest.config, rest.adapter);

    StepResult first = start.execute(ctx, EventSink.discard());
    StepResult second = start.execute(ctx, EventSink.discard());

    assertThat(first).isInstanceOf(StepResult.Ok.class);
    assertThat(second).isInstanceOf(StepResult.Ok.class);
    wm.verify(1, postRequestedFor(urlPathEqualTo(rest.path("/rest_v2/export"))));
  }

  @Test
  void should_fail_recoverably_and_skip_download_when_poll_reports_failed() throws IOException {
    stubStart();
    stubStates(
        "{\"phase\":\"inprogress\"}",
        "{\"phase\":\"failed\",\"message\":\"disk full on server\",\"errorCode\":\"export.failed\"}");
    stubDownload();
    List<Step> steps = new RestStrategy(fx.polling).exportSteps(request());
    Context ctx = fx.context(rest.config, rest.adapter);

    RunOutcome outcome = fx.run(steps, ctx);

    assertThat(outcome).isInstanceOf(RunOutcome.RolledBack.class);
    assertThat(((RunOutcome.RolledBack) outcome).cause())
        .contains("disk full on server")
        .contains("export.failed");
    wm.verify(0, getRequestedFor(urlPathEqualTo(rest.path("/rest_v2/export/exp-1/export.zip"))));
    assertThat(fx.journal()).doesNotContain("export.download:RUNNING");
    assertThat(Files.exists(output)).isFalse();
    assertThat(Files.exists(Sidecar.pathFor(output))).isFalse();
  }

  @Test
  void should_delete_partial_and_final_file_when_download_step_compensates() throws IOException {
    Files.createDirectories(output.getParent());
    Files.writeString(output, "archive");
    Files.writeString(RunFiles.partOf(output), "partial");
    Step download = new DownloadExport(output);

    StepResult result =
        download.compensate(fx.context(rest.config, rest.adapter), EventSink.discard());

    assertThat(result).isInstanceOf(StepResult.Ok.class);
    assertThat(Files.exists(output)).isFalse();
    assertThat(Files.exists(RunFiles.partOf(output))).isFalse();
  }
}
