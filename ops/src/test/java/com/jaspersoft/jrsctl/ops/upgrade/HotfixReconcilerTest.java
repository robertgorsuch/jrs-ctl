package com.jaspersoft.jrsctl.ops.upgrade;

import static org.assertj.core.api.Assertions.assertThat;

import com.jaspersoft.jrsctl.core.state.HotfixInstalled;
import com.jaspersoft.jrsctl.core.state.HotfixState;
import com.jaspersoft.jrsctl.jrs.api.ServerIdentity;
import com.jaspersoft.jrsctl.ops.FakeJrsAdapter;
import com.jaspersoft.jrsctl.ops.hotfix.HotfixPaths;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
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
          HotfixReconciler.classify(f.runtime(), paths, installed("HF-A", "r-a"), TARGET);

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
          HotfixReconciler.classify(f.runtime(), paths, installed("HF-B", "r-b"), TARGET);

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
          HotfixReconciler.classify(f.runtime(), paths, installed("HF-C", "r-c"), TARGET);

      assertThat(c.status()).isEqualTo(HotfixReconciler.Status.SUPERSEDED);
      assertThat(c.reasons()).anyMatch(r -> r.contains("foo-1.jar") && r.contains("absent"));
    }
  }

  @Test
  void should_report_bundle_unavailable_when_run_directory_is_gone() throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      HotfixPaths paths = HotfixPaths.from(f.services.config(), f.services.platform());

      HotfixReconciler.Classification c =
          HotfixReconciler.classify(f.runtime(), paths, installed("HF-D", "r-gone"), TARGET);

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
              PreflightSteps.targetIdentity(FakeJrsAdapter.identity("8.2.0"), "9.0.0"));

      assertThat(all)
          .extracting(c -> c.hotfix().id() + ":" + c.status())
          .containsExactlyInAnyOrder("HF-A:REAPPLICABLE", "HF-B:SUPERSEDED");
      assertThat(HotfixReconciler.slug("JRS-8.2.0-HF-0001")).isEqualTo("jrs-8.2.0-hf-0001");
    }
  }
}
