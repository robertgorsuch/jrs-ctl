package com.jaspersoft.jrsctl.jrs.rest;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.jaspersoft.jrsctl.core.config.Config;
import com.jaspersoft.jrsctl.jrs.api.ResourceFields;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

/**
 * Issue #307, ADR-0060: the two calls behind {@code import verify --content}. Response shapes are
 * those a 10.0.0 server returned for its samples.
 */
class RestJrsAdapterContentTest {

  @RegisterExtension
  WireMockExtension wm =
      WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

  @TempDir Path tmp;

  @Test
  void should_stream_a_files_content_and_report_a_404_as_empty() throws Exception {
    AdapterFixture f = new AdapterFixture(wm, Config.AuthMode.BASIC);
    wm.stubFor(
        get(urlPathEqualTo(f.path("/rest_v2/resources/public/Images/a.jpeg")))
            .withHeader("Accept", equalTo("application/octet-stream"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "image/jpeg")
                    .withBody("jpeg")));
    wm.stubFor(
        get(urlPathEqualTo(f.path("/rest_v2/resources/public/Images/gone.jpeg")))
            .willReturn(aResponse().withStatus(404)));

    Path target = tmp.resolve("a.data");
    assertThat(f.adapter.downloadResource("/public/Images/a.jpeg", target)).contains(target);
    assertThat(Files.readString(target)).isEqualTo("jpeg");
    assertThat(f.adapter.downloadResource("/public/Images/gone.jpeg", tmp.resolve("g"))).isEmpty();
  }

  @Test
  void should_read_label_description_data_source_and_input_controls_of_a_report_unit() {
    AdapterFixture f = new AdapterFixture(wm, Config.AuthMode.BASIC);
    wm.stubFor(
        get(urlPathEqualTo(f.path("/rest_v2/resources/public/Reports/Geo")))
            .withHeader("Accept", equalTo("application/json"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/repository.reportUnit+json")
                    .withBody(
                        "{\"version\":15,\"label\":\"Geo\",\"description\":\"map\","
                            + "\"dataSource\":{\"dataSourceReference\":"
                            + "{\"uri\":\"/public/DS/Foodmart\",\"version\":0}},"
                            + "\"inputControls\":["
                            + "{\"inputControlReference\":{\"uri\":\"/public/IC/b\",\"version\":15}},"
                            + "{\"inputControlReference\":{\"uri\":\"/public/IC/a\",\"version\":15}}]}")));

    assertThat(f.adapter.resourceFields("/public/Reports/Geo", false))
        .contains(
            new ResourceFields(
                "Geo",
                "map",
                Optional.of("/public/DS/Foodmart"),
                List.of("/public/IC/a", "/public/IC/b")));
  }

  @Test
  void should_ask_for_a_files_descriptor_and_read_a_missing_description_as_empty() {
    AdapterFixture f = new AdapterFixture(wm, Config.AuthMode.BASIC);
    wm.stubFor(
        get(urlPathEqualTo(f.path("/rest_v2/resources/public/Images/a.jpeg")))
            .withHeader("Accept", equalTo("application/repository.file+json"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withBody("{\"version\":1,\"label\":\"a.jpeg\",\"type\":\"img\"}")));
    wm.stubFor(
        get(urlPathEqualTo(f.path("/rest_v2/resources/public/gone")))
            .willReturn(aResponse().withStatus(404)));

    assertThat(f.adapter.resourceFields("/public/Images/a.jpeg", true))
        .contains(new ResourceFields("a.jpeg", "", Optional.empty(), List.of()));
    assertThat(f.adapter.resourceFields("/public/gone", false)).isEmpty();
  }
}
