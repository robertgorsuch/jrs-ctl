package com.jaspersoft.jrsctl.jrs.rest;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.jaspersoft.jrsctl.core.config.Config;
import com.jaspersoft.jrsctl.core.redact.Redactor;
import com.jaspersoft.jrsctl.core.secrets.Secret;
import com.jaspersoft.jrsctl.jrs.api.JrsUnreachableException;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

class RestClientTest {

  @RegisterExtension
  static WireMockExtension wm =
      WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

  private static final String PASSWORD = "s3cret-Pa55word!";

  private URI base() {
    return URI.create("http://localhost:" + wm.getPort() + "/jasperserver-pro");
  }

  private RestClient.Builder builder(Redactor redactor) {
    return RestClient.builder(base()).redactor(redactor).correlationId("run-42");
  }

  @Test
  void should_refuse_other_host_and_audit_when_mode_is_isolated() {
    List<String> audit = new ArrayList<>();
    RestClient client =
        builder(new Redactor())
            .networkMode(Config.NetworkMode.ISOLATED)
            .auditHook(audit::add)
            .build();
    String foreign = "http://127.0.0.1:" + wm.getPort() + "/rest_v2/serverInfo?pp=tok";

    assertThatThrownBy(() -> client.get(foreign))
        .isInstanceOf(IsolatedModeViolation.class)
        .hasMessageContaining("127.0.0.1")
        .hasMessageNotContaining("pp=tok");
    assertThat(audit).hasSize(1);
    assertThat(audit.get(0)).contains("FAIL").contains("127.0.0.1").doesNotContain("tok");
    wm.verify(0, getRequestedFor(urlPathEqualTo("/rest_v2/serverInfo")));
  }

  @Test
  void should_allow_other_host_when_mode_is_public() {
    wm.stubFor(get(urlPathEqualTo("/rest_v2/serverInfo")).willReturn(aResponse().withStatus(200)));
    RestClient client = builder(new Redactor()).networkMode(Config.NetworkMode.PUBLIC).build();

    RestClient.Response r = client.get("http://127.0.0.1:" + wm.getPort() + "/rest_v2/serverInfo");

    assertThat(r.status()).isEqualTo(200);
  }

