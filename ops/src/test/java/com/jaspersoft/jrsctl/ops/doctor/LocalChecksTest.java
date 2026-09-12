package com.jaspersoft.jrsctl.ops.doctor;

import static org.assertj.core.api.Assertions.assertThat;

import com.jaspersoft.jrsctl.core.platform.TomcatLayout;
import com.jaspersoft.jrsctl.ops.FakeLayout;
import com.jaspersoft.jrsctl.ops.FakeServices;
import com.jaspersoft.jrsctl.ops.ReportItem;
import com.jaspersoft.jrsctl.ops.Services;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The doctor checks that need no server: what they report when the installation is sound, and what
 * they report when it is not. Each is read-only and each non-PASS item has to name the next action,
 * which is the part an operator actually uses.
 */
class LocalChecksTest {

  @TempDir Path tmp;

  @Test
  void should_warn_when_there_is_no_config_file_and_fail_when_it_has_no_base_url()
      throws Exception {
    try (FakeServices fake = FakeServices.in(tmp.resolve("home"))) {
      Services services = fake.build();

      ReportItem missing = LocalChecks.config(services);

      assertThat(missing.status()).isEqualTo(ReportItem.Status.WARN);
      assertThat(missing.remediation()).contains("jrsctl init");
    }
  }

  @Test
  void should_pass_the_disk_check_with_room_and_fail_it_without() throws Exception {
    try (FakeServices fake = FakeServices.in(tmp.resolve("disk"))) {
      fake.platform.freeSpace = 100L * LocalChecks.GIB;
      assertThat(LocalChecks.disk(fake.build()).status()).isEqualTo(ReportItem.Status.PASS);
    }
    try (FakeServices fake = FakeServices.in(tmp.resolve("full"))) {
      fake.platform.freeSpace = LocalChecks.DISK_FAIL_BYTES / 2;
      ReportItem item = LocalChecks.disk(fake.build());

      assertThat(item.status()).isEqualTo(ReportItem.Status.FAIL);
      assertThat(item.remediation()).contains("prune");
    }
    try (FakeServices fake = FakeServices.in(tmp.resolve("tight"))) {
      fake.platform.freeSpace = 2 * LocalChecks.GIB;
      assertThat(LocalChecks.disk(fake.build()).status()).isEqualTo(ReportItem.Status.WARN);
    }
  }

  @Test
  void should_skip_the_permission_and_vendor_checks_without_a_layout() throws Exception {
    try (FakeServices fake = FakeServices.in(tmp.resolve("nolayout"))) {
      Services services = fake.build();

      assertThat(LocalChecks.permissions(services, Optional.empty()).status())
          .isEqualTo(ReportItem.Status.SKIP);
      assertThat(LocalChecks.vendor(services, Optional.empty()).status())
          .isEqualTo(ReportItem.Status.SKIP);
      assertThat(LocalChecks.layout(services, Optional.empty()).status())
          .isEqualTo(ReportItem.Status.FAIL);
    }
  }

  @Test
  void should_report_the_vendor_scripts_and_notice_a_missing_one() throws Exception {
    Path install = FakeLayout.linux(tmp.resolve("jrs"));
    try (FakeServices fake = FakeServices.in(tmp.resolve("vendor"))) {
      Services services = fake.build();
      Optional<TomcatLayout> layout = services.platform().detectTomcat(install);
      assertThat(layout).isPresent();

      assertThat(LocalChecks.vendor(services, layout).status()).isEqualTo(ReportItem.Status.PASS);

      Files.delete(layout.get().buildomaticDir().orElseThrow().resolve("js-import.sh"));
      ReportItem missing = LocalChecks.vendor(services, layout);

      assertThat(missing.status()).isEqualTo(ReportItem.Status.FAIL);
      assertThat(missing.detail()).contains("js-import.sh");
      assertThat(missing.remediation()).contains("vendor scripts");
    }
  }

  @Test
  void should_report_a_free_lock_no_pending_runs_and_an_empty_snapshot_store() throws Exception {
    try (FakeServices fake = FakeServices.in(tmp.resolve("clean"))) {
      Services services = fake.build();

      assertThat(LocalChecks.lock(services).status()).isEqualTo(ReportItem.Status.PASS);
      assertThat(LocalChecks.runs(services).status()).isEqualTo(ReportItem.Status.PASS);
      assertThat(LocalChecks.snapshots(services).detail()).contains("0 snapshot");
      assertThat(LocalChecks.state(services).status()).isEqualTo(ReportItem.Status.PASS);
    }
  }

  @Test
  void should_warn_when_isolated_mode_is_paired_with_a_proxy() throws Exception {
    try (FakeServices fake = FakeServices.in(tmp.resolve("network"))) {
      Services services =
          fake.yaml(
                  """
                  network:
                    mode: isolated
                    proxy:
                      host: proxy.example.internal
                      port: 8080
                  """)
              .build();

      ReportItem item = LocalChecks.network(services);

      assertThat(item.status()).isEqualTo(ReportItem.Status.WARN);
      assertThat(item.remediation()).contains("network.proxy");
    }
  }

  @Test
  void should_report_a_human_readable_size() throws IOException {
    assertThat(LocalChecks.human(5L * LocalChecks.GIB)).isEqualTo("5.0 GB");
    assertThat(LocalChecks.human(3L << 20)).isEqualTo("3.0 MB");
    assertThat(LocalChecks.human(512)).isEqualTo("512 B");
  }
}
