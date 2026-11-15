package com.jaspersoft.jrsctl.ops.exim;

import static org.assertj.core.api.Assertions.assertThat;

import com.jaspersoft.jrsctl.jrs.api.ResourceFields;
import com.jaspersoft.jrsctl.ops.ReportItem;
import com.jaspersoft.jrsctl.ops.ReportItem.Status;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Issue #307, ADR-0060: {@code import verify --content} compares every file by SHA-256 and the
 * label, description, and a report unit's data source and input controls, never versions or times.
 */
class ImportVerifyContentTest {

  @TempDir Path tmp;

  private EximFakeAdapter adapter;
  private EximFixture fx;

  private static final String IMAGE_XML =
      "<fileResource dataFile=\"a.jpeg.data\"><folder>/public/Images</folder><name>a.jpeg</name>"
          + "<version>1</version><label>a.jpeg</label><description></description>"
          + "<creationDate>2026-01-01T00:00:00</creationDate></fileResource>";

  private static final String REPORT_XML =
      "<reportUnit><folder>/public/Reports</folder><name>Geo</name><version>15</version>"
          + "<label>Geo</label><description>map</description>"
          + "<dataSource><uri>/public/DS/Foodmart</uri></dataSource>"
          + "<inputControl><uri>/public/IC/a</uri></inputControl></reportUnit>";

  private static final String VIEW_XML =
      "<adhocDataView><folder>/public/Views</folder><name>V</name><label>V</label>"
          + "<dataSource><uri>/public/DS/Foodmart</uri></dataSource>"
          + "<inputControl><uri>/public/Views/V_files/ic</uri></inputControl></adhocDataView>";

  @BeforeEach
  void setUp() throws IOException {
    adapter = new EximFakeAdapter();
    fx = new EximFixture(tmp, () -> adapter);
    adapter.trees.add(
        List.of(
            "/public",
            "/public/Images",
            "/public/Images/a.jpeg",
            "/public/Reports",
            "/public/Reports/Geo",
            "/public/Views",
            "/public/Views/V"));
    // the server as the import left it: same content, same fields, its own versions and times
    adapter.contents.put("/public/Images/a.jpeg", "jpeg");
    adapter.contents.put("/public/Reports/Geo_files/main", "<jasperReport/>");
    adapter.fields.put(
        "/public/Images/a.jpeg", new ResourceFields("a.jpeg", "", Optional.empty(), List.of()));
    adapter.fields.put(
        "/public/Reports/Geo",
        new ResourceFields(
            "Geo", "map", Optional.of("/public/DS/Foodmart"), List.of("/public/IC/a")));
    // REST lists no input controls for an Ad Hoc view (verified on 10.0.0)
    adapter.fields.put("/public/Views/V", new ResourceFields("V", "", Optional.empty(), List.of()));
  }

  @AfterEach
  void tearDown() {
    fx.close();
  }

