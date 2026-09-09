package com.jaspersoft.jrsctl.jrs.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import com.jaspersoft.jrsctl.core.config.Config;
import com.jaspersoft.jrsctl.core.platform.Platform;
import com.jaspersoft.jrsctl.core.redact.Redactor;
import com.jaspersoft.jrsctl.jrs.FakeJrsAdapter;
import com.jaspersoft.jrsctl.jrs.FakePlatform;
import com.jaspersoft.jrsctl.jrs.TestConfigs;
import com.jaspersoft.jrsctl.jrs.api.Capability;
import com.jaspersoft.jrsctl.jrs.api.ExportImportStrategy;
import com.jaspersoft.jrsctl.jrs.api.ExportRequest;
import com.jaspersoft.jrsctl.jrs.api.ImportRequest;
import com.jaspersoft.jrsctl.jrs.api.JrsUnreachableException;
import java.net.URI;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class StrategiesTest {

  private final Config config = TestConfigs.server(StrategyFixture.BASE, Config.AuthMode.BASIC);
  private final Strategies strategies =
      Strategies.standard(new FakePlatform(Platform.OsFamily.LINUX), new Redactor());

  private static ExportRequest export(boolean fullServer) {
    return new ExportRequest(
        ExportRequest.Scope.REPOSITORY,
        Set.of("/public"),
        false,
        false,
        false,
        false,
        false,
        fullServer,
        Path.of("x.zip"));
  }

  private static ImportRequest importRequest() {
    return new ImportRequest(
        Path.of("x.zip"),
        false,
        false,
        false,
        false,
        false,
        false,
        false,
        Optional.empty(),
        Optional.empty());
  }

  @Test
  void should_select_rest_when_probe_passes_and_not_full_server() {
    Strategies.Selection s =
        strategies.select(config, new FakeJrsAdapter(), export(false), Optional.empty());

    assertThat(s.kind()).isEqualTo(ExportImportStrategy.Kind.REST);
    assertThat(s.strategy().requiresServiceStop()).isFalse();
    assertThat(s.reason()).contains("EXPORT_ASYNC");
  }

  @Test
  void should_select_vendor_when_full_server_even_if_probe_passes() {
    Strategies.Selection s =
        strategies.select(config, new FakeJrsAdapter(), export(true), Optional.empty());

    assertThat(s.kind()).isEqualTo(ExportImportStrategy.Kind.VENDOR_CLI);
    assertThat(s.strategy().requiresServiceStop()).isTrue();
    assertThat(s.reason()).contains("full-server");
  }

  @Test
  void should_select_vendor_when_probe_fails() {
    FakeJrsAdapter noAsync = new FakeJrsAdapter().withCapabilities(Set.of(Capability.ORGS));

    Strategies.Selection export =
        strategies.select(config, noAsync, export(false), Optional.empty());
    Strategies.Selection imp =
        strategies.select(config, noAsync, importRequest(), Optional.empty());

    assertThat(export.kind()).isEqualTo(ExportImportStrategy.Kind.VENDOR_CLI);
    assertThat(export.reason()).contains("EXPORT_ASYNC probe failed");
    assertThat(imp.kind()).isEqualTo(ExportImportStrategy.Kind.VENDOR_CLI);
    assertThat(imp.reason()).contains("IMPORT_ASYNC probe failed");
  }

  @Test
  void should_select_vendor_when_server_unreachable_during_probe() {
    FakeJrsAdapter down =
        new FakeJrsAdapter()
            .failingProbe(
                new JrsUnreachableException(
                    URI.create("http://x"), "connection refused", "start the server", null));

    Strategies.Selection s = strategies.select(config, down, export(false), Optional.empty());

    assertThat(s.kind()).isEqualTo(ExportImportStrategy.Kind.VENDOR_CLI);
    assertThat(s.reason()).contains("probe failed").contains("connection refused");
  }

  @Test
  void should_honour_forced_kind_when_given() {
    FakeJrsAdapter adapter = new FakeJrsAdapter();

    Strategies.Selection vendor =
        strategies.select(
            config, adapter, export(false), Optional.of(ExportImportStrategy.Kind.VENDOR_CLI));
    Strategies.Selection rest =
        strategies.select(
            config, adapter, importRequest(), Optional.of(ExportImportStrategy.Kind.REST));

    assertThat(vendor.kind()).isEqualTo(ExportImportStrategy.Kind.VENDOR_CLI);
    assertThat(vendor.reason()).contains("forced");
    assertThat(rest.kind()).isEqualTo(ExportImportStrategy.Kind.REST);
    assertThat(rest.reason()).contains("forced");
  }

  @Test
  void should_select_rest_for_import_when_import_probe_passes() {
    Strategies.Selection s =
        strategies.select(config, new FakeJrsAdapter(), importRequest(), Optional.empty());

    assertThat(s.kind()).isEqualTo(ExportImportStrategy.Kind.REST);
  }
}
