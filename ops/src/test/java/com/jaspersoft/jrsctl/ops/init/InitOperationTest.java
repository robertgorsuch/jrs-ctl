package com.jaspersoft.jrsctl.ops.init;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jaspersoft.jrsctl.core.config.Config;
import com.jaspersoft.jrsctl.core.config.ConfigLoader;
import com.jaspersoft.jrsctl.core.platform.Platform;
import com.jaspersoft.jrsctl.core.platform.ServiceConfig;
import com.jaspersoft.jrsctl.ops.FakeLayout;
import com.jaspersoft.jrsctl.ops.FakePlatform;
import com.jaspersoft.jrsctl.ops.FakeServices;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InitOperationTest {

  @TempDir Path tmp;

  @Test
  void should_detect_linux_layout_with_ctlscript_when_no_systemd_unit_matches() throws Exception {
    Path install = FakeLayout.linux(tmp.resolve("jrs"));
    try (FakeServices fake = FakeServices.in(tmp.resolve("home"), Platform.OsFamily.LINUX)) {
      fake.platform.on(
          List.of("systemctl"), FakePlatform.Response.ok("ssh.service loaded active running"));
      InitOperation init = new InitOperation(fake.build(), () -> Optional.of("jasperserver"));

      InitReport report = init.detect(Optional.of(install));
      Config config = init.toConfig(report);

      assertThat(report.detectedInstall()).isTrue();
      assertThat(config.server().baseUrl())
          .contains(URI.create("http://localhost:8081/jasperserver-pro"));
      assertThat(config.server().webappName()).contains(Config.WebappName.JASPERSERVER_PRO);
      assertThat(config.server().installDir()).contains(install.toAbsolutePath().normalize());
      assertThat(config.server().tomcatDir())
          .contains(install.toAbsolutePath().normalize().resolve("apache-tomcat"));
      assertThat(config.server().runAsUser()).contains("jasperserver");
      assertThat(config.service().kind()).contains(ServiceConfig.Kind.CTLSCRIPT);
      assertThat(config.service().scriptPath())
          .contains(install.toAbsolutePath().normalize().resolve("ctlscript.sh"));
      assertThat(config.database().type()).contains(Config.DatabaseType.POSTGRESQL);
      assertThat(config.database().url())
          .contains("jdbc:postgresql://db.example.internal:5433/jasperserver");
      assertThat(config.database().username()).contains("jasperdb");
      assertThat(config.database().passwordRef().map(r -> r.render()))
          .contains("env:JRS_DB_PASSWORD");
      assertThat(config.vendor().javaHome())
          .contains(install.toAbsolutePath().normalize().resolve("java"));
      assertThat(report.values())
          .anyMatch(v -> v.key().equals("server.baseUrl") && v.source().contains("conf/server.xml"))
          .anyMatch(
              v ->
                  v.key().equals("database.type")
                      && v.source().contains("default_master.properties"))
          .noneMatch(v -> v.value().contains("Sup3rSecret"));
    }
  }

  @Test
  void should_detect_windows_service_when_sc_query_lists_a_jasper_service() throws Exception {
    Path install = FakeLayout.windows(tmp.resolve("jrs"));
    try (FakeServices fake = FakeServices.in(tmp.resolve("home"), Platform.OsFamily.WINDOWS)) {
      fake.platform.on(
          List.of("sc.exe", "query"),
          FakePlatform.Response.ok(
              "SERVICE_NAME: Spooler",
              "DISPLAY_NAME: Print Spooler",
              "SERVICE_NAME: jasperreportsTomcat",
              "DISPLAY_NAME: JasperReports Server Tomcat"));
      InitOperation init = new InitOperation(fake.build(), Optional::empty);

      InitReport report = init.detect(Optional.of(install));
      Config config = report.config();

      assertThat(config.service().kind()).contains(ServiceConfig.Kind.WINDOWS_SERVICE);
      assertThat(config.service().name()).contains("jasperreportsTomcat");
      assertThat(config.server().baseUrl())
          .contains(URI.create("http://localhost:8080/jasperserver-pro"));
      assertThat(config.server().tomcatDir())
          .contains(install.toAbsolutePath().normalize().resolve("tomcat"));
      assertThat(config.server().runAsUser()).isEmpty();
      assertThat(fake.platform.invocations).anyMatch(c -> c.get(0).equals("sc.exe"));
    }
  }

  @Test
  void should_fall_back_to_manual_when_no_service_or_script_exists() throws Exception {
    Path install = tmp.resolve("bare");
    Files.createDirectories(install.resolve("tomcat").resolve("webapps").resolve("jasperserver"));
    try (FakeServices fake = FakeServices.in(tmp.resolve("home"), Platform.OsFamily.LINUX)) {
      InitOperation init = new InitOperation(fake.build(), Optional::empty);

      Config config = init.detect(Optional.of(install)).config();

      assertThat(config.service().kind()).contains(ServiceConfig.Kind.MANUAL);
      assertThat(config.server().webappName()).contains(Config.WebappName.JASPERSERVER);
      assertThat(config.database().type()).isEmpty();
      assertThat(config.server().baseUrl())
          .contains(URI.create("http://localhost:8080/jasperserver"));
    }
  }

  @Test
  void should_report_nothing_detected_when_hint_has_no_layout() throws Exception {
    try (FakeServices fake = FakeServices.in(tmp.resolve("home"))) {
      InitOperation init = new InitOperation(fake.build(), Optional::empty);

      InitReport report = init.detect(Optional.of(tmp.resolve("missing")));

      assertThat(report.detectedInstall()).isFalse();
      assertThat(report.values()).anyMatch(v -> v.value().equals("(not detected)"));
    }
  }

  @Test
  void should_use_platform_candidates_when_no_hint_given() throws Exception {
    Path install = FakeLayout.linux(tmp.resolve("jrs"));
    try (FakeServices fake = FakeServices.in(tmp.resolve("home"))) {
      fake.platform.candidates.add(tmp.resolve("not-an-install"));
      fake.platform.candidates.add(install);
      InitOperation init = new InitOperation(fake.build(), Optional::empty);

      InitReport report = init.detect(Optional.empty());

      assertThat(report.detectedInstall()).isTrue();
      assertThat(report.values())
          .anyMatch(v -> v.key().equals("server.installDir") && v.source().contains("candidate"));
    }
  }

  @Test
  void should_write_a_schema_valid_config_and_refuse_to_overwrite_without_force() throws Exception {
    Path install = FakeLayout.linux(tmp.resolve("jrs"));
    try (FakeServices fake = FakeServices.in(tmp.resolve("home"))) {
      InitOperation init = new InitOperation(fake.build(), Optional::empty);
      Config config = init.detect(Optional.of(install)).config();

      Path written = init.write(config, false);

      String yaml = Files.readString(written, StandardCharsets.UTF_8);
      assertThat(yaml).contains("webappName: jasperserver-pro").doesNotContain("Sup3rSecret");
      Config reloaded = new ConfigLoader().load(fake.home, Map.of(), Map.of());
      assertThat(reloaded).isEqualTo(config);
      assertThatThrownBy(() -> init.write(config, false))
          .isInstanceOf(FileAlreadyExistsException.class);
      assertThat(init.write(config, true)).isEqualTo(written);
    }
  }

  @Test
  void should_prefer_jasper_names_over_tomcat_names_when_picking_a_service() {
    assertThat(InitOperation.pickServiceName(List.of("Tomcat9", "jasperreportsTomcat")))
        .contains("jasperreportsTomcat");
    assertThat(InitOperation.pickServiceName(List.of("Spooler", "Tomcat9"))).contains("Tomcat9");
    assertThat(InitOperation.pickServiceName(List.of("Spooler"))).isEmpty();
  }
}
