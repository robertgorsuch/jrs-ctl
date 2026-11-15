package com.jaspersoft.jrsctl.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.jaspersoft.jrsctl.core.redact.Redactor;
import com.jaspersoft.jrsctl.ops.ReportItem;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;

/** Issue #308, ADR-0058: the JUnit XML a report becomes. */
class JunitReportTest {

  private static final List<ReportItem> ITEMS =
      List.of(
          ReportItem.fail("server", "no answer from <host> & \"port\"", "start it"),
          ReportItem.warn("disk", "low\u0001 space", "free some"),
          ReportItem.pass("auth", "logged in"),
          ReportItem.skip("identity", "server failed", "fix server first"));

  private static Document parse(String xml) throws Exception {
    return DocumentBuilderFactory.newInstance()
        .newDocumentBuilder()
        .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
  }

  @Test
  void should_write_well_formed_xml_with_one_testcase_per_item_and_counts() throws Exception {
    String xml = JunitReport.render("import verify", ITEMS, false, Redactor.global());

    Document doc = parse(xml);
    assertThat(doc.getElementsByTagName("testcase").getLength()).isEqualTo(4);
    org.w3c.dom.Element suite = (org.w3c.dom.Element) doc.getElementsByTagName("testsuite").item(0);
    assertThat(suite.getAttribute("name")).isEqualTo("jrsctl import verify");
    assertThat(suite.getAttribute("failures")).isEqualTo("1");
    assertThat(suite.getAttribute("skipped")).isEqualTo("1");
    assertThat(xml).contains("classname=\"jrsctl.import.verify\"");
    assertThat(xml).contains("no answer from &lt;host&gt; &amp; &quot;port&quot;");
    assertThat(xml).doesNotContain("\u0001");
  }

  @Test
  void should_count_a_warn_as_a_failure_only_when_warn_fails() throws Exception {
    Document doc = parse(JunitReport.render("doctor", ITEMS, true, Redactor.global()));

    org.w3c.dom.Element suite = (org.w3c.dom.Element) doc.getElementsByTagName("testsuite").item(0);
    assertThat(suite.getAttribute("failures")).isEqualTo("2");
    assertThat(doc.getElementsByTagName("failure").getLength()).isEqualTo(2);
  }
}
