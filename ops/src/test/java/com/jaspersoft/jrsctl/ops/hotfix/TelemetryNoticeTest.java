package com.jaspersoft.jrsctl.ops.hotfix;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Review §3.3 (issue #113): the telemetry programme arrives through a cumulative hotfix, so the
 * apply plan says when the package lays down the switch, or when the server already has it on.
 */
class TelemetryNoticeTest {

  @TempDir Path tmp;

  private static Manifest manifest(List<Manifest.FileEntry> files) {
    return new Manifest(
        "JRS-8.2.0-HF-0001",
        "1",
        "t",
        Optional.empty(),
        new Manifest.Applies(List.of("8.2.0"), List.of("PRO"), List.of()),
        List.of(),
        List.of(),
        files,
        List.of(),
        List.of(),
        Manifest.Restart.NONE,
        List.of(),
        List.of(),
        Manifest.Rollback.SNAPSHOT,
        Optional.empty());
  }

  private HotfixPaths paths() throws Exception {
    Path install = tmp.resolve("jrs");
    Path tomcat = Files.createDirectories(install.resolve("apache-tomcat"));
    return new HotfixPaths(install, tomcat);
  }

  @Test
  void should_warn_when_the_package_lays_down_js_config_properties() throws Exception {
    Manifest m =
        manifest(
            List.of(
                new Manifest.FileEntry(
                    Manifest.Action.REPLACE,
                    "webapps/jasperserver-pro/WEB-INF/js.config.properties",
                    Optional.empty(),
                    List.of())));
    Optional<String> warning = TelemetryNotice.warning(m, paths(), "jasperserver-pro");
    assertThat(warning).isPresent();
    assertThat(warning.get()).contains("heartbeat.enabled").contains("jrsctl doctor after");
  }

  @Test
  void should_warn_when_the_server_already_uploads_telemetry_and_stay_quiet_otherwise()
      throws Exception {
    HotfixPaths paths = paths();
    Manifest m =
        manifest(
            List.of(
                new Manifest.FileEntry(
                    Manifest.Action.REPLACE,
                    "webapps/jasperserver-pro/WEB-INF/lib/x.jar",
                    Optional.empty(),
                    List.of())));
    assertThat(TelemetryNotice.warning(m, paths, "jasperserver-pro")).isEmpty();

    Path file =
        Files.createDirectories(
                paths.tomcatDir().resolve("webapps").resolve("jasperserver-pro").resolve("WEB-INF"))
            .resolve("js.config.properties");
    Files.writeString(file, "heartbeat.enabled=false\n", StandardCharsets.ISO_8859_1);
    assertThat(TelemetryNotice.warning(m, paths, "jasperserver-pro")).isEmpty();

    Files.writeString(file, "heartbeat.enabled=true\n", StandardCharsets.ISO_8859_1);
    Optional<String> warning = TelemetryNotice.warning(m, paths, "jasperserver-pro");
    assertThat(warning).isPresent();
    assertThat(warning.get()).contains("heartbeat.enabled=true").contains("telemetry item");
  }
}