  @Test
  void should_send_basic_header_and_correlation_id_when_basic_auth_configured() {
    wm.stubFor(
        get(urlPathEqualTo("/jasperserver-pro/rest_v2/serverInfo"))
            .willReturn(aResponse().withStatus(200)));
    RestClient client = builder(new Redactor()).build();
    try (Secret pw = Secret.fromString(PASSWORD)) {
      client.useBasic("jasperadmin", pw);
    }

    client.get("/rest_v2/serverInfo");

    String expected =
        "Basic "
            + Base64.getEncoder()
                .encodeToString(
                    ("jasperadmin:" + PASSWORD).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    wm.verify(
        getRequestedFor(urlPathEqualTo("/jasperserver-pro/rest_v2/serverInfo"))
            .withHeader("Authorization", equalTo(expected))
            .withHeader(RestClient.CORRELATION_HEADER, equalTo("run-42")));
  }

  @Test
  void should_not_leak_password_or_header_when_server_answers_401() {
    wm.stubFor(
        get(urlPathEqualTo("/jasperserver-pro/rest_v2/resources"))
            .willReturn(
                aResponse()
                    .withStatus(401)
                    .withBody("Bad credentials for jasperadmin:" + PASSWORD + " try again")));
    Redactor redactor = new Redactor();
    RestClient client = builder(redactor).build();
    try (Secret pw = Secret.fromString(PASSWORD)) {
      client.useBasic("jasperadmin", pw);
    }
    String header =
        "Basic "
            + Base64.getEncoder()
                .encodeToString(
                    ("jasperadmin:" + PASSWORD).getBytes(java.nio.charset.StandardCharsets.UTF_8));

    RestClient.Response r = client.get("/rest_v2/resources?folderUri=%2F");
    assertThatThrownBy(() -> client.require2xx(r, "GET", "/rest_v2/resources?folderUri=%2F"))
        .isInstanceOf(RestException.class)
        .hasMessageContaining("HTTP 401")
        .hasMessageContaining("/rest_v2/resources")
        .hasMessageNotContaining("folderUri")
        .hasMessageNotContaining(PASSWORD)
        .hasMessageNotContaining(header)
        .hasMessageContaining(Redactor.MASK);
  }

  @Test
  void should_append_pp_parameter_when_token_auth_configured() {
    wm.stubFor(
        get(urlPathEqualTo("/jasperserver-pro/rest_v2/jobs"))
            .willReturn(aResponse().withStatus(200)));
    Redactor redactor = new Redactor();
    RestClient client = builder(redactor).build();
    try (Secret token = Secret.fromString("tok&en=1")) {
      client.useToken(token);
    }

    client.get("/rest_v2/jobs?limit=5");

    wm.verify(
        getRequestedFor(urlPathEqualTo("/jasperserver-pro/rest_v2/jobs"))
            .withQueryParam("limit", equalTo("5"))
            .withQueryParam("pp", equalTo("tok&en=1"))
            .withHeader("Authorization", absent()));
    assertThat(redactor.redact("saw tok&en=1 here")).doesNotContain("tok&en=1");
  }

  @Test
  void should_store_session_cookie_and_resend_it_when_form_login_succeeds() {
    wm.stubFor(
        post(urlEqualTo("/jasperserver-pro/j_spring_security_check"))
            .willReturn(
                aResponse()
                    .withStatus(302)
                    .withHeader("Location", "/jasperserver-pro/")
                    .withHeader("Set-Cookie", "JSESSIONID=ABC123; Path=/jasperserver-pro")));
    wm.stubFor(
        get(urlPathEqualTo("/jasperserver-pro/rest_v2/jobs"))
            .willReturn(aResponse().withStatus(200)));
    Redactor redactor = new Redactor();
    RestClient client = builder(redactor).build();

    RestClient.Response login;
    try (Secret pw = Secret.fromString(PASSWORD)) {
      login = client.formLogin("/j_spring_security_check", "jasperadmin", pw);
    }
    client.get("/rest_v2/jobs");

    assertThat(login.status()).isEqualTo(302);
    assertThat(client.sessionCookie()).contains("ABC123");
    wm.verify(
        postRequestedFor(urlEqualTo("/jasperserver-pro/j_spring_security_check"))
            .withHeader("Content-Type", equalTo("application/x-www-form-urlencoded"))
            .withRequestBody(containing("j_username=jasperadmin"))
            .withRequestBody(containing("j_password=")));
    wm.verify(
        getRequestedFor(urlPathEqualTo("/jasperserver-pro/rest_v2/jobs"))
            .withHeader("Cookie", containing("JSESSIONID=ABC123")));
    assertThat(redactor.redact("cookie ABC123")).doesNotContain("ABC123");
  }

  @Test
  void should_throw_unreachable_with_remediation_when_port_is_closed() throws IOException {
    int closedPort;
    try (ServerSocket s = new ServerSocket(0)) {
      closedPort = s.getLocalPort();
    }
    RestClient client =
        RestClient.builder(URI.create("http://localhost:" + closedPort + "/jasperserver"))
            .redactor(new Redactor())
            .build();

    assertThatThrownBy(() -> client.get("/rest_v2/serverInfo?pp=secret-token"))
        .isInstanceOf(JrsUnreachableException.class)
        .hasMessageContaining("/rest_v2/serverInfo")
        .hasMessageNotContaining("secret-token")
        .satisfies(
            e -> {
              JrsUnreachableException u = (JrsUnreachableException) e;
              assertThat(u.remediation()).contains("server.baseUrl");
              assertThat(u.url().toString()).doesNotContain("secret-token");
            });
  }

  @Test
  void should_encode_repository_paths_when_segments_contain_spaces() {
    assertThat(RestClient.encodePath("/public/My Reports/All Accounts"))
        .isEqualTo("/public/My%20Reports/All%20Accounts");
    assertThat(RestClient.encodePath("/")).isEqualTo("/");
    assertThat(RestClient.encodeQuery("/a b")).isEqualTo("%2Fa+b");
  }
}
