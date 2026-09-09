package com.jaspersoft.jrsctl.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

/** Spec §14 Phase 8 "offline docs embedded": {@code jrsctl docs} lists and prints them. */
class DocsCommandTest {

  @Test
  void should_list_every_embedded_document_when_no_name_given() {
    StringWriter out = new StringWriter();
    CommandLine cmd = Main.commandLine();
    cmd.setOut(new PrintWriter(out));
    assertThat(cmd.execute("docs")).isZero();
    assertThat(out.toString())
        .contains("operator-guide")
        .contains("jrsctl operator guide")
        .contains("hotfix-authoring")
        .contains("security")
        .contains("readme");
  }

  @Test
  void should_emit_json_array_when_json_flag_given() {
    StringWriter out = new StringWriter();
    CommandLine cmd = Main.commandLine();
    cmd.setOut(new PrintWriter(out));
    assertThat(cmd.execute("docs", "--json")).isZero();
    String json = out.toString().trim();
    assertThat(json).startsWith("[").endsWith("]");
    assertThat(json).contains("\"name\" : \"operator-guide\"").contains("\"bytes\" :");
    assertThat(json).contains("\"title\" : \"jrsctl operator guide\"");
  }

  @Test
  void should_print_document_when_name_given() {
    StringWriter out = new StringWriter();
    CommandLine cmd = Main.commandLine();
    cmd.setOut(new PrintWriter(out));
    assertThat(cmd.execute("docs", "hotfix-authoring")).isZero();
    assertThat(out.toString())
        .startsWith("# jrsctl hotfix authoring guide")
        .contains("manifest.json");
  }

  @Test
  void should_exit_usage_and_list_names_when_name_unknown() {
    StringWriter err = new StringWriter();
    CommandLine cmd = Main.commandLine();
    cmd.setErr(new PrintWriter(err));
    assertThat(cmd.execute("docs", "no-such-doc")).isEqualTo(ExitCodes.USAGE);
    assertThat(err.toString()).contains("no-such-doc").contains("operator-guide");
  }

  @Test
  void should_report_size_of_embedded_document_when_described() {
    EmbeddedDocs.Doc doc = EmbeddedDocs.describe("security").orElseThrow();
    assertThat(doc.title()).isEqualTo("jrsctl security notes");
    assertThat(doc.bytes()).isGreaterThan(1000);
    assertThat(EmbeddedDocs.describe("nope")).isEmpty();
  }
}