  private Path archive() throws IOException {
    Map<String, String> e = new LinkedHashMap<>();
    e.put("index.xml", "<export/>");
    e.put("resources/public/.folder.xml", "<folder/>");
    e.put("resources/public/Images/.folder.xml", "<folder/>");
    e.put("resources/public/Images/a.jpeg.xml", IMAGE_XML);
    e.put("resources/public/Images/a.jpeg.data", "jpeg");
    e.put("resources/public/Reports/.folder.xml", "<folder/>");
    e.put("resources/public/Reports/Geo.xml", REPORT_XML);
    e.put("resources/public/Reports/Geo_files/main.data", "<jasperReport/>");
    e.put("resources/public/Views/.folder.xml", "<folder/>");
    e.put("resources/public/Views/V.xml", VIEW_XML);
    Path file = Files.createDirectories(tmp.resolve("in")).resolve("export.zip");
    try (OutputStream out = Files.newOutputStream(file);
        ZipOutputStream zip = new ZipOutputStream(out)) {
      for (Map.Entry<String, String> x : e.entrySet()) {
        zip.putNextEntry(new ZipEntry(x.getKey()));
        zip.write(x.getValue().getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
      }
    }
    return file;
  }

  private ImportVerifyReport verify(Optional<String> prefix) throws IOException {
    return new ImportVerification(fx.services)
        .verify(archive(), new ImportVerification.Options(true, prefix));
  }

  private static ReportItem content(ImportVerifyReport report) {
    return report.items().stream()
        .filter(i -> i.name().equals(ImportVerification.CONTENT))
        .findFirst()
        .orElseThrow();
  }

  @Test
  void should_pass_when_every_file_and_descriptor_matches_whatever_the_versions() throws Exception {
    ImportVerifyReport report = verify(Optional.empty());

    assertThat(content(report).status()).as(content(report).detail()).isEqualTo(Status.PASS);
    assertThat(content(report).detail()).startsWith("2 files and 3 descriptors identical");
    assertThat(report.content()).hasValueSatisfying(c -> assertThat(c.differences()).isEmpty());
    assertThat(report.exitCode()).isZero();
    assertThat(adapter.fieldRequests).contains("/public/Images/a.jpeg|true");
    assertThat(fx.services.home().nativeTemp()).isEmptyDirectory();
  }

  @Test
  void should_fail_naming_each_difference() throws Exception {
    adapter.contents.put("/public/Reports/Geo_files/main", "<jasperReport changed/>");
    adapter.contents.remove("/public/Images/a.jpeg");
    adapter.fields.put(
        "/public/Reports/Geo",
        new ResourceFields("Geo 2", "map", Optional.of("/public/DS/Other"), List.of()));

    ImportVerifyReport report = verify(Optional.empty());

    assertThat(content(report).status()).isEqualTo(Status.FAIL);
    assertThat(report.exitCode()).isEqualTo(ImportVerifyReport.EXIT_FAILED);
    assertThat(report.content().orElseThrow().differences())
        .extracting(d -> d.uri() + ": " + d.what())
        .containsExactly(
            "/public/Images/a.jpeg: not on the server",
            "/public/Reports/Geo: data source",
            "/public/Reports/Geo: input controls",
            "/public/Reports/Geo: label",
            "/public/Reports/Geo_files/main: content differs");
    assertThat(content(report).detail()).contains("5 difference(s)");
  }

  @Test
  void should_compare_only_under_the_uri_prefix() throws Exception {
    adapter.contents.put("/public/Reports/Geo_files/main", "changed");

    ImportVerifyReport report = verify(Optional.of("public/Images/"));

    assertThat(content(report).status()).isEqualTo(Status.PASS);
    assertThat(content(report).detail()).startsWith("1 file and 1 descriptor under /public/Images");
  }

  @Test
  void should_skip_when_nothing_is_under_the_prefix() throws Exception {
    ImportVerifyReport report = verify(Optional.of("/public/Nothing"));

    assertThat(content(report).status()).isEqualTo(Status.SKIP);
  }

  /** A refusal on one resource is recorded for it, and the comparison goes on. */
  @Test
  void should_record_each_resource_the_server_refuses_and_go_on() throws Exception {
    Path archive = archive();
    adapter.serverUp = () -> false;

    ImportVerifyReport report =
        new ImportVerification(fx.services)
            .verify(archive, new ImportVerification.Options(true, Optional.empty()));

    assertThat(content(report).status()).isEqualTo(Status.FAIL);
    assertThat(report.content().orElseThrow().differences())
        .hasSize(5)
        .allSatisfy(d -> assertThat(d.what()).startsWith("could not be read: connection refused"));
  }

  @Test
  void should_add_no_content_item_without_the_option() throws Exception {
    ImportVerifyReport report = new ImportVerification(fx.services).verify(archive());

    assertThat(report.items()).noneMatch(i -> i.name().equals(ImportVerification.CONTENT));
    assertThat(report.content()).isEmpty();
  }
}
