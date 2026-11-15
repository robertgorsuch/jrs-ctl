package com.jaspersoft.jrsctl.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Issue #306, ADR-0061: {@code customizations readiness} from the command line. */
class CustomizationsReadinessCommandTest {

  @TempDir Path tmp;

  @Test
  void should_group_findings_by_kind_and_say_that_no_finding_is_not_ready() throws Exception {
    Path module = Files.createDirectories(tmp.resolve("module/META-INF"));
    Files.writeString(
        module.resolve("spring.xml"),
        "<beans><bean class=\"javax.persistence.EntityManagerFactory\"/></beans>\n",
        StandardCharsets.UTF_8);
    Path home = Files.createDirectories(tmp.resolve("home"));

    InitCommandTest.Run r =
        InitCommandTest.run(
            "customizations",
            "readiness",
            "--target",
            "10.0",
            "--jar",
            tmp.resolve("module").toString(),
            "--home",
            home.toString(),
            "--no-color");

    assertThat(r.code()).as(r.out() + r.err()).isZero();
    assertThat(r.out())
        .contains("Readiness for 10.0: 1 file(s) checked")
        .contains("1 JAKARTA")
        .contains("JAKARTA: names javax.* classes Jakarta EE renamed")
        .contains("javax.persistence.EntityManagerFactory")
        .contains("library classes not looked up")
        .contains("No finding does not mean ready");
  }

  @Test
  void should_refuse_installed_without_vendor_as_usage() throws Exception {
    Path home = Files.createDirectories(tmp.resolve("home"));

    InitCommandTest.Run r =
        InitCommandTest.run(
            "customizations",
            "readiness",
            "--target",
            "10.0",
            "--installed",
            tmp.toString(),
            "--home",
            home.toString());

    assertThat(r.code()).isEqualTo(ExitCodes.USAGE);
    assertThat(r.out() + r.err()).contains("--installed needs --vendor");
  }
}
