package com.jaspersoft.jrsctl.jrs.rest;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.jaspersoft.jrsctl.core.config.Config;
import com.jaspersoft.jrsctl.jrs.api.Capability;
import com.jaspersoft.jrsctl.jrs.api.JrsUnreachableException;
import com.jaspersoft.jrsctl.jrs.api.ServerIdentity;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class RestJrsAdapterCapabilitiesTest {

  @RegisterExtension
  WireMockExtension wm =
      WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

  @Test
  void should_find_every_capability_when_pro_server_answers_all_probes() {
    AdapterFixture f = new AdapterFixture(wm, Config.AuthMode.BASIC);
    f.serverInfo("8.2.0-PRO");
    f.allProbesPresent();

    assertThat(f.adapter.capabilities())
        .containsExactlyInAnyOrder(
            Capability.EXPORT_ASYNC,
            Capability.IMPORT_ASYNC,
            Capability.ORGS,
            Capability.REST_LOGIN,
            Capability.KEYSTORE_ENCRYPTION,
            Capability.TOKEN_AUTH,
            Capability.PREAUTH);
    assertThat(f.adapter.probeResults())
        .containsKeys(Capability.values())
        .hasEntrySatisfying(
            Capability.EXPORT_ASYNC, d -> assertThat(d).contains("HTTP 404").contains("present"))
        .hasEntrySatisfying(
            Capability.TOKEN_AUTH, d -> assertThat(d).contains("compat matrix").contains("8.2.0"));
    assertThat(f.adapter.expectedCapabilities()).isEqualTo(f.adapter.capabilities());
    // probed once, cached afterwards
    f.adapter.capabilities();
    wm.verify(1, getRequestedFor(urlPathEqualTo(f.path("/rest_v2/organizations"))));
    wm.verify(1, getRequestedFor(urlPathEqualTo(f.path("/rest_v2/serverInfo"))));
  }

  @Test
  void should_report_absent_capabilities_when_ce_7_1_server_lacks_them() {
    AdapterFixture f = new AdapterFixture(wm, Config.AuthMode.BASIC);
    f.serverInfo("7.1.0-CE");
    f.probe("/rest_v2/export/jrsctl-probe/state", 405);
    f.probe("/rest_v2/import/jrsctl-probe/state", 501);
    f.probe("/rest_v2/organizations", 404);
    f.probe("/rest_v2/login", 404);

    assertThat(f.adapter.capabilities()).isEmpty();
    assertThat(f.adapter.probeResults())
        .hasEntrySatisfying(
            Capability.ORGS, d -> assertThat(d).contains("HTTP 404").contains("absent"))
        .hasEntrySatisfying(
            Capability.KEYSTORE_ENCRYPTION, d -> assertThat(d).startsWith("absent"));
    assertThat(f.adapter.expectedCapabilities())
        .containsExactlyInAnyOrder(
            Capability.EXPORT_ASYNC, Capability.IMPORT_ASYNC, Capability.REST_LOGIN);
    wm.verify(0, getRequestedFor(urlPathEqualTo(f.path("/rest_v2/export"))));
  }

  @Test
  void should_derive_identity_when_server_info_is_multi_tenant() {
    AdapterFixture f = new AdapterFixture(wm, Config.AuthMode.BASIC);
    f.serverInfo("9.0.0-PRO");

    ServerIdentity id = f.adapter.identity();

    assertThat(id.version()).isEqualTo("9.0.0");
    assertThat(id.tenancy()).isEqualTo(ServerIdentity.Tenancy.MULTI);
    assertThat(id.fingerprintInput()).contains("9.0.0|PRO|MULTI");
  }

  @Test
  void should_throw_unreachable_when_server_info_answers_503() {
    AdapterFixture f = new AdapterFixture(wm, Config.AuthMode.BASIC);
    wm.stubFor(
        get(urlPathEqualTo(f.path("/rest_v2/serverInfo")))
            .willReturn(aResponse().withStatus(503).withBody("starting")));

    assertThatThrownBy(f.adapter::identity)
        .isInstanceOf(JrsUnreachableException.class)
        .hasMessageContaining("503")
        .satisfies(
            e -> assertThat(((JrsUnreachableException) e).remediation()).contains("starting"));
  }

  /**
   * The compat matrix answers for every supported server without a single probe being sent, which
   * is what {@code doctor} relies on when the server is unreachable. The identity is asserted
   * because it is what selects the matrix row; {@link ServerIdentitiesTest} owns parsing the same
   * fixtures in detail.
   */
  @ParameterizedTest(name = "{0}")
  @CsvSource({
    "7.1.0-CE,   7.1.0,  CE,  SINGLE",
    "7.1.0-PRO,  7.1.0,  PRO, MULTI",
    "7.5.0-CE,   7.5.0,  CE,  SINGLE",
    "7.9.1-PRO,  7.9.1,  PRO, SINGLE",
    "8.2.0-CE,   8.2.0,  CE,  SINGLE",
    "8.2.0-PRO,  8.2.0,  PRO, MULTI",
    "9.0.0-CE,   9.0.0,  CE,  SINGLE",
    "9.0.0-PRO,  9.0.0,  PRO, MULTI",
    "10.0.0-CE,  10.0.0, CE,  SINGLE",
    "10.0.0-PRO, 10.0.0, PRO, MULTI"
  })
  void should_expect_the_matrix_capabilities_when_the_server_reports_a_supported_version(
      String fixture, String version, String edition, ServerIdentity.Tenancy tenancy) {
    AdapterFixture f = new AdapterFixture(wm, Config.AuthMode.BASIC);
    f.serverInfo(fixture);

    ServerIdentity id = f.adapter.identity();
    Set<Capability> expected = f.adapter.expectedCapabilities();

    assertThat(id.version()).isEqualTo(version);
    assertThat(id.edition().name()).isEqualTo(edition);
    assertThat(id.tenancy()).isEqualTo(tenancy);
    assertThat(expected)
        .contains(Capability.EXPORT_ASYNC, Capability.IMPORT_ASYNC, Capability.REST_LOGIN);
    assertThat(expected.contains(Capability.ORGS))
        .as("organizations are a PRO capability")
        .isEqualTo(edition.equals("PRO"));
    wm.verify(0, getRequestedFor(urlPathEqualTo(f.path("/rest_v2/organizations"))));
  }
}
