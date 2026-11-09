package com.jaspersoft.jrsctl.ops.passwords;

import static org.assertj.core.api.Assertions.assertThat;

import com.jaspersoft.jrsctl.ops.ReportItem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** ADR-0053, spec §10.6: the read-only distribution report. */
class PasswordStatusTest {

  @TempDir Path tmp;

  private static Map<String, ReportItem> byName(PasswordStatusReport report) {
    return report.items().stream().collect(Collectors.toMap(ReportItem::name, Function.identity()));
  }

  @Test
  void should_warn_with_the_counts_when_users_are_still_on_legacy_passwords() throws Exception {
    try (PasswordFixture f = PasswordFixture.create(tmp)) {
      PasswordStatusReport report = f.ops().status();

      Map<String, ReportItem> items = byName(report);
      assertThat(report.exitCode()).as(report.toString()).isEqualTo(0);
      assertThat(items.get("vendor").status()).isEqualTo(ReportItem.Status.PASS);
      assertThat(items.get("password-config").status()).isEqualTo(ReportItem.Status.PASS);
      assertThat(items.get("strategy").status()).isEqualTo(ReportItem.Status.WARN);
      assertThat(items.get("strategy").detail()).contains("6 users: 0 modern, 6 legacy");
      assertThat(items.get("strategy").remediation()).contains("passwords migrate");
      assertThat(report.passwords()).contains(new PasswordCounts(6, 0, 6));
      assertThat(report.strategy()).contains("modern");
      assertThat(f.vendorLogText()).contains("detect-password-strategy").doesNotContain("migrate-");
    }
  }

  @Test
  void should_pass_when_the_migration_is_complete() throws Exception {
    try (PasswordFixture f = PasswordFixture.create(tmp)) {
      Files.writeString(f.migratedMarker, "migrated");

      PasswordStatusReport report = f.ops().status();

      assertThat(byName(report).get("strategy").status()).isEqualTo(ReportItem.Status.PASS);
      assertThat(byName(report).get("strategy").detail()).contains("complete");
      assertThat(report.passwords()).contains(new PasswordCounts(6, 5, 1));
    }
  }

  @Test
  void should_warn_on_the_settings_when_the_webapp_still_stores_passwords_the_legacy_way()
      throws Exception {
    try (PasswordFixture f = PasswordFixture.createWithStrategy(tmp, "legacy")) {
      PasswordStatusReport report = f.ops().status();

      assertThat(byName(report).get("password-config").status()).isEqualTo(ReportItem.Status.WARN);
      assertThat(report.strategy()).contains("legacy");
      assertThat(report.exitCode()).isEqualTo(0);
    }
  }

  @Test
  void should_fail_when_the_vendor_utility_reports_a_failed_build() throws Exception {
    try (PasswordFixture f = PasswordFixture.create(tmp)) {
      f.failTarget("detect-password-strategy");

      PasswordStatusReport report = f.ops().status();

      assertThat(byName(report).get("strategy").status()).isEqualTo(ReportItem.Status.FAIL);
      assertThat(report.exitCode()).isEqualTo(2);
      assertThat(report.passwords()).isEmpty();
    }
  }
}
