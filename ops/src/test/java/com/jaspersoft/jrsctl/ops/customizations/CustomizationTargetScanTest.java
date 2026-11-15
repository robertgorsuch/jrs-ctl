package com.jaspersoft.jrsctl.ops.customizations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jaspersoft.jrsctl.core.snapshot.SnapshotStore;
import com.jaspersoft.jrsctl.ops.FakeServices;
import com.jaspersoft.jrsctl.ops.Services;
import com.jaspersoft.jrsctl.ops.customizations.CustomizationOperations.Scan;
import com.jaspersoft.jrsctl.ops.customizations.CustomizationOperations.TargetChange;
import com.jaspersoft.jrsctl.ops.customizations.CustomizationOperations.TargetClass;
import com.jaspersoft.jrsctl.ops.customizations.CustomizationOperations.TargetEntry;
import com.jaspersoft.jrsctl.ops.customizations.CustomizationOperations.TargetScan;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Issue #305, ADR-0057: every site file classified against the target version's vendor webapp, and
 * the three-way merge inputs written for the files both sides changed.
 */
class CustomizationTargetScanTest {

  @TempDir Path tmp;

  private FakeServices fake;
  private DefaultCustomizationOperations ops;
  private Path site;

  /** The source version's vendor webapp. */
  private static final Map<String, String> SOURCE = new TreeMap<>();

  /** The target version's vendor webapp. */
  private static final Map<String, String> TARGET = new TreeMap<>();

  /** The site's webapp: the source vendor's files plus the site's changes. */
  private final Map<String, String> siteFiles = new TreeMap<>();

  static {
    SOURCE.put("WEB-INF/applicationContext-carry.xml", "vendor carry v1\n");
    SOURCE.put("WEB-INF/applicationContext-merge.xml", "<beans>\nvendor v1\n</beans>\n");
    SOURCE.put("WEB-INF/jsp/obsolete.jsp", "vendor obsolete v1\n");
    SOURCE.put("WEB-INF/js.quartz.properties", "quartz=vendor\n");
    SOURCE.put("scripts/runtime/main.js", "vendor js v1\n");
    SOURCE.put("WEB-INF/lib/deleted-by-site.jar", "vendor jar\n");
    SOURCE.put("WEB-INF/lib/untouched.jar", "same\n");

    TARGET.put("WEB-INF/applicationContext-carry.xml", "vendor carry v1\n");
    TARGET.put("WEB-INF/applicationContext-merge.xml", "<beans>\nvendor v2 jakarta\n</beans>\n");
    TARGET.put("WEB-INF/js.quartz.properties", "quartz=vendor2\n");
    TARGET.put("scripts/runtime/main.js", "vendor js v2\n");
    TARGET.put("WEB-INF/lib/deleted-by-site.jar", "vendor jar\n");
    TARGET.put("WEB-INF/lib/untouched.jar", "same, new build\n");
    TARGET.put("WEB-INF/classes/clash.properties", "vendor's new file\n");
  }

  @BeforeEach
  void setUp() throws Exception {
    fake = FakeServices.in(tmp.resolve("home"));
    fake.platform.realFiles = true;
    Services services = fake.build();
    ops =
        new DefaultCustomizationOperations(
            services, new SnapshotStore(fake.home, services.platform().files(), fake.clock));
    siteFiles.putAll(SOURCE);
    siteFiles.remove("WEB-INF/lib/deleted-by-site.jar");
    siteFiles.put("WEB-INF/applicationContext-carry.xml", "site carry\n");
    siteFiles.put("WEB-INF/applicationContext-merge.xml", "<beans>\nsite bean\n</beans>\n");
    siteFiles.put("WEB-INF/jsp/obsolete.jsp", "site obsolete\n");
    siteFiles.put("WEB-INF/js.quartz.properties", "quartz=site\n");
    siteFiles.put("scripts/runtime/main.js", "site js\n");
    siteFiles.put("WEB-INF/classes/own.properties", "site's own\n");
    siteFiles.put("WEB-INF/classes/clash.properties", "site's file\n");
    site = tree(tmp.resolve("site/jasperserver-pro"), siteFiles);
  }

  @AfterEach
  void tearDown() {
    fake.close();
  }

  private static Path tree(Path dir, Map<String, String> files) throws Exception {
    for (Map.Entry<String, String> e : files.entrySet()) {
      Path file = dir.resolve(e.getKey());
      Files.createDirectories(file.getParent());
      Files.writeString(file, e.getValue(), StandardCharsets.UTF_8);
    }
    return dir;
  }

