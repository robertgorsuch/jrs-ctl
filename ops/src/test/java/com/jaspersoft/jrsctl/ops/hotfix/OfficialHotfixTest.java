package com.jaspersoft.jrsctl.ops.hotfix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jaspersoft.jrsctl.core.engine.Plan;
import com.jaspersoft.jrsctl.core.engine.RunOutcome;
import com.jaspersoft.jrsctl.core.state.HotfixInstalled;
import com.jaspersoft.jrsctl.ops.hotfix.HotfixOperations.ApplyOptions;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Issue #66: apply an official Jaspersoft cumulative hotfix as downloaded, instead of asking the
 * operator to build a jrsctl bundle from it first. The fixtures mirror the layout of {@code
 * hotfix_JRSPro10.0.0_cumulative_20260730_0457.zip}: an outer ZIP holding a readme, a webapp
 * archive and an installer archive, each inner archive carrying its own readme.
 */
class OfficialHotfixTest {

  private static final ApplyOptions SIGNED = new ApplyOptions(false);
  private static final ApplyOptions UNSIGNED = new ApplyOptions(true);
  private static final ApplyOptions CONFIRMED =
      new ApplyOptions(HotfixOperations.UnsignedAcceptance.CHECKSUM_CONFIRMED);

  private static final String LIB = "WEB-INF/lib/";

  private static final String OUTER_README =
      """
      ================================================================================
      Product Name: JasperReports Server Pro (TM)
      Release Version: 8.2.0
      Build version: [20260730_0457]
      ================================================================================
      """;

  private static final String WEBAPP_README =
      """
      ================================================================================
      Installation

      To apply the Hotfix:
      * Stop the application server.
      ================================================================================
      Added files:
      WEB-INF/lib/new-1.0.jar

      ================================================================================
      Modified files:
      WEB-INF/lib/foo-1.2.3.jar

      ================================================================================
      Deleted files:
      WEB-INF/lib/bar-0.9.jar
      WEB-INF/lib/never-installed-1.0.jar

      ================================================================================
      IMPORTANT
      If you plan to apply this hotfix where a previous hotfix is applied then additionally delete
      the following libraries if found:
      WEB-INF/lib/foo-*.jar

      Additional Notes:

      Details for fix JS-1:
      Run the following SQL on your DB2 database by hand.
      """;

  @TempDir Path tmp;

  private Path officialPackage(String name, Map<String, String> webapp, Map<String, String> install)
      throws IOException {
    Map<String, byte[]> outer = new LinkedHashMap<>();
    outer.put("readme.txt", OUTER_README.getBytes(StandardCharsets.UTF_8));
    outer.put("jasperserver-pro.zip", zipBytes(webapp, WEBAPP_README));
    if (!install.isEmpty()) {
      outer.put("js-install.zip", zipBytes(install, null));
    }
    Path zip = Files.createDirectories(tmp.resolve("downloads")).resolve(name);
    try (OutputStream out = Files.newOutputStream(zip);
        ZipOutputStream zos = new ZipOutputStream(out)) {
      for (Map.Entry<String, byte[]> e : outer.entrySet()) {
        zos.putNextEntry(new ZipEntry(e.getKey()));
        zos.write(e.getValue());
        zos.closeEntry();
      }
    }
    return zip;
  }

