package com.jaspersoft.jrsctl.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Issue #369: the installer-file diffs of {@code customizations scan} carry the site's database
 * passwords; neither the JSON nor the text report may show them, and the JSON must still parse.
 */
class CustomizationsScanRedactionCommandTest {

  private static final String SITE_SECRET = "Site@Secret42";
  private static final String VENDOR_SECRET = "VendorCi9x";
  private static final String ESCAPE_SECRET = "EscapeXml77";

  @TempDir Path tmp;

  private Path home;
  private Path site;
  private Path vendor;

  private static void write(Path root, String rel, String content) throws Exception {
    Path file = root.resolve(rel);
    Files.createDirectories(file.getParent());
    Files.writeString(file, content, StandardCharsets.UTF_8);
  }

  private static String context(String user, String password, String url) {
    return "<Context path=\"/jasperserver-pro\">\n"
        + "  <Resource name=\"jdbc/jasperserver\" type=\"javax.sql.DataSource\"\n"
        + "      username=\""
        + user
        + "\" password=\""
        + password
        + "\"\n"
        + "      url=\""
        + url
        + "\" />\n"
        + "</Context>\n";
  }

  private static String jdbc(String url, String password) {
    return "metadata.jdbc.url="
        + url
        + "\n"
        + "metadata.jdbc.password="
        + password
        + "\n"
        + "metadata.jdbc.password.escapeXML="
        + password
        + "\n"
        + "# a comment after the secret\n";
  }

  @BeforeEach
  void setUp() throws Exception {
    home = Files.createDirectories(tmp.resolve("home"));
    site = tmp.resolve("site/jasperserver-pro");
    vendor = tmp.resolve("vendor/jasperserver-pro");
    write(
        vendor, "META-INF/context.xml", context("ci_agent", VENDOR_SECRET, "jdbc:postgresql://ci"));
    write(site, "META-INF/context.xml", context("postgres", SITE_SECRET, "jdbc:postgresql://db"));
    write(vendor, "WEB-INF/js.jdbc.properties", jdbc("jdbc:postgresql://ci", VENDOR_SECRET));
    write(site, "WEB-INF/js.jdbc.properties", jdbc("jdbc:postgresql://db", ESCAPE_SECRET));
  }

  private InitCommandTest.Run scan(String... extra) {
    String[] base = {
      "customizations",
      "scan",
      "--vendor",
      vendor.toString(),
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
  void should_mask_installer_diff_passwords_and_stay_valid_json_when_json_is_asked()
      throws Exception {
    InitCommandTest.Run run = scan("--json");

    assertThat(run.code()).as(run.err()).isZero();
    assertThat(run.out()).doesNotContain(SITE_SECRET, VENDOR_SECRET, ESCAPE_SECRET);
    JsonNode root = new ObjectMapper().readTree(run.out());
    String diffs = root.get("entries").findValuesAsText("diff").toString();
    assertThat(diffs).contains("[redacted]", "jdbc:postgresql://db", "a comment after the secret");
  }

  @Test
  void should_mask_installer_diff_passwords_when_the_report_is_text() {
    InitCommandTest.Run run = scan();

    assertThat(run.code()).as(run.err()).isZero();
    assertThat(run.out())
        .doesNotContain(SITE_SECRET, VENDOR_SECRET, ESCAPE_SECRET)
        .contains("[redacted]");
  }
}