  private static Path war(Path war, Map<String, String> files) throws Exception {
    Files.createDirectories(war.getParent());
    try (OutputStream out = Files.newOutputStream(war);
        ZipOutputStream zip = new ZipOutputStream(out)) {
      for (Map.Entry<String, String> e : files.entrySet()) {
        zip.putNextEntry(new ZipEntry(e.getKey()));
        zip.write(e.getValue().getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
      }
    }
    return war;
  }

  private static Map<String, TargetEntry> byPath(TargetScan t) {
    return t.entries().stream()
        .collect(Collectors.toMap(e -> e.entry().relativePath(), Function.identity()));
  }

  @Test
  void should_put_every_site_file_in_its_class_when_scanning_against_target_wars()
      throws Exception {
    Path source = war(tmp.resolve("8.2/jasperserver-pro.war"), SOURCE);
    Path target = war(tmp.resolve("10.0/jasperserver-pro.war"), TARGET);
    Scan scan = ops.scan(site, source);

    TargetScan t = ops.classifyAgainstTarget(scan, target, Optional.empty());

    Map<String, TargetEntry> by = byPath(t);
    assertThat(by.get("WEB-INF/applicationContext-carry.xml").targetClass())
        .isEqualTo(TargetClass.CARRY);
    assertThat(by.get("WEB-INF/applicationContext-merge.xml").targetClass())
        .isEqualTo(TargetClass.MERGE);
    assertThat(by.get("WEB-INF/applicationContext-merge.xml").target())
        .isEqualTo(TargetChange.CHANGED);
    assertThat(by.get("WEB-INF/jsp/obsolete.jsp").targetClass()).isEqualTo(TargetClass.OBSOLETE);
    assertThat(by.get("WEB-INF/classes/own.properties").targetClass()).isEqualTo(TargetClass.OWN);
    assertThat(by.get("WEB-INF/classes/clash.properties").targetClass())
        .isEqualTo(TargetClass.CLASH);
    assertThat(by.get("WEB-INF/js.quartz.properties").targetClass())
        .isEqualTo(TargetClass.INSTALLER);
    assertThat(by.get("scripts/runtime/main.js").targetClass()).isEqualTo(TargetClass.OVERLAY);
    assertThat(by.get("WEB-INF/lib/deleted-by-site.jar").targetClass())
        .isEqualTo(TargetClass.REMOVED);
    // a vendor file the site left alone is not a site file, whatever the target did to it
    assertThat(by).doesNotContainKey("WEB-INF/lib/untouched.jar");
    assertThat(t.entries()).extracting(TargetEntry::targetClass).isSorted();
    assertThat(t.counts().get(TargetClass.MERGE)).isEqualTo(1L);
    assertThat(t.out()).isEmpty();
    assertThat(by.get("WEB-INF/applicationContext-merge.xml").merge()).isEmpty();
  }

  @Test
  void should_write_mine_base_and_theirs_for_each_merge_file_when_out_is_given() throws Exception {
    Path source = tree(tmp.resolve("8.2/jasperserver-pro"), SOURCE);
    Path target = war(tmp.resolve("10.0/jasperserver-pro.war"), TARGET);
    Path out = tmp.resolve("merge");

    TargetScan t = ops.classifyAgainstTarget(ops.scan(site, source), target, Optional.of(out));

    String rel = "WEB-INF/applicationContext-merge.xml";
    assertThat(Files.readString(out.resolve(rel + ".mine"))).contains("site bean");
    assertThat(Files.readString(out.resolve(rel + ".base"))).contains("vendor v1");
    assertThat(Files.readString(out.resolve(rel + ".theirs"))).contains("vendor v2 jakarta");
    assertThat(byPath(t).get(rel).merge()).hasSize(3);
    // only MERGE files are written
    try (var all = Files.walk(out)) {
      assertThat(all.filter(Files::isRegularFile).count()).isEqualTo(3);
    }
  }

  @Test
  void should_refuse_an_out_directory_that_is_not_empty_and_write_nothing() throws Exception {
    Path source = war(tmp.resolve("8.2/jasperserver-pro.war"), SOURCE);
    Path target = war(tmp.resolve("10.0/jasperserver-pro.war"), TARGET);
    Path out = Files.createDirectories(tmp.resolve("merge"));
    Files.writeString(out.resolve("keep.txt"), "keep");
    Scan scan = ops.scan(site, source);

    assertThatThrownBy(() -> ops.classifyAgainstTarget(scan, target, Optional.of(out)))
        .isInstanceOf(CustomizationException.class)
        .hasMessageContaining("not empty");
    try (var all = Files.list(out)) {
      assertThat(all.count()).isEqualTo(1);
    }
  }

  @Test
  void should_refuse_a_target_that_is_the_source_webapp() throws Exception {
    Path source = war(tmp.resolve("8.2/jasperserver-pro.war"), SOURCE);
    Scan scan = ops.scan(site, source);

    assertThatThrownBy(() -> ops.classifyAgainstTarget(scan, source, Optional.empty()))
        .isInstanceOf(CustomizationException.class)
        .hasMessageContaining("same webapp");
  }

  @Test
  void should_name_the_target_option_when_the_target_holds_no_webapp() throws Exception {
    Path source = war(tmp.resolve("8.2/jasperserver-pro.war"), SOURCE);
    Scan scan = ops.scan(site, source);
    Path empty = Files.createDirectories(tmp.resolve("empty"));

    assertThatThrownBy(() -> ops.classifyAgainstTarget(scan, empty, Optional.empty()))
        .isInstanceOf(CustomizationException.class)
        .hasMessageStartingWith("--target-vendor:");
  }
}
