package com.jaspersoft.jrsctl.core.selfcheck;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SelfCheckTest {

  @Test
  void should_pass_all_builtin_checks_when_running_on_the_build_jdk() {
    SelfCheck.Report report = new SelfCheck().run();
    assertThat(report.ok()).as(report.toString()).isTrue();
    assertThat(report.items())
        .extracting(SelfCheck.Item::name)
        .contains("runtime", "version", "config-schema");
  }

  @Test
  void should_report_a_failing_check_when_a_registered_check_throws() {
    SelfCheck.Report report =
        new SelfCheck()
            .add(
                () -> {
                  throw new IllegalStateException("boom");
                })
            .run();
    assertThat(report.ok()).isFalse();
    assertThat(report.items())
        .anyMatch(i -> i.status() == SelfCheck.Status.FAIL && i.detail().contains("boom"));
  }
}