  /** ADR-0030 (issue #99): a package applied by hand is entered from its readme, once. */
  @Test
  void should_record_a_hand_applied_package_as_a_recorded_row_that_cannot_be_rolled_back()
      throws IOException {
    try (HotfixFixture f = HotfixFixture.create(tmp)) {
      Path pkg =
          zip(
              "hotfix.zip",
              Map.of(
                  "readme.txt",
                  utf8(OUTER_README),
                  "jasperserver-pro.zip",
                  zipBytes(Map.of("WEB-INF/lib/fix.jar", "new"), null)));

      HotfixInstalled row = f.ops().record(pkg);

      assertThat(row.id()).startsWith("JRSHF-");
      assertThat(row.origin()).isEqualTo(HotfixInstalled.Origin.RECORDED);
      assertThat(row.recorded()).isTrue();
      assertThat(row.snapshotRef()).isEmpty();
      assertThat(row.installedRunId()).isEqualTo(DefaultHotfixOperations.RECORDED_RUN_ID);
      assertThat(f.ops().list()).extracting(HotfixInstalled::id).containsExactly(row.id());
      assertThat(f.store().hotfixFiles(row.id())).isEmpty();
      assertThat(f.store().auditRows(20)).anyMatch(a -> a.action().equals("hotfix.recorded"));

      assertThatThrownBy(() -> f.ops().record(pkg))
          .isInstanceOf(HotfixException.class)
          .hasMessageContaining("already in the ledger")
          .hasMessageContaining("recorded");
      assertThatThrownBy(
              () -> f.ops().planRollback(row.id(), new HotfixOperations.RollbackOptions(false)))
          .isInstanceOf(HotfixException.class)
          .hasMessageContaining("applied outside jrsctl")
          .satisfies(e -> assertThat(((HotfixException) e).exitCode()).isEqualTo(2));
    }
  }

  @Test
  void should_refuse_to_record_a_file_that_is_not_an_official_package() throws IOException {
    try (HotfixFixture f = HotfixFixture.create(tmp)) {
      Path notOfficial = zip("plain.zip", Map.of("a.txt", utf8("x")));

      assertThatThrownBy(() -> f.ops().record(notOfficial))
          .isInstanceOf(HotfixException.class)
          .hasMessageContaining("not an official Jaspersoft hotfix package");
      assertThatThrownBy(() -> f.ops().record(tmp.resolve("missing.zip")))
          .isInstanceOf(HotfixException.class)
          .hasMessageContaining("does not exist");
      assertThat(f.ops().list()).isEmpty();
    }
  }

  private static byte[] zipBytes(Map<String, String> files, String readme) throws IOException {
    var buffer = new java.io.ByteArrayOutputStream();
    try (ZipOutputStream zos = new ZipOutputStream(buffer)) {
      if (readme != null) {
        zos.putNextEntry(new ZipEntry("readme.txt"));
        zos.write(readme.getBytes(StandardCharsets.UTF_8));
        zos.closeEntry();
      }
      for (Map.Entry<String, String> e : files.entrySet()) {
        zos.putNextEntry(new ZipEntry(e.getKey()));
        zos.write(e.getValue().getBytes(StandardCharsets.UTF_8));
        zos.closeEntry();
      }
    }
    return buffer.toByteArray();
  }

  /** An outer ZIP with exactly the given entries, in order. */
  private Path zip(String name, Map<String, byte[]> entries) throws IOException {
    Path zip = Files.createDirectories(tmp.resolve("downloads")).resolve(name);
    try (OutputStream out = Files.newOutputStream(zip);
        ZipOutputStream zos = new ZipOutputStream(out)) {
      for (Map.Entry<String, byte[]> e : entries.entrySet()) {
        zos.putNextEntry(new ZipEntry(e.getKey()));
        zos.write(e.getValue());
        zos.closeEntry();
      }
    }
    return zip;
  }

  private static byte[] utf8(String text) {
    return text.getBytes(StandardCharsets.UTF_8);
  }

  private Path fullPackage() throws IOException {
    return officialPackage(
        "hotfix_JRSPro8.2.0_cumulative_20260730_0457.zip",
        new LinkedHashMap<>(
            Map.of(LIB + "foo-1.2.3.jar", "patched foo", LIB + "new-1.0.jar", "brand new")),
        new LinkedHashMap<>(Map.of("buildomatic/lib/tool-2.0.jar", "patched tool")));
  }

  @Test
  void should_derive_add_replace_and_delete_from_the_package_and_this_installation()
      throws IOException {
    try (HotfixFixture f = HotfixFixture.create(tmp)) {
      Plan plan = f.ops().planApply(fullPackage(), UNSIGNED);

      assertThat(plan.summary().target()).contains("JRSHF-8.2.0-20260730-0457");
      assertThat(plan.summary().filesTouched().stream().map(Path::getFileName).map(Path::toString))
          .contains("foo-1.2.3.jar", "new-1.0.jar", "bar-0.9.jar", "tool-2.0.jar")
          .doesNotContain("never-installed-1.0.jar");
      assertThat(plan.summary().serviceRestart()).isTrue();
    }
  }

