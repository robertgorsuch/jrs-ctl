package com.jaspersoft.jrsctl.jrs.rest;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.jaspersoft.jrsctl.core.compat.CompatMatrix;
import com.jaspersoft.jrsctl.core.config.Config;
import com.jaspersoft.jrsctl.core.platform.Platform;
import com.jaspersoft.jrsctl.core.redact.Redactor;
import com.jaspersoft.jrsctl.core.secrets.Secret;
import com.jaspersoft.jrsctl.jrs.FakePlatform;
import com.jaspersoft.jrsctl.jrs.TestConfigs;
import com.jaspersoft.jrsctl.jrs.api.Credentials;
import com.jaspersoft.jrsctl.jrs.keystore.KeystoreInspector;
import java.io.IOException;
import java.net.URI;
import java.util.Optional;

/** Wires a {@link RestJrsAdapter} against a WireMock server for the adapter tests. */
final class AdapterFixture {

  static final String CONTEXT = "/jasperserver-pro";
  static final String PASSWORD = "adm1n-Secret";

  final WireMockExtension wm;
  final Redactor redactor = new Redactor();
  final Config config;
  final RestClient client;
  final RestJrsAdapter adapter;
  final Secret password = Secret.fromString(PASSWORD);

  AdapterFixture(WireMockExtension wm, Config.AuthMode mode) {
    this(wm, mode, new FakePlatform(Platform.OsFamily.LINUX));
  }

  AdapterFixture(WireMockExtension wm, Config.AuthMode mode, Platform platform) {
    this.wm = wm;
    this.config = TestConfigs.server(base(), mode);
    this.client =
        RestClient.builder(base())
            .redactor(redactor)
            .networkMode(Config.NetworkMode.ISOLATED)
            .correlationId("run-1")
            .build();
    Credentials credentials = new Credentials("jasperadmin", password, Optional.empty());
    switch (mode) {
      case BASIC -> client.useBasic("jasperadmin", password);
      case TOKEN -> client.useToken(password);
      case FORM -> {}
    }
    this.adapter =
        new RestJrsAdapter(
            client,
            config,
            CompatMatrix.load(),
            Optional.of(credentials),
            new KeystoreInspector(platform, config));
  }

  URI base() {
    return URI.create("http://localhost:" + wm.getPort() + CONTEXT);
  }

  /** Stubs serverInfo with a fixture from {@code src/test/resources/fixtures}. */
  void serverInfo(String fixture) {
    try {
      wm.stubFor(
          get(urlPathEqualTo(CONTEXT + "/rest_v2/serverInfo"))
              .willReturn(
                  aResponse()
                      .withStatus(200)
                      .withHeader("Content-Type", "application/json")
                      .withBody(ServerIdentitiesTest.fixture(fixture))));
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  /** Stubs the four probe endpoints for a fully capable PRO multi-tenant server. */
  void allProbesPresent() {
    probe("/rest_v2/export/jrsctl-probe/state", 404);
    probe("/rest_v2/import/jrsctl-probe/state", 404);
    probe("/rest_v2/organizations", 200);
    probe("/rest_v2/login", 405);
  }

  void probe(String path, int status) {
    wm.stubFor(get(urlPathEqualTo(CONTEXT + path)).willReturn(aResponse().withStatus(status)));
  }

  String path(String rel) {
    return CONTEXT + rel;
  }
}
