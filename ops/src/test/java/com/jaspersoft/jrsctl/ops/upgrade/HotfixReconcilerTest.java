package com.jaspersoft.jrsctl.ops.upgrade;

import static org.assertj.core.api.Assertions.assertThat;

import com.jaspersoft.jrsctl.core.state.HotfixInstalled;
import com.jaspersoft.jrsctl.core.state.HotfixState;
import com.jaspersoft.jrsctl.jrs.api.ServerIdentity;
import com.jaspersoft.jrsctl.ops.FakeJrsAdapter;
import com.jaspersoft.jrsctl.ops.hotfix.HotfixPaths;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HotfixReconcilerTest {

  private static final String LIB = "webapps/jasperserver-pro/WEB-INF/lib/";
  private static final ServerIdentity TARGET = FakeJrsAdapter.identity("9.0.0");

  @TempDir Path tmp;

  private static String manifest(String id, String versions, String replaces) {
    return """
        {
          "id": "%s",
          "version": "1",
          "title": "fix",
          "applies": { "versions": [%s], "editions": ["PRO"] },
          "files": [ { "action": "replace", "path": "%s", "replaces": [%s] } ],
          "restart": "required",
          "rollback": "snapshot"
        }
        """
        .formatted(id, versions, LIB + "foo-2.jar", replaces);
  }

  private static HotfixInstalled installed(String id, String runId) {
    return new HotfixInstalled(
        id, "1", "fix", runId, Optional.empty(), HotfixState.INSTALLED, Instant.EPOCH);
  }

  private void bundle(UpgradeFixture f, String runId, String json) throws Exception {
    UpgradeFixture.write(
        f.fake.home.runDir(runId).resolve("bundle").resolve("manifest.json"), json);
  }

  @Test
  void should_classify_reapplicable_when_applies_matches_and_replaces_targets_exist()
      throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      f.store().recordHotfixInstalled(installed("HF-A", "r-a"), List.of());
      bundle(f, "r-a", manifest("HF-A", "\">=8.0.0 <10.0.0\"", "\"foo-1.jar\""));
      UpgradeFixture.write(
          f.webappDir.resolve("WEB-INF").resolve("lib").resolve("foo-1.jar"), "old");
      HotfixPaths paths = HotfixPaths.from(f.services.config(), f.services.platform());

      HotfixReconciler.Classification c =
          HotfixReconciler.classify(
              f.runtime(),
              paths,
              installed("HF-A", "r-a"),
              TARGET,
              HotfixReconciler.installed(paths));

      assertThat(c.status()).isEqualTo(HotfixReconciler.Status.REAPPLICABLE);
      assertThat(c.reasons()).isEmpty();
      assertThat(c.bundleAvailable()).isTrue();
      assertThat(c.describe()).isEqualTo("HF-A -> REAPPLICABLE");
    }
  }

  @Test
  void should_classify_superseded_when_applies_excludes_target_version() throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      bundle(f, "r-b", manifest("HF-B", "\">=8.0.0 <9.0.0\"", ""));
      HotfixPaths paths = HotfixPaths.from(f.services.config(), f.services.platform());

      HotfixReconciler.Classification c =
          HotfixReconciler.classify(
              f.runtime(),
              paths,
              installed("HF-B", "r-b"),
              TARGET,
              HotfixReconciler.installed(paths));

      assertThat(c.status()).isEqualTo(HotfixReconciler.Status.SUPERSEDED);
      assertThat(c.reasons()).anyMatch(r -> r.contains("9.0.0") && r.contains("applies.versions"));
    }
  }

  @Test
  void should_classify_superseded_when_a_replaces_target_is_absent() throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      bundle(f, "r-c", manifest("HF-C", "\">=8.0.0 <10.0.0\"", "\"foo-1.jar\""));
      HotfixPaths paths = HotfixPaths.from(f.services.config(), f.services.platform());

      HotfixReconciler.Classification c =
          HotfixReconciler.classify(
              f.runtime(),
              paths,
              installed("HF-C", "r-c"),
              TARGET,
              HotfixReconciler.installed(paths));

      assertThat(c.status()).isEqualTo(HotfixReconciler.Status.SUPERSEDED);
      assertThat(c.reasons()).anyMatch(r -> r.contains("foo-1.jar") && r.contains("absent"));
    }
  }

  /**
   * Apply deletes the files a hotfix replaces, so at plan time the running webapp never holds them;
   * the question is whether the target package ships them (assessment item U4).
   */
  @Test
  void should_judge_replaces_targets_against_the_package_webapp_when_planning() throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      bundle(f, "r-e", manifest("HF-E", "\">=8.0.0 <10.0.0\"", "\"foo-1.jar\""));
      UpgradeFixture.write(
          f.packageDir
              .resolve("jasperserver-pro")
              .resolve("WEB-INF")
              .resolve("lib")
              .resolve("foo-1.jar"),
          "vendor");
      HotfixPaths paths = HotfixPaths.from(f.services.config(), f.services.platform());
      TargetPackage pkg = TargetPackage.inspect(f.packageDir, f.runtime().locator());
      HotfixInstalled hotfix = installed("HF-E", "r-e");

      HotfixReconciler.Classification asInstalled =
          HotfixReconciler.classify(
              f.runtime(), paths, hotfix, TARGET, HotfixReconciler.installed(paths));
      HotfixReconciler.Classification asPackaged =
          HotfixReconciler.classify(
              f.runtime(),
              paths,
              hotfix,
              TARGET,
              HotfixReconciler.inPackage(pkg, "jasperserver-pro", paths));

      assertThat(asInstalled.status()).isEqualTo(HotfixReconciler.Status.SUPERSEDED);
      assertThat(asInstalled.reasons()).anyMatch(r -> r.contains("foo-1.jar"));
      assertThat(asPackaged.status()).isEqualTo(HotfixReconciler.Status.REAPPLICABLE);
    }
  }

  /** A real distribution ships the webapp as a war, not unpacked. */
  @Test
  void should_look_for_replaces_targets_inside_the_packages_war_when_planning() throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      bundle(f, "r-w", manifest("HF-W", "\">=8.0.0 <10.0.0\"", "\"foo-1.jar\""));
      Path pkg = Files.createDirectories(f.root.resolve("pkg-war"));
      try (ZipOutputStream zip =
          new ZipOutputStream(Files.newOutputStream(pkg.resolve("jasperserver-pro.war")))) {
        zip.putNextEntry(new ZipEntry("WEB-INF/lib/foo-1.jar"));
        zip.write("vendor".getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
      }
      HotfixPaths paths = HotfixPaths.from(f.services.config(), f.services.platform());
      TargetPackage war = TargetPackage.inspect(pkg, f.runtime().locator());
      assertThat(war.warFile()).isPresent();
      assertThat(war.webappDir()).isEmpty();

      HotfixReconciler.Classification c =
          HotfixReconciler.classify(
              f.runtime(),
              paths,
              installed("HF-W", "r-w"),
              TARGET,
              HotfixReconciler.inPackage(war, "jasperserver-pro", paths));

      assertThat(c.status()).isEqualTo(HotfixReconciler.Status.REAPPLICABLE);
    }
  }

  @Test
  void should_report_bundle_unavailable_when_run_directory_is_gone() throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      HotfixPaths paths = HotfixPaths.from(f.services.config(), f.services.platform());

      HotfixReconciler.Classification c =
          HotfixReconciler.classify(
              f.runtime(),
              paths,
              installed("HF-D", "r-gone"),
              TARGET,
              HotfixReconciler.installed(paths));

      assertThat(c.status()).isEqualTo(HotfixReconciler.Status.SUPERSEDED);
      assertThat(c.bundleAvailable()).isFalse();
      assertThat(c.describe()).contains("bundle no longer available, re-apply manually");
    }
  }

  @Test
  void should_classify_every_installed_hotfix_against_the_target_when_planning() throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      f.store().recordHotfixInstalled(installed("HF-A", "r-a"), List.of());
      f.store().recordHotfixInstalled(installed("HF-B", "r-b"), List.of());
      bundle(f, "r-a", manifest("HF-A", "\">=8.0.0 <10.0.0\"", ""));
      bundle(f, "r-b", manifest("HF-B", "\">=8.0.0 <9.0.0\"", ""));
      UpgradeInput in =
          new UpgradeInput(
              UpgradeOperations.UpgradeOptions.newdb("9.0.0", f.packageDir),
              HotfixPaths.from(f.services.config(), f.services.platform()),
              "jasperserver-pro",
              TargetPackage.inspect(f.packageDir, f.runtime().locator()),
              Optional.of(FakeJrsAdapter.identity("8.2.0")),
              java.util.Map.of());

      List<HotfixReconciler.Classification> all =
          HotfixReconciler.classify(
              f.runtime(),
              in,
              PreflightSteps.targetIdentity(FakeJrsAdapter.identity("8.2.0"), "9.0.0"),
              HotfixReconciler.installed(in.paths()));

      assertThat(all)
          .extracting(c -> c.hotfix().id() + ":" + c.status())
          .containsExactlyInAnyOrder("HF-A:REAPPLICABLE", "HF-B:SUPERSEDED");
      assertThat(HotfixReconciler.slug("JRS-8.2.0-HF-0001")).isEqualTo("jrs-8.2.0-hf-0001");
    }
  }
}
