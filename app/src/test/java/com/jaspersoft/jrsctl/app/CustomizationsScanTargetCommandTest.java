package com.jaspersoft.jrsctl.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Issue #305, ADR-0057: {@code customizations scan --target-vendor} from the command line. */
class CustomizationsScanTargetCommandTest {

  @TempDir Path tmp;

  private Path home;
  private Path site;
  private Path source;
  private Path target;

  private static void write(Path root, String rel, String content) throws Exception {
    Path file = root.resolve(rel);
    Files.createDirectories(file.getParent());
    Files.writeString(file, content, StandardCharsets.UTF_8);
  }

  @BeforeEach
  void setUp() throws Exception {
    home = Files.createDirectories(tmp.resolve("home"));
    site = tmp.resolve("site/jasperserver-pro");
    source = tmp.resolve("8.2/jasperserver-pro");
    target = tmp.resolve("10.0/jasperserver-pro");
    write(source, "WEB-INF/merge.xml", "v1\n");
    write(source, "WEB-INF/carry.xml", "same\n");
    write(target, "WEB-INF/merge.xml", "v2\n");
    write(target, "WEB-INF/carry.xml", "same\n");
    write(site, "WEB-INF/merge.xml", "site\n");
    write(site, "WEB-INF/carry.xml", "site carry\n");
  }

  private InitCommandTest.Run scan(String... extra) {
    String[] base = {
      "customizations",
      "scan",
      "--vendor",
      source.toString(),
      "--installed",
      site.toString(),
      "--home",
      home.toString(),
      "--no-color"
    };
    String[] all = new String[base.length + extra.length];
    System.arraycopy(base, 0, all, 0, base.length);
    System.arraycopy(extra, 0, all, base.length, extra.length);
    return InitCommandTest.run(all);
  }

  @Test
  void should_list_counts_and_classes_when_scanning_against_a_target() {
    InitCommandTest.Run r = scan("--target-vendor", target.toString());

    assertThat(r.code()).as(r.out() + r.err()).isZero();
    assertThat(r.out()).contains("1 CARRY, 1 MERGE");
    assertThat(r.out()).contains("CARRY: re-apply as is").contains("MERGE: the vendor changed it");
    assertThat(r.out()).contains("WEB-INF/merge.xml");
  }

  @Test
  void should_write_the_merge_inputs_and_name_them_when_out_is_given() {
    Path out = tmp.resolve("merge");

    InitCommandTest.Run r = scan("--target-vendor", target.toString(), "--out", out.toString());

    assertThat(r.code()).as(r.out() + r.err()).isZero();
    assertThat(out.resolve("WEB-INF/merge.xml.theirs")).hasContent("v2");
    assertThat(r.out()).contains("git merge-file");
  }

  @Test
  void should_refuse_as_usage_when_out_or_register_do_not_fit_the_target_scan() {
    InitCommandTest.Run outAlone = scan("--out", tmp.resolve("merge").toString());
    InitCommandTest.Run register =
        InitCommandTest.run(
            "customizations",
            "scan",
            "--vendor",
            source.toString(),
            "--target-vendor",
            target.toString(),
            "--register",
            "--home",
            home.toString());

    assertThat(outAlone.code()).isEqualTo(ExitCodes.USAGE);
    assertThat(outAlone.out() + outAlone.err()).contains("--out needs --target-vendor");
    assertThat(register.code()).isEqualTo(ExitCodes.USAGE);
    assertThat(register.out() + register.err()).contains("--register cannot be combined");
  }
}
