package com.jaspersoft.jrsctl.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

class MainTest {

  @Test
  void should_print_banner_and_exit_zero_when_version_flag_given() {
    StringWriter out = new StringWriter();
    CommandLine cmd = new CommandLine(new JrsctlCommand());
    cmd.setOut(new java.io.PrintWriter(out));
    int code = cmd.execute("--version");
    assertThat(code).isZero();
    assertThat(out.toString()).contains("jrsctl").contains("Actian Jaspersoft");
  }

  @Test
  void should_exit_zero_when_selfcheck_passes() {
    StringWriter out = new StringWriter();
    CommandLine cmd = new CommandLine(new JrsctlCommand());
    cmd.setOut(new java.io.PrintWriter(out));
    int code = cmd.execute("selfcheck");
    assertThat(code).isZero();
    assertThat(out.toString()).contains("selfcheck ok");
  }

  @Test
  void should_emit_json_when_selfcheck_json_flag_given() {
    StringWriter out = new StringWriter();
    CommandLine cmd = new CommandLine(new JrsctlCommand());
    cmd.setOut(new java.io.PrintWriter(out));
    int code = cmd.execute("selfcheck", "--json");
    assertThat(code).isZero();
    assertThat(out.toString()).contains("\"items\"").contains("\"status\" : \"PASS\"");
  }

  @Test
  void should_exit_one_when_unknown_subcommand_given() {
    java.io.ByteArrayOutputStream err = new java.io.ByteArrayOutputStream();
    PrintStream saved = System.err;
    System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
    try {
      assertThat(Main.run("no-such-command")).isEqualTo(ExitCodes.USAGE);
    } finally {
      System.setErr(saved);
    }
  }
}
