package com.jaspersoft.jrsctl.jrs.rest;

import static org.assertj.core.api.Assertions.assertThat;

import com.jaspersoft.jrsctl.core.compat.CompatMatrix;
import com.jaspersoft.jrsctl.core.config.Config;
import com.jaspersoft.jrsctl.core.platform.Platform;
import com.jaspersoft.jrsctl.core.redact.Redactor;
import com.jaspersoft.jrsctl.core.secrets.Secret;
import com.jaspersoft.jrsctl.jrs.FakePlatform;
import com.jaspersoft.jrsctl.jrs.TestConfigs;
import com.jaspersoft.jrsctl.jrs.api.Capability;
import com.jaspersoft.jrsctl.jrs.api.Credentials;
import com.jaspersoft.jrsctl.jrs.api.HealthReport;
import com.jaspersoft.jrsctl.jrs.api.ServerIdentity;
import com.jaspersoft.jrsctl.jrs.keystore.KeystoreInspector;
import java.net.URI;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Contract test against a real JasperReports Server (release gate, spec §0 rule 10 and §16). It is
 * excluded from every phase build by the {@code needs-jrs} tag and is skipped outright when Docker
 * is unavailable. The image is spec §19 open question Q6: the working assumption is a {@code
 * js-docker} build of the CE edition pushed to the private registry; override it with {@code
 * -Djrsctl.test.jrsImage=<image>} until Q6 is answered by ADR.
 */
@Tag("needs-jrs")
@Testcontainers(disabledWithoutDocker = true)
class LiveJrsContainerTest {

  /** Spec §19 Q6: which JRS CE container image the needs-jrs suite uses. */
  static final String IMAGE =
      System.getProperty(
          "jrsctl.test.jrsImage", "registry.example.invalid/js-docker/jrs-ce:latest");

  static final int PORT = 8080;
  static final String CONTEXT = "/jasperserver";

  @Container
  static final GenericContainer<?> JRS =
      new GenericContainer<>(DockerImageName.parse(IMAGE))
          .withExposedPorts(PORT)
          .waitingFor(
              Wait.forHttp(CONTEXT + "/rest_v2/serverInfo")
                  .forPort(PORT)
                  .withStartupTimeout(Duration.ofMinutes(10)));

  private RestJrsAdapter adapter() {
    URI base = URI.create("http://" + JRS.getHost() + ":" + JRS.getMappedPort(PORT) + CONTEXT);
    Config config = TestConfigs.server(base, Config.AuthMode.BASIC);
    Redactor redactor = new Redactor();
    RestClient client = RestClient.builder(base).redactor(redactor).build();
    Secret password = Secret.fromString("jasperadmin");
    client.useBasic("jasperadmin", password);
    Platform platform = new FakePlatform(Platform.OsFamily.LINUX);
    return new RestJrsAdapter(
        client,
        config,
        CompatMatrix.load(),
        Optional.of(new Credentials("jasperadmin", password, Optional.empty())),
        new KeystoreInspector(platform, config));
  }

  @Test
  void should_detect_identity_and_probe_capabilities_when_container_is_up() {
    RestJrsAdapter adapter = adapter();

    ServerIdentity id = adapter.identity();

    assertThat(id.version()).matches("\\d+\\.\\d+\\.\\d+");
    assertThat(id.edition()).isEqualTo(ServerIdentity.Edition.CE);
    assertThat(adapter.capabilities()).contains(Capability.EXPORT_ASYNC, Capability.IMPORT_ASYNC);
  }

  @Test
  void should_pass_health_when_container_is_up() {
    HealthReport report = adapter().health();

    assertThat(report.reachable()).isTrue();
    assertThat(report.ok()).as(report.items().toString()).isTrue();
  }
}
