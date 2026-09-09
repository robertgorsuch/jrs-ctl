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
      assertThat(items.get("layout").status()).isEqualTo(Status.FAIL);
      assertThat(items.get("permissions").status()).isEqualTo(Status.SKIP);
      assertThat(items.get("service").status()).isEqualTo(Status.FAIL);
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
