package com.jaspersoft.jrsctl.jrs.strategy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Issue #263: what {@code export record} learns from an archive's {@code index.xml}. The shape is
 * the vendor's own, taken from the minimal catalog a 10.0.0 distribution ships under {@code
 * buildomatic/install_resources/export}.
 */
class ExportIndexTest {

  @TempDir Path tmp;

  private static final String EVERYTHING =
      "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
          + "<export><property name=\"keyalias\" value=\"e0ddc2e1-7ba1-4e76-a8fa-901aa34a458e\"/>"
          + "<module id=\"tenants\"><organization>organizations</organization>"
          + "<organization>organization_1</organization></module>"
          + "<module id=\"authority\"><user tenant=\"organization_1\">jasperadmin</user>"
          + "<user>superuser</user><role>ROLE_USER</role></module>"
          + "<module id=\"repositoryResources\"><folder>/</folder></module>"
          + "<module id=\"calendars\"><calendar>New Years Days</calendar></module>"
          + "<module id=\"reportJobs\"/><module id=\"audit\"/><module id=\"reportMonitoring\"/>"
          + "<module id=\"favorites\"/>"
          + "<property name=\"pathProcessorId\" value=\"zip\"/>"
          + "<property name=\"rootTenantId\" value=\"organizations\"/>"
          + "<property name=\"jsVersion\" value=\"8.2.0 PRO\"/>"
          + "<property name=\"encrypted\" value=\"4A52534956\"/></export>";

  private static final String FOLDERS =
      "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
          + "<export><module id=\"repositoryResources\"><folder>/public/Samples</folder>"
          + "<resource>/public/one.jrxml</resource></module>"
          + "<property name=\"keyalias\" value=\"deprecatedImportExportEncSecret\"/></export>";

  private Path zip(String name, Map<String, String> entries) throws IOException {
    Path file = tmp.resolve(name);
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

  @Test
  void should_read_the_version_key_alias_uris_and_modules_of_a_vendor_export() throws Exception {
    Path archive = zip("full.zip", Map.of("resources/", "", "index.xml", EVERYTHING));

    ExportIndex index = ExportIndex.read(archive);

    assertThat(index.jsVersion()).contains("8.2.0 PRO");
    assertThat(index.version()).contains("8.2.0");
    assertThat(index.keyAlias()).contains("e0ddc2e1-7ba1-4e76-a8fa-901aa34a458e");
    assertThat(index.uris()).containsExactly("/");
    assertThat(index.modules())
        .contains("tenants", "authority", "repositoryResources", "reportJobs", "audit");
    assertThat(index.hasUsersRoles()).isTrue();
    assertThat(index.hasAuditEvents()).isTrue();
    assertThat(index.hasMonitoringEvents()).isTrue();
    assertThat(index.hasAccessEvents()).isFalse();
  }

  /** The root folder with users, roles and report jobs is what {@code --everything} produces. */
  @Test
  void should_treat_the_root_with_users_roles_and_jobs_as_a_full_server_export() throws Exception {
    assertThat(ExportIndex.read(zip("full.zip", Map.of("index.xml", EVERYTHING))).fullServer())
        .isTrue();
    assertThat(ExportIndex.read(zip("part.zip", Map.of("index.xml", FOLDERS))).fullServer())
        .isFalse();
  }

  @Test
  void should_list_folders_and_resources_sorted_and_read_a_legacy_key_alias() throws Exception {
    ExportIndex index = ExportIndex.read(zip("part.zip", Map.of("index.xml", FOLDERS)));

    assertThat(index.uris()).containsExactly("/public/Samples", "/public/one.jrxml");
    assertThat(index.keyAlias()).contains("deprecatedImportExportEncSecret");
    assertThat(index.version()).isEmpty();
    assertThat(index.hasUsersRoles()).isFalse();
  }

  @Test
  void should_refuse_an_archive_without_index_xml() throws Exception {
    Path archive = zip("none.zip", Map.of("resources/", ""));

    assertThatThrownBy(() -> ExportIndex.read(archive))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("index.xml");
  }

  @Test
  void should_refuse_a_file_that_is_not_a_zip() throws Exception {
    Path text = Files.writeString(tmp.resolve("not.zip"), "plain text");

    assertThatThrownBy(() -> ExportIndex.read(text))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("index.xml");
  }
}
