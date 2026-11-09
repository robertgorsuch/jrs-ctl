package com.jaspersoft.jrsctl.ops.exim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jaspersoft.jrsctl.ops.ReportItem;
import com.jaspersoft.jrsctl.ops.ReportItem.Status;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Issue #262: after an import into a fresh server the operator has to prove nothing was skipped.
 * {@code import verify} reads what the archive holds and asks the server, over REST, whether every
 * organisation, user, role, folder and resource is there; a read-only report, exit 2 when anything
 * is missing.
 */
class ImportVerifyTest {

  @TempDir Path tmp;

  private EximFakeAdapter adapter;
  private EximFixture fx;

  private static final List<String> CATALOG =
      List.of(
          "index.xml",
          "organizations/organizations.xml",
          "organizations/organization_1.xml",
          "users/superuser.xml",
          "users/organization_1/joeuser.xml",
          "roles/ROLE_USER.xml",
          "roles/organization_1/ROLE_DEMO.xml",
          "resources/public/.folder.xml",
          "resources/public/Samples/.folder.xml",
          "resources/public/Samples/AllAccounts.xml",
          "resources/public/Samples/AllAccounts_files/AllAccounts.data",
          "resources/public/Samples/Missing.xml");

  @BeforeEach
  void setUp() throws IOException {
    adapter = new EximFakeAdapter();
    fx = new EximFixture(tmp, () -> adapter);
    adapter.organizations.add("organization_1");
    adapter.users.addAll(List.of("superuser", "joeuser|organization_1"));
    adapter.roles.addAll(List.of("ROLE_USER", "ROLE_DEMO|organization_1"));
    adapter.trees.add(
        List.of(
            "/public",
            "/public/Samples",
            "/public/Samples/AllAccounts",
            "/public/Samples/Missing"));
  }

  @AfterEach
  void tearDown() {
    fx.close();
  }

  private Path archive(List<String> entries) throws IOException {
    Path file = Files.createDirectories(tmp.resolve("in")).resolve("export.zip");
    try (OutputStream out = Files.newOutputStream(file);
        ZipOutputStream zip = new ZipOutputStream(out)) {
      for (String name : entries) {
        zip.putNextEntry(new ZipEntry(name));
        zip.write("<x/>".getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
      }
    }
    return file;
  }

  private static Map<String, ReportItem> byName(ImportVerifyReport report) {
    return report.items().stream().collect(Collectors.toMap(ReportItem::name, Function.identity()));
  }

  @Test
  void should_pass_every_item_when_the_server_holds_everything_the_archive_does()
      throws IOException {
    ImportVerifyReport report = new ImportVerification(fx.services).verify(archive(CATALOG));

    Map<String, ReportItem> items = byName(report);
    assertThat(items.keySet())
        .containsExactlyInAnyOrder("archive", "organizations", "users", "roles", "resources");
    assertThat(items.values()).allMatch(i -> i.status() == Status.PASS);
    assertThat(items.get("archive").detail())
        .contains("1 organisation")
        .contains("2 users")
        .contains("2 roles")
        .contains("2 folders")
        .contains("2 resources");
    assertThat(report.exitCode()).isEqualTo(0);
    assertThat(report.missing()).allSatisfy((kind, list) -> assertThat(list).isEmpty());
    assertThat(report.archiveCounts().resources()).isEqualTo(2);
  }

  @Test
  void should_fail_the_items_whose_entries_the_server_lacks_and_name_them() throws IOException {
    adapter.users.remove("joeuser|organization_1");
    adapter.trees.clear();
    adapter.trees.add(List.of("/public", "/public/Samples", "/public/Samples/AllAccounts"));

    ImportVerifyReport report = new ImportVerification(fx.services).verify(archive(CATALOG));

    Map<String, ReportItem> items = byName(report);
    assertThat(items.get("users").status()).isEqualTo(Status.FAIL);
    assertThat(items.get("users").detail()).contains("joeuser|organization_1");
    assertThat(items.get("resources").status()).isEqualTo(Status.FAIL);
    assertThat(items.get("resources").detail()).contains("/public/Samples/Missing");
    assertThat(items.get("roles").status()).isEqualTo(Status.PASS);
    assertThat(items.get("organizations").status()).isEqualTo(Status.PASS);
    assertThat(report.exitCode()).isEqualTo(2);
    assertThat(report.missing().get("users")).containsExactly("joeuser|organization_1");
    assertThat(report.missing().get("resources")).containsExactly("/public/Samples/Missing");
  }

  @Test
  void should_skip_the_items_an_archive_has_nothing_for() throws IOException {
    ImportVerifyReport report =
        new ImportVerification(fx.services)
            .verify(archive(List.of("index.xml", "resources/public/.folder.xml")));

    Map<String, ReportItem> items = byName(report);
    assertThat(items.get("organizations").status()).isEqualTo(Status.SKIP);
    assertThat(items.get("users").status()).isEqualTo(Status.SKIP);
    assertThat(items.get("roles").status()).isEqualTo(Status.SKIP);
    assertThat(items.get("resources").status()).isEqualTo(Status.PASS);
    assertThat(report.exitCode()).isEqualTo(0);
  }

  @Test
  void should_refuse_an_archive_that_is_not_an_export() throws IOException {
    Path archive = archive(List.of("resources/public/.folder.xml"));

    assertThatThrownBy(() -> new ImportVerification(fx.services).verify(archive))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("index.xml");
  }

  @Test
  void should_fail_the_resources_item_when_the_server_cannot_be_listed() throws IOException {
    adapter.listFailure = java.util.Optional.of("listing refused");

    ImportVerifyReport report = new ImportVerification(fx.services).verify(archive(CATALOG));

    assertThat(byName(report).get("resources").status()).isEqualTo(Status.FAIL);
    assertThat(byName(report).get("resources").detail()).contains("listing refused");
    assertThat(report.exitCode()).isEqualTo(2);
  }
}
