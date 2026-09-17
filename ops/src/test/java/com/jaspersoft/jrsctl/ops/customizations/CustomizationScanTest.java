package com.jaspersoft.jrsctl.ops.customizations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jaspersoft.jrsctl.core.snapshot.SnapshotStore;
import com.jaspersoft.jrsctl.core.state.Customization;
import com.jaspersoft.jrsctl.ops.FakeLayout;
import com.jaspersoft.jrsctl.ops.FakeServices;
import com.jaspersoft.jrsctl.ops.Services;
import com.jaspersoft.jrsctl.ops.customizations.CustomizationOperations.Change;
import com.jaspersoft.jrsctl.ops.customizations.CustomizationOperations.Scan;
import com.jaspersoft.jrsctl.ops.customizations.CustomizationOperations.ScanEntry;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Issue #72: find the files an operator changed in the installed webapp by comparing it with the
 * vendor's untouched copy, instead of registering each one by hand.
 */
class CustomizationScanTest {

  @TempDir Path tmp;

  private FakeServices fake;
  private DefaultCustomizationOperations ops;
  private Path webapp;

  @BeforeEach
  void setUp() throws Exception {
    Path install = FakeLayout.linux(Files.createDirectories(tmp.resolve("jrs")));
    webapp = install.resolve("apache-tomcat").resolve("webapps").resolve("jasperserver-pro");
    fake = FakeServices.in(tmp.resolve("home"));
    fake.platform.realFiles = true;
    fake.yaml(
        """
        server:
          baseUrl: http://localhost:8080/jasperserver-pro
          webappName: jasperserver-pro
          installDir: %s
          tomcatDir: %s
        service:
          kind: manual
        """
            .formatted(slashes(install), slashes(install.resolve("apache-tomcat"))));
    Services services = fake.build();
    ops =
        new DefaultCustomizationOperations(
            services, new SnapshotStore(fake.home, services.platform().files(), fake.clock));
    installed("WEB-INF/classes/jasperserver.properties", "theme=custom\n");
    installed("WEB-INF/lib/foo.jar", "same jar");
    installed("scripts/extra.js", "added by the site");
    installed("WEB-INF/hibernate.properties", "dialect=site\n");
    installed("WEB-INF/logs/jasperserver.log", "log lines");
  }

  @AfterEach
  void tearDown() {
    fake.close();
  }

  private static String slashes(Path p) {
    return p.toAbsolutePath().toString().replace('\\', '/');
  }

  private void installed(String rel, String content) throws Exception {
    Path file = webapp.resolve(rel);
    Files.createDirectories(file.getParent());
    Files.writeString(file, content, StandardCharsets.UTF_8);
  }

  private static final Map<String, String> VENDOR =
      Map.of(
          "WEB-INF/classes/jasperserver.properties", "theme=default\n",
          "WEB-INF/lib/foo.jar", "same jar",
          "WEB-INF/lib/removed.jar", "gone from the site",
          "WEB-INF/hibernate.properties", "dialect=vendor\n");

  private Path vendorDir() throws Exception {
    Path dir = tmp.resolve("dist").resolve("jasperserver-pro");
    for (Map.Entry<String, String> e : VENDOR.entrySet()) {
      Path file = dir.resolve(e.getKey());
      Files.createDirectories(file.getParent());
      Files.writeString(file, e.getValue(), StandardCharsets.UTF_8);
    }
    return dir;
  }