  @Test
  void should_delete_the_libraries_an_earlier_hotfix_left_but_never_one_the_package_lays_down()
      throws IOException {
    try (HotfixFixture f = HotfixFixture.create(tmp)) {
      Plan plan = f.ops().planApply(fullPackage(), UNSIGNED);

      // foo-1.2.2.jar matches the readme's WEB-INF/lib/foo-*.jar; foo-1.2.3.jar matches it too but
      // the package replaces that file, so it must survive.
      assertThat(plan.summary().filesTouched()).contains(f.target(HotfixFixture.FOO_OLDER));
      assertThat(plan.summary().warnings())
          .anySatisfy(w -> assertThat(w).contains("left by an earlier hotfix"))
          .anySatisfy(w -> assertThat(w).contains("Additional Notes"));
    }
  }

  @Test
  void should_apply_and_roll_back_an_official_package_through_the_ordinary_plan()
      throws IOException {
    try (HotfixFixture f = HotfixFixture.create(tmp)) {
      Path pkg = fullPackage();

      RunOutcome outcome = f.run(f.ops().planApply(pkg, UNSIGNED), "r-official-1");

      assertThat(outcome).isInstanceOf(RunOutcome.Succeeded.class);
      assertThat(f.target(HotfixFixture.FOO)).hasContent("patched foo");
      assertThat(f.target(HotfixFixture.LIB + "new-1.0.jar")).hasContent("brand new");
      assertThat(f.target("buildomatic/lib/tool-2.0.jar")).hasContent("patched tool");
      assertThat(f.target(HotfixFixture.BAR)).doesNotExist();
      assertThat(f.target(HotfixFixture.FOO_OLDER)).doesNotExist();
      HotfixInstalled installed = f.store().hotfixes().get(0);
      assertThat(installed.id()).isEqualTo("JRSHF-8.2.0-20260730-0457");

      RunOutcome undone =
          f.run(
              f.ops().planRollback(installed.id(), new HotfixOperations.RollbackOptions(false)),
              "r-official-2");

      assertThat(undone).isInstanceOf(RunOutcome.Succeeded.class);
      assertThat(f.target(HotfixFixture.FOO)).hasContent(HotfixFixture.OLD_FOO);
      assertThat(f.target(HotfixFixture.FOO_OLDER)).hasContent(HotfixFixture.OLDER_FOO);
      assertThat(f.target(HotfixFixture.BAR)).hasContent(HotfixFixture.BAR_BYTES);
      assertThat(f.target(HotfixFixture.LIB + "new-1.0.jar")).doesNotExist();
    }
  }

  /**
   * Issue #159: an operator who compared the checksum with the support portal (ADR-0027) passed no
   * flag, so the plan, the verify step and the audit must say the checksum was confirmed, never
   * that --allow-unsigned was given.
   */
  @Test
  void should_report_a_confirmed_checksum_and_not_allow_unsigned_when_the_operator_confirmed_it()
      throws IOException {
    try (HotfixFixture f = HotfixFixture.create(tmp)) {
      Plan plan = f.ops().planApply(fullPackage(), CONFIRMED);

      assertThat(plan.summary().warnings())
          .anySatisfy(w -> assertThat(w).contains("checksum confirmed by the operator"))
          .noneSatisfy(w -> assertThat(w).contains("--allow-unsigned"));
      assertThat(plan.steps())
          .noneSatisfy(s -> assertThat(s.detail()).contains("--allow-unsigned"));

      RunOutcome outcome = f.run(plan, "r-official-confirmed");

      assertThat(outcome).isInstanceOf(RunOutcome.Succeeded.class);
      assertThat(f.store().auditRows(20))
          .anyMatch(a -> a.action().equals(ApplySteps.AUDIT_CHECKSUM_CONFIRMED))
          .noneMatch(a -> a.action().equals(ApplySteps.AUDIT_ALLOW_UNSIGNED));
    }
  }

