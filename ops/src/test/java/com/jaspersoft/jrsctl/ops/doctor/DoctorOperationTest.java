package com.jaspersoft.jrsctl.ops.doctor;

import static org.assertj.core.api.Assertions.assertThat;

import com.jaspersoft.jrsctl.core.state.AuditEntry;
import com.jaspersoft.jrsctl.jrs.api.Capability;
import com.jaspersoft.jrsctl.jrs.api.KeystoreInfo;
import com.jaspersoft.jrsctl.ops.FakeLayout;
import com.jaspersoft.jrsctl.ops.FakeServices;
import com.jaspersoft.jrsctl.ops.ReportItem;
import com.jaspersoft.jrsctl.ops.ReportItem.Status;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DoctorOperationTest {

  @TempDir Path tmp;

  private static final List<String> SERVER_DEPENDENT =
      List.of("auth", "identity", "compat", "capabilities", "keystore", "vendor-java");

  private String healthyYaml(Path install) {
    return """
        server:
          baseUrl: http://localhost:8081/jasperserver-pro
          webappName: jasperserver-pro
          installDir: %s
          runAsUser: jasperserver
          auth:
            mode: basic
            username: jasperadmin
            passwordRef: env:JRS_PASSWORD
        service:
          kind: systemd
          name: jasperreports
        network:
          mode: public
        """
        .formatted(install.toString().replace("\\", "/"));
  }

  private static Map<String, ReportItem> byName(DoctorReport report) {
    return report.items().stream().collect(Collectors.toMap(ReportItem::name, Function.identity()));
  }

  @Test
  void should_pass_every_check_when_server_and_layout_are_healthy() throws Exception {
    Path install = FakeLayout.linux(tmp.resolve("jrs"));
    try (FakeServices fake = FakeServices.in(tmp.resolve("home")).yaml(healthyYaml(install))) {
      DoctorReport report = new DoctorOperation(fake.build()).run(DoctorOptions.DEFAULT);

      Map<String, ReportItem> items = byName(report);
      assertThat(items.keySet()).containsExactlyInAnyOrderElementsOf(DoctorOperation.CHECKS);
      assertThat(report.exitCode()).as(report.items().toString()).isEqualTo(0);
      assertThat(items.get("server").status()).isEqualTo(Status.PASS);
      assertThat(items.get("auth").status()).isEqualTo(Status.PASS);
      assertThat(items.get("identity").detail()).contains("8.2.0").contains("PRO");
      assertThat(items.get("compat").status()).isEqualTo(Status.PASS);
      assertThat(items.get("capabilities").status()).isEqualTo(Status.PASS);
      assertThat(items.get("layout").status()).isEqualTo(Status.PASS);
      assertThat(items.get("service").status()).isEqualTo(Status.PASS);
      assertThat(items.get("permissions").status()).isEqualTo(Status.PASS);
      assertThat(items.get("disk").status()).isEqualTo(Status.PASS);
      assertThat(items.get("keystore").status()).isEqualTo(Status.PASS);
      assertThat(items.get("vendor").status()).isEqualTo(Status.PASS);
      assertThat(items.get("vendor-java").status()).isEqualTo(Status.SKIP);
      assertThat(items.get("tomcat").status()).isEqualTo(Status.SKIP);
      assertThat(items.get("database").status()).isEqualTo(Status.SKIP);
      assertThat(items.get("runs").status()).isEqualTo(Status.PASS);
      assertThat(items.get("lock").status()).isEqualTo(Status.PASS);
      assertThat(items.get("snapshots").status()).isEqualTo(Status.PASS);
      assertThat(items.get("network").status()).isEqualTo(Status.PASS);
      assertThat(items.get("secrets").status()).isEqualTo(Status.PASS);
      assertThat(items.get("secrets").detail()).doesNotContain("s3cret-pass");
      assertThat(report.items().stream().map(i -> i.status().rank()))
          .as("sorted FAIL, WARN, PASS, SKIP")
          .isSorted();
      assertThat(fake.adapter.calls).contains("identity", "login jasperadmin", "keystore");
    }
  }

  /**
   * Issue #68: from a machine that only reaches the server over REST, the checks of a local
   * installation are skipped and say why, instead of failing and pointing at jrsctl init.
   */
  /** Review §2.1: the running Tomcat against the platform sheet; the fixture's server is 8.2.0. */
  @Test
  void should_judge_the_tomcat_version_against_the_matrix_when_it_can_be_read() throws Exception {
    Path install = FakeLayout.linux(tmp.resolve("jrs"));
    Path tomcat = install.resolve("apache-tomcat");
    // one Services per home: a second build in the same home keeps the state db open
    Files.writeString(tomcat.resolve("RELEASE-NOTES"), "Apache Tomcat Version 9.0.85\n");
    try (FakeServices fake = FakeServices.in(tmp.resolve("home-9")).yaml(healthyYaml(install))) {
      ReportItem certified =
          byName(new DoctorOperation(fake.build()).run(DoctorOptions.DEFAULT)).get("tomcat");
      assertThat(certified.status()).isEqualTo(Status.PASS);
      assertThat(certified.detail()).contains("Tomcat 9.0.85").contains("certified");
    }

    Files.writeString(tomcat.resolve("RELEASE-NOTES"), "Apache Tomcat Version 10.1.24\n");
    try (FakeServices fake = FakeServices.in(tmp.resolve("home-10")).yaml(healthyYaml(install))) {
      ReportItem jakarta =
          byName(new DoctorOperation(fake.build()).run(DoctorOptions.DEFAULT)).get("tomcat");
      assertThat(jakarta.status()).isEqualTo(Status.FAIL);
      assertThat(jakarta.detail())
          .contains("Tomcat 10.1.24")
          .contains("not certified for JRS 8.2.0");
    }
  }

  @Test
  void should_skip_the_local_installation_checks_when_the_configuration_names_no_installation()
      throws Exception {
    String remote =
        """
        server:
          baseUrl: http://localhost:8081/jasperserver-pro
          auth:
            mode: basic
            username: jasperadmin
            passwordRef: env:JRS_PASSWORD
        network:
          mode: public
        """;
    try (FakeServices fake = FakeServices.in(tmp.resolve("home")).yaml(remote)) {
      DoctorReport report = new DoctorOperation(fake.build()).run(DoctorOptions.DEFAULT);

      Map<String, ReportItem> items = byName(report);
      assertThat(report.exitCode()).as(report.items().toString()).isEqualTo(0);
      for (String local :
          List.of("layout", "service", "service-manager", "permissions", "keystore", "vendor")) {
        assertThat(items.get(local).status()).as(local).isEqualTo(Status.SKIP);
        assertThat(items.get(local).detail()).as(local).contains("no local installation");
      }
      assertThat(items.get("server").status()).isEqualTo(Status.PASS);
      assertThat(items.get("auth").status()).isEqualTo(Status.PASS);
    }
  }

  /**
   * Issue #73: with the database settings read from default_master.properties, an installation
   * whose operator never set a database password must not fail doctor, or the upgrade preflight
   * that runs it; the database is needed only for hotfixes with SQL.
   */
  @Test
  void should_skip_the_database_check_when_no_password_reference_is_configured() throws Exception {
    Path install = FakeLayout.linux(tmp.resolve("jrs"));
    String yaml =
        healthyYaml(install)
            + """
            database:
              type: postgresql
              url: jdbc:postgresql://db.example.internal:5433/jasperserver
              username: jasperdb
            """;
    try (FakeServices fake = FakeServices.in(tmp.resolve("home")).yaml(yaml)) {
      DoctorReport report = new DoctorOperation(fake.build()).run(DoctorOptions.DEFAULT);

      ReportItem database = byName(report).get("database");
      assertThat(database.status()).isEqualTo(Status.SKIP);
      assertThat(database.detail()).contains("database.passwordRef");
      assertThat(report.exitCode()).as(report.items().toString()).isZero();
    }
  }

  /** Issue #73: config.yaml overriding buildomatic's database settings with other values. */
  @Test
  void should_name_database_values_that_disagree_with_default_master_properties() throws Exception {
    Path install = FakeLayout.linux(tmp.resolve("jrs"));
    String yaml =
        healthyYaml(install)
            + """
            database:
              type: postgresql
              url: jdbc:postgresql://db.example.internal:5433/jasperserver
              username: reporting
              passwordRef: env:JRS_PASSWORD
            """;
    try (FakeServices fake = FakeServices.in(tmp.resolve("home")).yaml(yaml)) {
      DoctorReport report = new DoctorOperation(fake.build()).run(DoctorOptions.DEFAULT);

      ReportItem database = byName(report).get("database");
      assertThat(database.status()).isNotEqualTo(Status.SKIP);
      assertThat(database.detail())
          .contains("database.username")
          .contains("reporting")
          .contains("jasperdb")
          .contains("default_master.properties");
      assertThat(database.remediation()).contains("remove database.username from config.yaml");
    }
  }

  /** Review finding 1.18: doctor runs the integrity check and names the way out. */
  @Test
  void should_fail_state_with_a_remediation_when_state_db_is_corrupt() throws Exception {
    Path install = FakeLayout.linux(tmp.resolve("jrs"));
    Path home = tmp.resolve("home");
    Path db = new com.jaspersoft.jrsctl.core.JrsctlHome(home).stateDb();
    try (com.jaspersoft.jrsctl.core.state.StateStore victim =
        com.jaspersoft.jrsctl.core.state.StateStore.open(db, java.time.Clock.systemUTC())) {
      for (int i = 0; i < 200; i++) {
        victim.audit("test", "fill", "row " + i + " " + "x".repeat(200));
      }
    }
    try (java.nio.channels.FileChannel ch =
        java.nio.channels.FileChannel.open(db, java.nio.file.StandardOpenOption.WRITE)) {
      // page headers of pages 2 to 4: an invalid page type fails quick_check whatever the page
      // holds, whereas unallocated space inside a page is not validated
      byte[] junk = new byte[64];
      java.util.Arrays.fill(junk, (byte) 0xFF);
      for (int page = 2; page <= 4; page++) {
        ch.write(java.nio.ByteBuffer.wrap(junk), (page - 1) * 4096L);
      }
    }
    try (FakeServices fake = FakeServices.in(home).yaml(healthyYaml(install))) {
      DoctorReport report = new DoctorOperation(fake.build()).run(DoctorOptions.DEFAULT);
      Map<String, ReportItem> items = byName(report);

      assertThat(items.get("state").status()).isEqualTo(ReportItem.Status.FAIL);
      assertThat(items.get("state").detail()).contains("quick_check");
      assertThat(items.get("state").remediation()).contains("move").contains("state.db");
      assertThat(report.exitCode()).isNotZero();
    }
  }

  /**
   * Found against a real 10.0.0 server: with a wrong password the server answers every call with
   * 401, and doctor called that "unexpected RestException ... check server.baseUrl", pointing the
   * operator at the address instead of the credentials.
   */
  @Test
  void should_name_the_refused_user_and_the_credentials_when_the_server_answers_401()
      throws Exception {
    Path install = FakeLayout.linux(tmp.resolve("jrs"));
    try (FakeServices fake = FakeServices.in(tmp.resolve("home")).yaml(healthyYaml(install))) {
      fake.adapter.identityFailure =
          java.util.Optional.of(
              new com.jaspersoft.jrsctl.jrs.rest.RestException(
                  401,
                  "GET",
                  "/rest_v2/serverInfo",
                  "HTTP 401 from GET /rest_v2/serverInfo: <!doctype html><html>Unauthorized"));

      DoctorReport report = new DoctorOperation(fake.build()).run(DoctorOptions.DEFAULT);

      ReportItem server = byName(report).get("server");
      assertThat(server.status()).isEqualTo(Status.FAIL);
      assertThat(server.detail())
          .contains("refused the login of jasperadmin")
          .contains("401")
          .doesNotContain("<html")
          .doesNotContain("unexpected");
      assertThat(server.remediation())
          .contains("server.auth.passwordRef")
          .doesNotContain("baseUrl");
      assertThat(byName(report).get("auth").detail()).isEqualTo("server refused the credentials");
    }
  }

  @Test
  void should_fail_server_and_skip_dependents_when_server_unreachable() throws Exception {
    Path install = FakeLayout.linux(tmp.resolve("jrs"));
    try (FakeServices fake = FakeServices.in(tmp.resolve("home")).yaml(healthyYaml(install))) {
      fake.unreachable = true;

      DoctorReport report = new DoctorOperation(fake.build()).run(DoctorOptions.DEFAULT);

      Map<String, ReportItem> items = byName(report);
      assertThat(items.get("server").status()).isEqualTo(Status.FAIL);
      assertThat(items.get("server").remediation()).contains("server.baseUrl");
      for (String name : SERVER_DEPENDENT) {
        assertThat(items.get(name).status()).as(name).isEqualTo(Status.SKIP);
        assertThat(items.get(name).detail()).as(name).isEqualTo("server unreachable");
      }
      assertThat(items.get("layout").status()).isEqualTo(Status.PASS);
      assertThat(report.exitCode()).isEqualTo(2);
      assertThat(report.items().get(0).name()).isEqualTo("server");
    }
  }

  @Test
  void should_exit_6_when_the_only_failure_is_compat() throws Exception {
    Path install = FakeLayout.linux(tmp.resolve("jrs"));
    try (FakeServices fake = FakeServices.in(tmp.resolve("home")).yaml(healthyYaml(install))) {
      fake.adapter.identity = com.jaspersoft.jrsctl.ops.FakeJrsAdapter.identity("6.4.0");

      DoctorReport report = new DoctorOperation(fake.build()).run(DoctorOptions.DEFAULT);

      Map<String, ReportItem> items = byName(report);
      assertThat(items.get("compat").status()).isEqualTo(Status.FAIL);
      assertThat(items.get("compat").detail()).contains("6.4.0");
      assertThat(items.get("identity").status()).isEqualTo(Status.PASS);
      assertThat(report.exitCode()).as(report.items().toString()).isEqualTo(6);
    }
  }

  @Test
  void should_warn_and_audit_when_allow_unsupported_given() throws Exception {
    Path install = FakeLayout.linux(tmp.resolve("jrs"));
    try (FakeServices fake = FakeServices.in(tmp.resolve("home")).yaml(healthyYaml(install))) {
      fake.adapter.identity = com.jaspersoft.jrsctl.ops.FakeJrsAdapter.identity("6.4.0");

      DoctorReport report = new DoctorOperation(fake.build()).run(new DoctorOptions(true));

      Map<String, ReportItem> items = byName(report);
      assertThat(items.get("compat").status()).isEqualTo(Status.WARN);
      assertThat(report.exitCode()).as(report.items().toString()).isEqualTo(0);
      List<AuditEntry> audit = fake.stateStore().auditRows(10);
      assertThat(audit).anyMatch(a -> a.action().equals("--allow-unsupported"));
    }
  }

  @Test
  void should_warn_network_when_isolated_with_proxy() throws Exception {
    try (FakeServices fake =
        FakeServices.in(tmp.resolve("home"))
            .yaml(
                """
                server:
                  baseUrl: http://localhost:8080/jasperserver-pro
                network:
                  mode: isolated
                  proxy:
                    host: proxy.example
                    port: 3128
                """)) {
      DoctorReport report = new DoctorOperation(fake.build()).run(DoctorOptions.DEFAULT);

      Map<String, ReportItem> items = byName(report);
      assertThat(items.get("network").status()).isEqualTo(Status.WARN);
      assertThat(items.get("network").remediation()).contains("proxy");
      // no installation in this configuration: the local checks are skipped (#68)
      assertThat(items.get("layout").status()).isEqualTo(Status.SKIP);
      assertThat(items.get("permissions").status()).isEqualTo(Status.SKIP);
      assertThat(items.get("service").status()).isEqualTo(Status.SKIP);
      assertThat(items.get("auth").status()).isEqualTo(Status.FAIL);
      assertThat(report.exitCode()).isEqualTo(2);
    }
  }

  @Test
  void should_fail_secrets_when_env_var_missing_and_file_not_owner_only() throws Exception {
    Path secret = tmp.resolve("db.pass");
    Files.writeString(secret, "hunter2", StandardCharsets.UTF_8);
    try (FakeServices fake =
        FakeServices.in(tmp.resolve("home"))
            .yaml(
                """
                server:
                  baseUrl: http://localhost:8080/jasperserver-pro
                  auth:
                    username: jasperadmin
                    passwordRef: env:NOT_SET_ANYWHERE
                database:
                  type: postgresql
                  url: jdbc:postgresql://localhost/jrs
                  passwordRef: file:%s
                """
                    .formatted(secret.toString().replace("\\", "/")))) {
      fake.platform.ownerOnly = false;

      DoctorReport report = new DoctorOperation(fake.build()).run(DoctorOptions.DEFAULT);

      ReportItem secrets = byName(report).get("secrets");
      assertThat(secrets.status()).isEqualTo(Status.FAIL);
      assertThat(secrets.detail())
          .contains("NOT_SET_ANYWHERE")
          .contains("readable by other users")
          .doesNotContain("hunter2");
      assertThat(byName(report).get("database").status()).isEqualTo(Status.FAIL);
      assertThat(byName(report).get("database").detail()).contains("driver");
    }
  }

  @Test
  void should_warn_capabilities_and_fail_keystore_when_server_lacks_expected_ones()
      throws Exception {
    Path install = FakeLayout.linux(tmp.resolve("jrs"));
    try (FakeServices fake = FakeServices.in(tmp.resolve("home")).yaml(healthyYaml(install))) {
      fake.adapter.capabilities = EnumSet.of(Capability.EXPORT_ASYNC, Capability.IMPORT_ASYNC);
      fake.adapter.keystore = KeystoreInfo.absent("~jasperserver/.jrsks unreadable");

      DoctorReport report = new DoctorOperation(fake.build()).run(DoctorOptions.DEFAULT);

      Map<String, ReportItem> items = byName(report);
      assertThat(items.get("capabilities").status()).isEqualTo(Status.WARN);
      assertThat(items.get("capabilities").detail()).contains("missing").contains("REST_LOGIN");
      assertThat(items.get("keystore").status()).isEqualTo(Status.FAIL);
      assertThat(items.get("keystore").remediation()).contains("runAsUser");
    }
  }

  @Test
  void should_warn_service_when_kind_is_manual_and_fail_disk_when_below_1gb() throws Exception {
    Path install = FakeLayout.linux(tmp.resolve("jrs"));
    try (FakeServices fake =
        FakeServices.in(tmp.resolve("home"))
            .yaml(healthyYaml(install).replace("kind: systemd", "kind: manual"))) {
      fake.platform.freeSpace = 512L << 20;

      DoctorReport report = new DoctorOperation(fake.build()).run(DoctorOptions.DEFAULT);

      Map<String, ReportItem> items = byName(report);
      assertThat(items.get("service").status()).isEqualTo(Status.WARN);
      assertThat(items.get("service").remediation()).contains("interactive");
      assertThat(items.get("disk").status()).isEqualTo(Status.FAIL);
      assertThat(items.get("disk").detail()).contains("512.0 MB");
    }
  }
}
