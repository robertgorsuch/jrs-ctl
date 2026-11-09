package com.jaspersoft.jrsctl.jrs.strategy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Issue #262: what an export archive holds, read from its own layout (the one {@code js-export}
 * writes and the vendor's catalog archives ship with): organisations, users and roles with their
 * organisation, folders by their {@code .folder.xml} marker, and resources by their {@code .xml}
 * descriptor, data files beside them left out.
 */
class ExportArchiveInventoryTest {

  @TempDir Path tmp;

  private static final List<String> CATALOG =
      List.of(
          "index.xml",
          "organizations/",
          "organizations/organizations.xml",
          "organizations/organization_1.xml",
          "users/",
          "users/superuser.xml",
          "users/anonymousUser.xml",
          "users/organization_1/",
          "users/organization_1/jasperadmin.xml",
          "users/organization_1/joeuser.xml",
          "roles/",
          "roles/ROLE_USER.xml",
          "roles/organization_1/ROLE_DEMO.xml",
          "resources/",
          "resources/public/",
          "resources/public/.folder.xml",
          "resources/public/Samples/",
          "resources/public/Samples/.folder.xml",
          "resources/public/Samples/AllAccounts.xml",
          "resources/public/Samples/AllAccounts_files/",
          "resources/public/Samples/AllAccounts_files/AllAccounts.data",
          "resources/organizations/organization_1/",
          "resources/organizations/organization_1/.folder.xml",
          "calendars/New Years Days.xml",
          "reportJobs/");

  private Path zip(List<String> entries) throws IOException {
    Path file = tmp.resolve("export.zip");
    try (OutputStream out = Files.newOutputStream(file);
        ZipOutputStream zip = new ZipOutputStream(out)) {
      for (String name : entries) {
        zip.putNextEntry(new ZipEntry(name));
        if (!name.endsWith("/")) {
          zip.write(("<" + name + "/>").getBytes(StandardCharsets.UTF_8));
        }
        zip.closeEntry();
      }
    }
    return file;
  }

  @Test
  void should_list_organisations_users_roles_folders_and_resources_from_the_archive_layout()
      throws Exception {
    ExportArchiveInventory inventory = ExportArchiveInventory.read(zip(CATALOG));

    assertThat(inventory.organizations()).containsExactly("organization_1");
    assertThat(inventory.users())
        .containsExactly(
            "anonymousUser", "jasperadmin|organization_1", "joeuser|organization_1", "superuser");
    assertThat(inventory.roles()).containsExactly("ROLE_DEMO|organization_1", "ROLE_USER");
    assertThat(inventory.folders())
        .containsExactly("/organizations/organization_1", "/public", "/public/Samples");
    assertThat(inventory.resources()).containsExactly("/public/Samples/AllAccounts");
  }

  @Test
  void should_refuse_an_archive_without_index_xml() throws Exception {
    Path archive = zip(List.of("resources/", "resources/public/.folder.xml"));

    assertThatThrownBy(() -> ExportArchiveInventory.read(archive))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("index.xml");
  }
}