  @Test
  void should_report_and_audit_allow_unsigned_when_the_flag_was_given() throws IOException {
    try (HotfixFixture f = HotfixFixture.create(tmp)) {
      Plan plan = f.ops().planApply(fullPackage(), UNSIGNED);

      assertThat(plan.summary().warnings())
          .anySatisfy(w -> assertThat(w).contains("accepted with --allow-unsigned"));

      f.run(plan, "r-official-flag");

      assertThat(f.store().auditRows(20))
          .anyMatch(a -> a.action().equals(ApplySteps.AUDIT_ALLOW_UNSIGNED))
          .noneMatch(a -> a.action().equals(ApplySteps.AUDIT_CHECKSUM_CONFIRMED));
    }
  }

  @Test
  void should_refuse_an_official_package_without_allow_unsigned() throws IOException {
    try (HotfixFixture f = HotfixFixture.create(tmp)) {
      Path pkg = fullPackage();

      assertThatThrownBy(() -> f.ops().planApply(pkg, SIGNED))
          .isInstanceOf(HotfixException.class)
          .hasMessageContaining("carry no jrsctl signature")
          .hasFieldOrPropertyWithValue(
              "remediation",
              "check the package against the checksum on the support portal, then re-run with --allow-unsigned");
    }
  }

  @Test
  void should_warn_about_settings_files_the_package_overwrites() throws IOException {
    try (HotfixFixture f = HotfixFixture.create(tmp)) {
      HotfixFixture.write(f.target("webapps/jasperserver-pro/WEB-INF/web.xml"), "<web-app/>");
      Path pkg =
          officialPackage(
              "hotfix_JRSPro8.2.0_cumulative_20260730_0457.zip",
              new LinkedHashMap<>(
                  Map.of(LIB + "foo-1.2.3.jar", "patched foo", "WEB-INF/web.xml", "<web-app/>!")),
              Map.of());

      Plan plan = f.ops().planApply(pkg, UNSIGNED);

      assertThat(plan.summary().warnings())
          .anySatisfy(w -> assertThat(w).contains("applied again").contains("WEB-INF/web.xml"));
    }
  }

  @Test
  void should_name_the_package_and_its_checksum_so_it_can_be_compared_with_the_portal()
      throws IOException {
    try (HotfixFixture f = HotfixFixture.create(tmp)) {
      Path pkg = fullPackage();

      Plan plan = f.ops().planApply(pkg, UNSIGNED);

      assertThat(plan.summary().warnings())
          .anySatisfy(
              w ->
                  assertThat(w)
                      .contains(pkg.getFileName().toString())
                      .contains(f.sha(pkg))
                      .contains("support portal"));
    }
  }

  @Test
  void should_keep_the_warnings_when_a_later_command_reuses_the_converted_bundle()
      throws IOException {
    try (HotfixFixture f = HotfixFixture.create(tmp)) {
      Path pkg = fullPackage();
      f.ops().verify(pkg);

      Plan plan = f.ops().planApply(pkg, UNSIGNED);

      assertThat(plan.summary().warnings())
          .anySatisfy(w -> assertThat(w).contains("Additional Notes"))
          .anySatisfy(w -> assertThat(w).contains("left by an earlier hotfix"));
    }
  }

  @Test
  void should_verify_an_official_package_as_an_unsigned_bundle() throws IOException {
    try (HotfixFixture f = HotfixFixture.create(tmp)) {
      HotfixOperations.VerifyReport report = f.ops().verify(fullPackage());

      assertThat(report.signatureValid()).isFalse();
      assertThat(report.hashesValid()).isTrue();
      assertThat(report.manifestId()).isEqualTo("JRSHF-8.2.0-20260730-0457");
      // ADR-0027: an official package has no signature to fail, so verify reports it as ok and
      // says what it is; apply asks the operator to confirm the checksum instead of a flag
      assertThat(report.official()).isTrue();
      assertThat(report.sha256()).hasSize(64);
      assertThat(report.ok()).isTrue();
    }
  }

