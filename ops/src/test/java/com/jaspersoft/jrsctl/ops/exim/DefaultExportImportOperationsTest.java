package com.jaspersoft.jrsctl.ops.exim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jaspersoft.jrsctl.core.engine.Plan;
import com.jaspersoft.jrsctl.core.engine.Step;
import com.jaspersoft.jrsctl.jrs.api.Capability;
import com.jaspersoft.jrsctl.jrs.api.ExportImportStrategy;
import com.jaspersoft.jrsctl.jrs.api.ExportRequest;
import com.jaspersoft.jrsctl.jrs.strategy.Sidecar;
import com.jaspersoft.jrsctl.ops.exim.ExportImportOperations.ExportOptions;
import com.jaspersoft.jrsctl.ops.exim.ExportImportOperations.ImportOptions;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DefaultExportImportOperationsTest {

  @TempDir Path tmp;

  private EximFakeAdapter adapter;
  private EximFixture fx;
  private Path archive;

  @BeforeEach
  void setUp() throws IOException {
    adapter = new EximFakeAdapter();
    fx = new EximFixture(tmp, () -> adapter);
    archive = tmp.resolve("in").resolve("public.zip");
    Files.createDirectories(archive.getParent());
    Files.write(archive, new byte[] {'P', 'K', 3, 4, 1, 2, 3});
  }

  @AfterEach
  void tearDown() {
    fx.close();
  }

  private static ExportOptions export(Set<String> uris, boolean fullServer, Path out) {
    return export(uris, fullServer, out, Optional.empty());
  }

  private static ExportOptions export(
      Set<String> uris, boolean fullServer, Path out, Optional<ExportImportStrategy.Kind> kind) {
    return new ExportOptions(uris, true, false, false, false, false, fullServer, out, kind);
  }

  private static ImportOptions importOf(Path archive, boolean update) {
    return new ImportOptions(
        archive,
        update,
        false,
        false,
        false,
        false,
        false,
        false,
        Optional.empty(),
        Optional.empty(),
        Optional.empty());
  }

  private void sidecar(List<String> uris, boolean fullServer) throws IOException {
    sidecar(uris, fullServer, "8.2.0");
  }

  private void sidecar(List<String> uris, boolean fullServer, String sourceVersion)
      throws IOException {
    Sidecar.write(
        Sidecar.pathFor(archive),
        new Sidecar(
            Instant.parse("2026-09-01T00:00:00Z"),
            "srv",
            sourceVersion,
            Optional.of(EximFakeAdapter.FINGERPRINT),
            new Sidecar.Flags(
                fullServer ? ExportRequest.Scope.EVERYTHING : ExportRequest.Scope.REPOSITORY,
                uris,
                true,
                false,
                false,
                false,
                false,
                fullServer),
            "0000",
            ExportImportStrategy.Kind.REST));
  }

  private static List<String> ids(Plan plan) {
    return plan.steps().stream().map(Step::id).toList();
  }

  private static List<String> phases(Plan plan) {
    return plan.byPhase().keySet().stream().toList();
  }

  // ---- export ---------------------------------------------------------------------------------

  @Test
  void should_choose_rest_and_compose_export_steps_when_export_probe_passes() {
    Path out = tmp.resolve("out").resolve("x.zip");

    Plan plan = fx.ops().planExport(export(Set.of("/public", "/adhoc"), false, out));

    assertThat(ids(plan))
        .containsExactly("export.start", "export.poll", "export.download", "export.sidecar");
    assertThat(phases(plan)).containsExactly("export");
    assertThat(plan.summary().operation()).isEqualTo("export");
    assertThat(plan.summary().target()).isEqualTo("/adhoc, /public");
    assertThat(plan.summary().strategy())
        .startsWith("rest (")
        .contains("EXPORT_ASYNC probe passed");
    assertThat(plan.summary().serviceRestart()).isFalse();
    assertThat(plan.summary().filesTouched())
        .containsExactly(out.toAbsolutePath().normalize(), Sidecar.pathFor(out.toAbsolutePath()));
    assertThat(plan.summary().warnings()).isEmpty();
    assertThat(plan.fingerprint().inputs()).containsKeys("server", "out", "config", "request");
    assertThat(adapter.exports).as("planning must not export").isEmpty();
  }

  @Test
  void should_choose_vendor_and_warn_about_service_stop_when_full_server_requested() {
    Plan plan = fx.ops().planExport(export(Set.of(), true, tmp.resolve("full.zip")));

    assertThat(ids(plan))
        .containsExactly(
            "export.locate-vendor-tools",
            "export.stop-service",
            "export.js-export",
            "export.start-service",
            "export.wait-for-server",
            "export.sidecar");
    assertThat(plan.summary().target()).isEqualTo("full server");
    assertThat(plan.summary().strategy()).startsWith("vendor (").contains("full-server");
    assertThat(plan.summary().serviceRestart()).isTrue();
    assertThat(plan.summary().warnings()).anyMatch(w -> w.contains("service will be stopped"));
  }

  /** Issue #67: a full-server export runs js-export against the running server by default. */
  @Test
  void should_keep_the_service_running_and_say_so_when_a_full_server_export_does_not_ask_to_stop() {
    Plan plan =
        fx.ops()
            .planExport(
                new ExportOptions(
                    Set.of(),
                    true,
                    false,
                    false,
                    false,
                    false,
                    true,
                    tmp.resolve("full.zip"),
                    Optional.empty(),
                    false));

    assertThat(ids(plan))
        .containsExactly("export.locate-vendor-tools", "export.js-export", "export.sidecar");
    assertThat(plan.summary().serviceRestart()).isFalse();
    assertThat(plan.summary().warnings())
        .noneMatch(w -> w.contains("service will be stopped"))
        .anyMatch(w -> w.contains("keeps running") && w.contains("--stop-service"));
  }

  @Test
  void should_choose_vendor_when_strategy_forced() {
    Plan plan =
        fx.ops()
            .planExport(
                export(
                    Set.of("/public"),
                    false,
                    tmp.resolve("v.zip"),
                    Optional.of(ExportImportStrategy.Kind.VENDOR_CLI)));

    assertThat(plan.summary().strategy())
        .isEqualTo("vendor (vendor CLI forced by --strategy vendor)");
    assertThat(plan.summary().serviceRestart()).isTrue();
    assertThat(plan.fingerprint().inputs()).containsEntry("strategy", "VENDOR_CLI");
  }

  @Test
  void should_choose_vendor_when_export_probe_fails() {
    adapter.capabilities = EnumSet.of(Capability.IMPORT_ASYNC);

    Plan plan = fx.ops().planExport(export(Set.of("/public"), false, tmp.resolve("p.zip")));

    assertThat(plan.summary().strategy()).startsWith("vendor (").contains("probe failed");
    assertThat(ids(plan)).contains("export.js-export");
  }

  @Test
  void should_default_to_repository_root_when_no_uri_given() {
    Plan plan = fx.ops().planExport(export(Set.of(), false, tmp.resolve("root.zip")));

    assertThat(plan.summary().target()).isEqualTo("/");
    assertThat(plan.summary().resourcesTouched()).containsExactly("/");
  }

  // ---- import ---------------------------------------------------------------------------------

  /** Field test 2, I1: the sidecar remembers the alias, so the import needs no flag. */
  @Test
  void should_adopt_the_key_alias_recorded_in_the_sidecar_when_the_import_names_none()
      throws IOException {
    Sidecar.write(
        Sidecar.pathFor(archive),
        new Sidecar(
            Instant.parse("2026-09-01T00:00:00Z"),
            "srv",
            "8.2.0",
            Optional.of(EximFakeAdapter.FINGERPRINT),
            new Sidecar.Flags(
                ExportRequest.Scope.REPOSITORY,
                List.of("/public"),
                true,
                false,
                false,
                false,
                false,
                false,
                Optional.of(ExportRequest.PORTABLE_KEY_ALIAS)),
            "0000",
            ExportImportStrategy.Kind.REST));

    Plan plan = fx.ops().planImport(importOf(archive, false));

    assertThat(plan.fingerprint().inputs().get("request"))
        .contains(";keyAlias=" + ExportRequest.PORTABLE_KEY_ALIAS);
    assertThat(plan.summary().warnings())
        .anyMatch(w -> w.contains("exported with key alias " + ExportRequest.PORTABLE_KEY_ALIAS));
  }

  /**
   * Field test 2, I4: an organisation import snapshots that organisation's folder, never the root.
   */
  @Test
  void should_scope_the_pre_import_snapshot_to_the_organisation_folder() {
    adapter.existing = Optional.of(Set.of("/organizations/org1"));
    ImportOptions base = importOf(archive, true);
    ImportOptions options =
        new ImportOptions(
            base.archive(),
            base.update(),
            base.skipUserUpdate(),
            base.accessEvents(),
            base.auditEvents(),
            base.monitoring(),
            base.settings(),
            base.skipThemes(),
            base.sourceKeystore(),
            base.sourceKeystorePassword(),
            base.strategy(),
            base.brokenDependencies(),
            Optional.empty(),
            Optional.of("org1"),
            true);

    Plan plan = fx.ops().planImport(options);

    assertThat(plan.summary().resourcesTouched()).containsExactly("/organizations/org1");
    assertThat(plan.steps().get(1).detail()).startsWith("/organizations/org1 -> ");
    assertThat(plan.fingerprint().inputs().get("request")).contains(";organization=org1;merge");
    assertThat(adapter.imports).as("planning must not import").isEmpty();
  }

  @Test
  void should_order_precheck_backup_then_import_when_planning_a_rest_import() throws IOException {
    sidecar(List.of("/public"), false);

    Plan plan = fx.ops().planImport(importOf(archive, false));

    assertThat(phases(plan)).containsExactly("precheck", "backup", "import");
    assertThat(ids(plan))
        .containsExactly(
            "precheck.import.check-keystore",
            "backup.pre-import-snapshot",
            "backup.pre-import-listing",
            "backup.export.start",
            "backup.export.poll",
            "backup.export.download",
            "backup.export.sidecar",
            "import.snapshot-rollback",
            "import.start",
            "import.poll",
            "import.verify");
    Path snapshot = plan.summary().backupLocations().get(0);
    assertThat(snapshot.toString())
        .startsWith(fx.services.home().snapshots().resolve("pre-import").toString())
        .endsWith(".zip");
    assertThat(plan.summary().operation()).isEqualTo("import");
    assertThat(plan.summary().target()).isEqualTo("public.zip");
    assertThat(plan.summary().resourcesTouched()).containsExactly("/public");
    assertThat(plan.summary().warnings())
        .contains(DefaultExportImportOperations.ROLLBACK_WARNING)
        .noneMatch(w -> w.contains("no sidecar"));
    assertThat(plan.summary().strategy()).startsWith("rest (").contains("IMPORT_ASYNC");
    assertThat(plan.summary().serviceRestart()).isFalse();
    assertThat(plan.steps().get(1).detail()).startsWith("/public -> ");
    assertThat(plan.steps().get(2).detail()).startsWith("every URI under /public -> ");
    assertThat(plan.steps().get(3).detail()).isEqualTo("uris /public");
    assertThat(plan.steps().get(7).mutating()).isTrue();
    assertThat(adapter.imports).as("planning must not import").isEmpty();
  }

  @Test
  void should_snapshot_full_server_when_update_targets_root_without_sidecar() {
    Plan plan = fx.ops().planImport(importOf(archive, true));

    assertThat(plan.summary().warnings())
        .contains(DefaultExportImportOperations.ROLLBACK_WARNING)
        .anyMatch(w -> w.contains("no sidecar"))
        .anyMatch(w -> w.contains("--update"));
    assertThat(plan.summary().resourcesTouched()).containsExactly("/");
    assertThat(plan.steps().get(1).detail()).startsWith("full server -> ");
    // issue #100: the listing of the root follows the snapshot announcement
    assertThat(plan.steps().get(2).detail()).startsWith("every URI under / -> ");
    assertThat(plan.steps().get(3).detail()).isEqualTo("everything");
  }

  @Test
  void should_hoist_vendor_prechecks_and_keep_service_steps_when_strategy_forced_to_vendor()
      throws IOException {
    sidecar(List.of("/public"), false);
    ImportOptions options =
        new ImportOptions(
            archive,
            false,
            false,
            false,
            false,
            false,
            false,
            false,
            Optional.empty(),
            Optional.empty(),
            Optional.of(ExportImportStrategy.Kind.VENDOR_CLI));

    Plan plan = fx.ops().planImport(options);

    assertThat(phases(plan)).containsExactly("precheck", "backup", "import");
    assertThat(ids(plan))
        .startsWith(
            "precheck.import.check-keystore",
            "precheck.import.locate-vendor-tools",
            "backup.pre-import-snapshot",
            "backup.pre-import-listing",
            "backup.export.locate-vendor-tools",
            "backup.export.stop-service")
        .contains("import.snapshot-rollback", "import.stop-service", "import.js-import");
    assertThat(ids(plan).indexOf("import.snapshot-rollback"))
        .isLessThan(ids(plan).indexOf("import.stop-service"));
    assertThat(plan.summary().serviceRestart()).isTrue();
    assertThat(plan.summary().warnings()).anyMatch(w -> w.contains("service will be stopped"));
  }

  @Test
  void should_change_fingerprint_when_archive_content_changes() throws IOException {
    Plan before = fx.ops().planImport(importOf(archive, false));
    Files.write(archive, new byte[] {'P', 'K', 9, 9, 9});
    Plan after = fx.ops().planImport(importOf(archive, false));

    assertThat(before.fingerprint().matches(after.fingerprint())).isFalse();
    assertThat(before.fingerprint().changedKeys(after.fingerprint())).contains("archiveSha256");
    assertThat(before.summary().backupLocations())
        .as("snapshot name follows the archive hash")
        .isNotEqualTo(after.summary().backupLocations());
  }

  /**
   * Issue #107, release notes 10.1 p.6: "Resources exported from version 10.1.0 cannot be imported
   * into older versions". The fake server is 8.2.0.
   */
  @Test
  void should_refuse_planning_when_the_archive_comes_from_10_1_and_this_server_is_older()
      throws IOException {
    sidecar(List.of("/public"), false, "10.1.0");

    assertThatThrownBy(() -> fx.ops().planImport(importOf(archive, false)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("10.1.0")
        .hasMessageContaining("8.2.0")
        .hasMessageContaining("cannot be imported into older versions")
        .hasMessageContaining("--force-version");
    assertThat(adapter.imports).isEmpty();
  }

  @Test
  void should_plan_with_a_warning_and_an_audit_row_when_force_version_is_given()
      throws IOException {
    sidecar(List.of("/public"), false, "10.1.0");
    ImportOptions options = importOf(archive, false).withForceVersion(true);

    Plan plan = fx.ops().planImport(options);

    assertThat(plan.summary().warnings())
        .anyMatch(w -> w.contains("--force-version") && w.contains("10.1.0"));
    assertThat(fx.services.stateStore().get().auditRows(10))
        .anyMatch(
            a -> a.action().equals("--force-version") && a.detail().orElse("").contains("10.1.0"));
  }

  @Test
  void should_not_refuse_an_archive_from_a_version_at_most_this_servers() throws IOException {
    sidecar(List.of("/public"), false, "8.2.0");

    assertThat(ids(fx.ops().planImport(importOf(archive, false)))).isNotEmpty();
  }

  @Test
  void should_refuse_planning_when_archive_is_missing() {
    assertThatThrownBy(() -> fx.ops().planImport(importOf(tmp.resolve("nope.zip"), false)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("nope.zip");
    assertThat(adapter.imports).isEmpty();
  }

  /** Field test 2, E3: a mistyped --uri is refused before anything runs. */
  @Test
  void should_refuse_planning_when_a_uri_does_not_exist_on_the_server() {
    adapter.existing = Optional.of(Set.of("/public"));

    assertThatThrownBy(
            () ->
                fx.ops()
                    .planExport(export(Set.of("/public", "/typo"), false, tmp.resolve("x.zip"))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("/typo does not exist on the server");
    assertThat(adapter.existenceChecks).containsExactlyInAnyOrder("/public", "/typo");
  }

  @Test
  void should_not_ask_the_server_about_the_root_or_a_full_server_export() {
    adapter.existing = Optional.of(Set.of());

    fx.ops().planExport(export(Set.of(), false, tmp.resolve("root.zip")));
    fx.ops().planExport(export(Set.of(), true, tmp.resolve("full.zip")));

    assertThat(adapter.existenceChecks).isEmpty();
  }

  /**
   * Importing new content is the ordinary case: a folder the archive holds need not exist on the
   * target yet, so the snapshot covers what does exist and says what does not.
   */
  @Test
  void should_snapshot_only_the_sidecar_uris_that_exist_when_planning_an_import()
      throws IOException {
    sidecar(List.of("/public/a", "/public/b"), false);
    adapter.existing = Optional.of(Set.of("/public/a"));

    Plan plan = fx.ops().planImport(importOf(archive, true));

    assertThat(plan.summary().resourcesTouched()).containsExactly("/public/a");
    assertThat(plan.summary().warnings())
        .anyMatch(w -> w.contains("/public/b does not exist on this server yet"));
  }

  @Test
  void should_skip_the_snapshot_when_none_of_the_sidecar_uris_exist_yet() throws IOException {
    sidecar(List.of("/public/new"), false);
    adapter.existing = Optional.of(Set.of());

    Plan plan = fx.ops().planImport(importOf(archive, false));

    assertThat(plan.steps().stream().map(Step::id))
        .noneMatch(id -> id.startsWith("snapshot") || id.contains("restore"));
    assertThat(plan.summary().warnings()).anyMatch(w -> w.contains("no pre-import snapshot"));
    assertThat(plan.summary().backupLocations()).isEmpty();
  }

  @Test
  void should_use_sidecar_uris_for_snapshot_when_sidecar_is_a_subtree_export() throws IOException {
    sidecar(List.of("/organizations/org_1", "/public/reports"), false);

    Plan plan = fx.ops().planImport(importOf(archive, true));

    assertThat(plan.summary().resourcesTouched())
        .containsExactly("/organizations/org_1", "/public/reports");
    // issue #100: the listing names the same folders, then the export step follows
    assertThat(plan.steps().get(2).detail())
        .startsWith("every URI under ")
        .contains("/organizations/org_1")
        .contains("/public/reports");
    assertThat(plan.steps().get(3).detail())
        .startsWith("uris ")
        .contains("/organizations/org_1")
        .contains("/public/reports");
  }
}
