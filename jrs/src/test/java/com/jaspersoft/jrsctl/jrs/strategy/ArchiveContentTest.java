package com.jaspersoft.jrsctl.jrs.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import com.jaspersoft.jrsctl.jrs.api.ResourceFields;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Issue #307, ADR-0060: the archive side of {@code import verify --content}. The descriptors are
 * trimmed copies of what a 10.0.0 REST export of {@code /public/Samples} holds.
 */
class ArchiveContentTest {

  @TempDir Path tmp;

  private static final String FILE_XML =
      """
      <?xml version="1.0" encoding="UTF-8"?>
      <fileResource exportedWithPermissions="false" dataFile="BombCandle.jpeg.data">
          <folder>/public/Images</folder>
          <name>BombCandle.jpeg</name>
          <version>1</version>
          <label>BombCandle.jpeg</label>
          <description></description>
          <creationDate>2026-09-17T07:42:27.412-07:00</creationDate>
          <fileType>img</fileType>
      </fileResource>
      """;

  private static final String REPORT_XML =
      """
      <?xml version="1.0" encoding="UTF-8"?>
      <reportUnit exportedWithPermissions="false">
          <folder>/public/Reports</folder>
          <name>Geo</name>
          <version>15</version>
          <label>01. Geographic Results</label>
          <description>Sample Report &amp; map</description>
          <mainReport>
              <localResource xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                  dataFile="main.data" xsi:type="fileResource">
                  <folder>/public/Reports/Geo_files</folder>
                  <name>main</name>
                  <label>Main</label>
              </localResource>
          </mainReport>
          <dataSource>
              <uri>/public/Data_Sources/Foodmart</uri>
          </dataSource>
          <inputControl>
              <localResource xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                  xsi:type="inputControl">
                  <folder>/public/Reports/Geo_files</folder>
                  <name>Country</name>
                  <label>Country</label>
                  <dataType>
                      <localResource xsi:type="dataType">
                          <folder>/public/Reports/Geo_files/Country_files</folder>
                          <name>text</name>
                      </localResource>
                  </dataType>
              </localResource>
          </inputControl>
          <inputControl>
              <uri>/public/Input_Controls/ProductFamily</uri>
          </inputControl>
      </reportUnit>
      """;

  private Path zip(Map<String, String> entries) throws Exception {
    Path file = tmp.resolve("export.zip");
    try (OutputStream out = Files.newOutputStream(file);
        ZipOutputStream zip = new ZipOutputStream(out)) {
      for (Map.Entry<String, String> e : entries.entrySet()) {
        zip.putNextEntry(new ZipEntry(e.getKey()));
        zip.write(e.getValue().getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
      }
    }
    return file;
  }

  private static String sha(String s) throws Exception {
    return HexFormat.of()
        .formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
  }

  private Path sample() throws Exception {
    Map<String, String> e = new LinkedHashMap<>();
    e.put("index.xml", "<export/>");
    e.put("resources/public/.folder.xml", "<folder/>");
    e.put("resources/public/Images/BombCandle.jpeg.xml", FILE_XML);
    e.put("resources/public/Images/BombCandle.jpeg.data", "jpeg bytes");
    e.put("resources/public/Reports/Geo.xml", REPORT_XML);
    e.put("resources/public/Reports/Geo_files/main.data", "<jasperReport/>");
    e.put("resources/public/Reports/Geo_files/Country.xml", "<inputControl/>");
    e.put("resources/public/Reports/jasper-002dui.xml", FILE_XML);
    e.put("resources/public/Reports/jasper-002dui.data", "dash");
    return zip(e);
  }

  @Test
  void should_hash_every_file_under_the_uri_its_entry_names() throws Exception {
    ArchiveContent content = ArchiveContent.read(sample(), uri -> true);

    assertThat(content.fileSha256())
        .containsEntry("/public/Images/BombCandle.jpeg", sha("jpeg bytes"))
        .containsEntry("/public/Reports/Geo_files/main", sha("<jasperReport/>"))
        .containsEntry("/public/Reports/jasper-ui", sha("dash"))
        .hasSize(3);
  }

  @Test
  void should_read_top_level_descriptors_with_their_references_and_skip_local_ones()
      throws Exception {
    ArchiveContent content = ArchiveContent.read(sample(), uri -> true);

    assertThat(content.descriptors())
        .containsOnlyKeys(
            "/public/Images/BombCandle.jpeg", "/public/Reports/Geo", "/public/Reports/jasper-ui");
    ArchiveContent.Descriptor file = content.descriptors().get("/public/Images/BombCandle.jpeg");
    assertThat(file.file()).isTrue();
    assertThat(file.fields())
        .isEqualTo(new ResourceFields("BombCandle.jpeg", "", Optional.empty(), List.of()));
    ArchiveContent.Descriptor report = content.descriptors().get("/public/Reports/Geo");
    assertThat(report.reportUnit()).isTrue();
    assertThat(report.fields())
        .isEqualTo(
            new ResourceFields(
                "01. Geographic Results",
                "Sample Report & map",
                Optional.of("/public/Data_Sources/Foodmart"),
                List.of(
                    "/public/Reports/Geo_files/Country", "/public/Input_Controls/ProductFamily")));
  }

  @Test
  void should_keep_only_what_the_filter_accepts() throws Exception {
    ArchiveContent content = ArchiveContent.read(sample(), uri -> uri.startsWith("/public/Images"));

    assertThat(content.fileSha256()).containsOnlyKeys("/public/Images/BombCandle.jpeg");
    assertThat(content.descriptors()).containsOnlyKeys("/public/Images/BombCandle.jpeg");
  }
}