  /**
   * Field test 2, H1: support's packages do not all use the exact names ADR-0024 matched. A readme
   * in another case, one directory of prefix and a version in the inner archive's name are the same
   * package.
   */
  @Test
  void should_recognise_a_package_whose_names_differ_in_case_and_carry_a_prefix()
      throws IOException {
    Map<String, byte[]> entries = new LinkedHashMap<>();
    entries.put("JRSHF-8.2.0/README.TXT", utf8(OUTER_README));
    entries.put(
        "JRSHF-8.2.0/jasperserver-pro-8.2.0-hotfix.zip",
        zipBytes(Map.of(LIB + "foo-1.2.3.jar", "patched foo"), WEBAPP_README));
    Path pkg = zip("prefixed.zip", entries);
    assertThat(OfficialPackage.looksOfficial(pkg)).isTrue();
    try (HotfixFixture f = HotfixFixture.create(tmp)) {
      Plan plan = f.ops().planApply(pkg, UNSIGNED);

      assertThat(plan.summary().target()).contains("JRSHF-8.2.0-20260730-0457");
      assertThat(plan.summary().filesTouched().stream().map(Path::getFileName).map(Path::toString))
          .contains("foo-1.2.3.jar");
    }
  }

  @Test
  void should_recognise_a_package_that_ships_the_webapp_unpacked() throws IOException {
    Map<String, byte[]> entries = new LinkedHashMap<>();
    entries.put("readme.txt", utf8(OUTER_README));
    entries.put("jasperserver-pro/readme.txt", utf8(WEBAPP_README));
    entries.put("jasperserver-pro/" + LIB + "new-1.0.jar", utf8("brand new"));
    Path pkg = zip("unpacked.zip", entries);
    assertThat(OfficialPackage.looksOfficial(pkg)).isTrue();
    try (HotfixFixture f = HotfixFixture.create(tmp)) {
      Plan plan = f.ops().planApply(pkg, UNSIGNED);

      assertThat(plan.summary().filesTouched().stream().map(Path::getFileName).map(Path::toString))
          .contains("new-1.0.jar");
    }
  }

  /** The derived bundle is read back through the bundle reader, which must accept vendor paths. */
  @Test
  void should_convert_and_read_back_a_package_with_a_space_in_a_path() throws IOException {
    Path pkg =
        officialPackage(
            "spaced.zip",
            new LinkedHashMap<>(Map.of("WEB-INF/samples/My Report (v2)+final.jrxml", "<jasper/>")),
            Map.of());
    try (HotfixFixture f = HotfixFixture.create(tmp)) {
      HotfixOperations.VerifyReport report = f.ops().verify(pkg);

      assertThat(report.hashesValid()).as(String.join("; ", report.hashProblems())).isTrue();
      assertThat(report.official()).isTrue();
    }
  }

  @Test
  void should_name_both_shapes_when_a_zip_is_neither() throws IOException {
    Path odd = zip("odd.zip", Map.of("something.txt", utf8("?")));
    try (HotfixFixture f = HotfixFixture.create(tmp)) {
      assertThatThrownBy(() -> f.ops().verify(odd))
          .isInstanceOf(HotfixException.class)
          .hasMessageContaining("neither an official Jaspersoft hotfix package")
          .hasMessageContaining("readme.txt")
          .hasMessageContaining("manifest.json")
          .satisfies(e -> assertThat(((HotfixException) e).exitCode()).isEqualTo(2));
    }
  }

  @Test
  void should_refuse_a_package_whose_readme_names_no_build() throws IOException {
    try (HotfixFixture f = HotfixFixture.create(tmp)) {
      Path zip = Files.createDirectories(tmp.resolve("downloads")).resolve("odd.zip");
      try (OutputStream out = Files.newOutputStream(zip);
          ZipOutputStream zos = new ZipOutputStream(out)) {
        zos.putNextEntry(new ZipEntry("readme.txt"));
        zos.write("Product Name: JasperReports Server Pro\n".getBytes(StandardCharsets.UTF_8));
        zos.closeEntry();
        zos.putNextEntry(new ZipEntry("jasperserver-pro.zip"));
        zos.write(zipBytes(Map.of(LIB + "foo-1.2.3.jar", "patched"), null));
        zos.closeEntry();
      }

      assertThatThrownBy(() -> f.ops().planApply(zip, UNSIGNED))
          .isInstanceOf(HotfixException.class)
          .hasMessageContaining("release and build version");
    }
  }
}
