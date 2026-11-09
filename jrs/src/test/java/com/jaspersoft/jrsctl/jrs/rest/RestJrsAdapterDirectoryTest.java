package com.jaspersoft.jrsctl.jrs.rest;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.jaspersoft.jrsctl.core.config.Config;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Issue #262: the directory listings {@code import verify} compares an archive with: every user and
 * role the server holds (the root listing covers every organisation, REST API reference 10.1
 * pp.311, 321), and whether one organisation exists.
 */
class RestJrsAdapterDirectoryTest {

  @RegisterExtension
  WireMockExtension wm =
      WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

  @Test
  void should_list_users_with_their_organisation_and_none_for_no_content() {
    AdapterFixture f = new AdapterFixture(wm, Config.AuthMode.BASIC);
    wm.stubFor(
        get(urlPathEqualTo(f.path("/rest_v2/users")))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withBody(
                        "{\"user\":[{\"username\":\"superuser\",\"fullName\":\"Super\"},"
                            + "{\"username\":\"jasperadmin\",\"tenantId\":\"organization_1\"}]}")));
    wm.stubFor(
        get(urlPathEqualTo(f.path("/rest_v2/roles"))).willReturn(aResponse().withStatus(204)));

    assertThat(f.adapter.listUserIds()).containsExactly("superuser", "jasperadmin|organization_1");
    assertThat(f.adapter.listRoleNames()).isEmpty();
  }

  @Test
  void should_list_roles_with_their_organisation() {
    AdapterFixture f = new AdapterFixture(wm, Config.AuthMode.BASIC);
    wm.stubFor(
        get(urlPathEqualTo(f.path("/rest_v2/roles")))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withBody(
                        "{\"role\":[{\"name\":\"ROLE_USER\",\"externallyDefined\":false},"
                            + "{\"name\":\"ROLE_DEMO\",\"tenantId\":\"organization_1\"}]}")));

    assertThat(f.adapter.listRoleNames()).containsExactly("ROLE_USER", "ROLE_DEMO|organization_1");
  }

  @Test
  void should_say_whether_an_organisation_exists_and_report_other_errors() {
    AdapterFixture f = new AdapterFixture(wm, Config.AuthMode.BASIC);
    wm.stubFor(
        get(urlPathEqualTo(f.path("/rest_v2/organizations/organization_1")))
            .willReturn(aResponse().withStatus(200).withBody("{\"id\":\"organization_1\"}")));
    wm.stubFor(
        get(urlPathEqualTo(f.path("/rest_v2/organizations/gone")))
            .willReturn(aResponse().withStatus(404)));
    wm.stubFor(
        get(urlPathEqualTo(f.path("/rest_v2/organizations/broken")))
            .willReturn(aResponse().withStatus(500)));

    assertThat(f.adapter.organizationExists("organization_1")).isTrue();
    assertThat(f.adapter.organizationExists("gone")).isFalse();
    assertThatThrownBy(() -> f.adapter.organizationExists("broken"))
        .isInstanceOf(RestException.class)
        .hasMessageContaining("500");
  }
}