  private Path vendorWar() throws Exception {
    Path war = Files.createDirectories(tmp.resolve("pkg")).resolve("jasperserver-pro.war");
    try (OutputStream out = Files.newOutputStream(war);
        ZipOutputStream zip = new ZipOutputStream(out)) {
      for (Map.Entry<String, String> e : VENDOR.entrySet()) {
        zip.putNextEntry(new ZipEntry(e.getKey()));
        zip.write(e.getValue().getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
      }
    }
    return war;
  }

  private static Map<String, ScanEntry> byPath(Scan scan) {
    return scan.entries().stream()
        .collect(Collectors.toMap(ScanEntry::relativePath, Function.identity()));
  }

  @Test
  void should_list_changed_added_removed_and_installer_files_against_an_unpacked_webapp()
      throws Exception {
    Scan scan = ops.scan(vendorDir().getParent());

    Map<String, ScanEntry> entries = byPath(scan);
    assertThat(entries.get("WEB-INF/classes/jasperserver.properties").change())
        .isEqualTo(Change.CHANGED);
    assertThat(entries.get("scripts/extra.js").change()).isEqualTo(Change.ADDED);
    assertThat(entries.get("WEB-INF/lib/removed.jar").change()).isEqualTo(Change.REMOVED);
    assertThat(entries.get("WEB-INF/hibernate.properties").change()).isEqualTo(Change.INSTALLER);
    assertThat(entries)
        .doesNotContainKey("WEB-INF/lib/foo.jar")
        .doesNotContainKey("WEB-INF/logs/jasperserver.log");
    assertThat(scan.entries()).allMatch(e -> !e.registered());
  }

  @Test
  void should_compare_against_the_war_without_unpacking_it() throws Exception {
    Scan scan = ops.scan(vendorWar());

    Map<String, ScanEntry> entries = byPath(scan);
    assertThat(entries.get("WEB-INF/classes/jasperserver.properties").change())
        .isEqualTo(Change.CHANGED);
    assertThat(entries.get("WEB-INF/lib/removed.jar").change()).isEqualTo(Change.REMOVED);
    assertThat(entries).doesNotContainKey("WEB-INF/lib/foo.jar");
    assertThat(tmp.resolve("pkg")).isDirectoryNotContaining("glob:**/WEB-INF");
  }

  @Test
  void should_register_changed_files_with_the_vendor_hash_as_original_and_added_ones_as_they_are()
      throws Exception {
    Scan scan = ops.scan(vendorWar());

    var registered = ops.registerScan(scan);

    assertThat(registered)
        .extracting(c -> webapp.relativize(c.path()).toString().replace('\\', '/'))
        .containsExactlyInAnyOrder("WEB-INF/classes/jasperserver.properties", "scripts/extra.js");
    Customization changed =
        registered.stream()
            .filter(c -> c.path().endsWith("jasperserver.properties"))
            .findFirst()
            .orElseThrow();
    assertThat(changed.originalSha256())
        .isEqualTo(
            fake.platform
                .files()
                .sha256(
                    Files.writeString(
                        tmp.resolve("vendor-copy"), "theme=default\n", StandardCharsets.UTF_8)));
    assertThat(ops.scan(vendorWar()).entries())
        .filteredOn(e -> e.change() == Change.CHANGED || e.change() == Change.ADDED)
        .allMatch(ScanEntry::registered);
    assertThat(ops.registerScan(ops.scan(vendorWar()))).as("nothing left to register").isEmpty();
  }

  @Test
  void should_report_installer_files_the_vendor_copy_lacks_as_installer_not_added()
      throws Exception {
    // a real 10.0.0 install has an installer-written keystore.init.properties the war lacks
    installed("WEB-INF/classes/keystore.init.properties", "ks=/opt/jrs\n");

    Map<String, ScanEntry> entries = byPath(ops.scan(vendorWar()));

    assertThat(entries.get("WEB-INF/classes/keystore.init.properties").change())
        .isEqualTo(Change.INSTALLER);
    assertThat(ops.registerScan(ops.scan(vendorWar())))
        .noneMatch(c -> c.path().endsWith("keystore.init.properties"));
  }

  @Test
  void should_ignore_backup_copies_an_operator_left_next_to_an_edited_file() throws Exception {
    installed("WEB-INF/js.config.properties.bak-2026-07-30", "backup");
    installed("WEB-INF/web.xml.bak", "backup");
    installed("WEB-INF/web.xml.orig", "backup");
    installed("scripts/extra.js~", "editor backup");

    Map<String, ScanEntry> entries = byPath(ops.scan(vendorWar()));

    assertThat(entries.keySet())
        .noneMatch(p -> p.contains(".bak") || p.endsWith(".orig") || p.endsWith("~"))
        .contains("scripts/extra.js");
  }

  @Test
  void should_refuse_a_vendor_path_that_holds_no_webapp() throws Exception {
    Path empty = Files.createDirectories(tmp.resolve("nothing"));

    assertThatThrownBy(() -> ops.scan(empty))
        .isInstanceOf(CustomizationException.class)
        .hasMessageContaining("jasperserver-pro");
    assertThat(Optional.of(empty)).isPresent();
  }
}
