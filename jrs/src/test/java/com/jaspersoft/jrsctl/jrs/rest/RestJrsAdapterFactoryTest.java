package com.jaspersoft.jrsctl.jrs.rest;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.jaspersoft.jrsctl.core.config.Config;
import com.jaspersoft.jrsctl.core.config.ConfigException;
import com.jaspersoft.jrsctl.core.platform.Platform;
import com.jaspersoft.jrsctl.core.redact.Redactor;
import com.jaspersoft.jrsctl.core.secrets.EncryptedSecretStore;
import com.jaspersoft.jrsctl.core.secrets.PassphraseSource;
import com.jaspersoft.jrsctl.core.secrets.Secret;
import com.jaspersoft.jrsctl.core.secrets.SecretResolver;
import com.jaspersoft.jrsctl.jrs.FakePlatform;
import com.jaspersoft.jrsctl.jrs.TestConfigs;
import com.jaspersoft.jrsctl.jrs.api.HealthReport;
import com.jaspersoft.jrsctl.jrs.api.JrsAdapter;
import com.jaspersoft.jrsctl.jrs.api.JrsAdapterFactory;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

class RestJrsAdapterFactoryTest {

  private static final String PASSWORD = "factory-Secret-9";

  @RegisterExtension
  WireMockExtension wm =
      WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

  @TempDir Path tmp;

  private final Platform platform = new FakePlatform(Platform.OsFamily.LINUX);

  private SecretResolver resolver() {
    EncryptedSecretStore store =
        new EncryptedSecretStore(
            tmp.resolve("secrets.enc"),
            new PassphraseSource.Fixed(Secret.fromString("pp")),
            "host");
    return new SecretResolver(Map.of(TestConfigs.PASSWORD_ENV, PASSWORD), platform.files(), store);
  }

  private URI base() {
    return URI.create("http://localhost:" + wm.getPort() + "/jasperserver-pro");
  }

  @Test
  void should_be_discoverable_when_loaded_through_service_loader() {
    assertThat(JrsAdapterFactory.load()).isInstanceOf(RestJrsAdapterFactory.class);
  }

  @Test
  void should_not_touch_server_and_register_secret_when_connecting() {
    Redactor redactor = new Redactor();

    JrsAdapter adapter =
        new RestJrsAdapterFactory()
            .connect(
                TestConfigs.server(base(), Config.AuthMode.BASIC), resolver(), redactor, platform);

    assertThat(adapter).isInstanceOf(RestJrsAdapter.class);
    wm.verify(0, anyRequestedFor(anyUrl()));
    assertThat(redactor.redact("saw " + PASSWORD)).doesNotContain(PASSWORD).contains(Redactor.MASK);
  }

  @Test
  void should_send_basic_auth_on_first_use_when_mode_is_basic() {
    wm.stubFor(
        get(urlPathEqualTo("/jasperserver-pro/rest_v2/serverInfo"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withBody("{\"version\":\"8.2.0\",\"edition\":\"PRO\"}")));
    JrsAdapter adapter =
        new RestJrsAdapterFactory()
            .connect(
                TestConfigs.server(base(), Config.AuthMode.BASIC),
                resolver(),
                new Redactor(),
                platform);

    assertThat(adapter.identity().version()).isEqualTo("8.2.0");
    wm.verify(
        getRequestedFor(urlPathEqualTo("/jasperserver-pro/rest_v2/serverInfo"))
            .withHeader("Authorization", containing("Basic ")));
  }

  @Test
  void should_throw_config_exception_when_base_url_missing() {
    assertThatThrownBy(
            () ->
                new RestJrsAdapterFactory()
                    .connect(Config.defaults(), resolver(), new Redactor(), platform))
        .isInstanceOf(ConfigException.class)
        .hasMessageContaining("server.baseUrl");
  }

  @Test
  void should_report_unreachable_as_health_item_when_server_is_down() throws IOException {
    int closed;
    try (ServerSocket s = new ServerSocket(0)) {
      closed = s.getLocalPort();
    }
    JrsAdapter adapter =
        new RestJrsAdapterFactory()
            .connect(
                TestConfigs.server(
                    URI.create("http://localhost:" + closed + "/jasperserver"),
                    Config.AuthMode.BASIC),
                resolver(),
                new Redactor(),
                platform);

    HealthReport report = adapter.health();

    assertThat(report.reachable()).isFalse();
    assertThat(report.items()).hasSize(1);
    assertThat(report.items().get(0).status()).isEqualTo(HealthReport.Status.FAIL);
    assertThat(report.items().get(0).remediation()).contains("server.baseUrl");
  }

  @Test
  void should_connect_without_credentials_when_password_ref_absent() {
    JrsAdapter adapter =
        new RestJrsAdapterFactory()
            .connect(TestConfigs.anonymous(base()), resolver(), new Redactor(), platform);
    wm.stubFor(
        get(urlPathEqualTo("/jasperserver-pro/rest_v2/serverInfo"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withBody("{\"version\":\"7.1.0\",\"edition\":\"CE\"}")));

    HealthReport report = adapter.health();

    assertThat(report.reachable()).isTrue();
    assertThat(report.items())
        .extracting(HealthReport.Item::status)
        .containsExactly(HealthReport.Status.PASS, HealthReport.Status.WARN);
  }
}
