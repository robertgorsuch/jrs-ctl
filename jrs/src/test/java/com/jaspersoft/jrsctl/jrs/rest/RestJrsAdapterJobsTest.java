package com.jaspersoft.jrsctl.jrs.rest;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.jaspersoft.jrsctl.core.config.Config;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Issue #264: the report-job calls behind {@code import --pause-jobs}: the job listing before and
 * after an import, and the pause and resume of the ids the import added ({@code POST
 * /rest_v2/jobs/pause|resume} with a job-id list; REST API reference 10.0 p.229).
 */
class RestJrsAdapterJobsTest {

  @RegisterExtension
  WireMockExtension wm =
      WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

  @Test
  void should_list_the_ids_of_every_job_the_server_holds() {
    AdapterFixture f = new AdapterFixture(wm, Config.AuthMode.BASIC);
    wm.stubFor(
        get(urlPathEqualTo(f.path("/rest_v2/jobs")))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withBody(
                        "{\"jobsummary\":[{\"id\":8320,\"version\":0,\"label\":\"Daily\","
                            + "\"reportUnitURI\":\"/public/a\",\"state\":{\"value\":\"NORMAL\"}},"
                            + "{\"id\":8321,\"label\":\"Weekly\"}]}")));

    assertThat(f.adapter.listJobIds()).containsExactly("8320", "8321");
  }

  @Test
  void should_list_no_ids_when_the_server_has_no_jobs() {
    AdapterFixture f = new AdapterFixture(wm, Config.AuthMode.BASIC);
    wm.stubFor(
        get(urlPathEqualTo(f.path("/rest_v2/jobs"))).willReturn(aResponse().withStatus(204)));

    assertThat(f.adapter.listJobIds()).isEmpty();
  }

  @Test
  void should_pause_and_resume_the_given_jobs_with_an_id_list_and_nothing_for_none() {
    AdapterFixture f = new AdapterFixture(wm, Config.AuthMode.BASIC);
    wm.stubFor(
        post(urlPathEqualTo(f.path("/rest_v2/jobs/pause")))
            .willReturn(aResponse().withStatus(200)));
    wm.stubFor(
        post(urlPathEqualTo(f.path("/rest_v2/jobs/resume")))
            .willReturn(aResponse().withStatus(200)));

    f.adapter.pauseJobs(List.of("8320", "8321"));
    f.adapter.resumeJobs(List.of("8321"));
    f.adapter.pauseJobs(List.of());

    wm.verify(
        1,
        postRequestedFor(urlPathEqualTo(f.path("/rest_v2/jobs/pause")))
            .withRequestBody(equalToJson("{\"jobId\":[8320,8321]}")));
    wm.verify(
        1,
        postRequestedFor(urlPathEqualTo(f.path("/rest_v2/jobs/resume")))
            .withRequestBody(equalToJson("{\"jobId\":[8321]}")));
  }

  @Test
  void should_report_the_server_error_when_a_pause_is_refused() {
    AdapterFixture f = new AdapterFixture(wm, Config.AuthMode.BASIC);
    wm.stubFor(
        post(urlPathEqualTo(f.path("/rest_v2/jobs/pause")))
            .willReturn(aResponse().withStatus(500).withBody("{\"message\":\"scheduler down\"}")));

    assertThatThrownBy(() -> f.adapter.pauseJobs(List.of("1")))
        .isInstanceOf(RestException.class)
        .hasMessageContaining("500")
        .hasMessageContaining("/rest_v2/jobs/pause");
  }
}
