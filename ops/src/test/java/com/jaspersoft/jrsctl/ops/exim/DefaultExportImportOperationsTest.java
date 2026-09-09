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
    Sidecar.write(
        Sidecar.pathFor(archive),
        new Sidecar(
            Instant.parse("2026-09-01T00:00:00Z"),
            "srv",
            "8.2.0",
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

  @Test
  void should_order_precheck_backup_then_import_when_planning_a_rest_import() throws IOException {
    sidecar(List.of("/public"), false);

    Plan plan = fx.ops().planImport(importOf(archive, false));

    assertThat(phases(plan)).containsExactly("precheck", "backup", "import");
    assertThat(ids(plan))
        .containsExactly(
            "precheck.import.check-keystore",
            "backup.pre-import-snapshot",
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
        .contains(DefaultExportImportOperations.BEST_EFFORT_WARNING)
        .noneMatch(w -> w.contains("no sidecar"));
    assertThat(plan.summary().strategy()).startsWith("rest (").contains("IMPORT_ASYNC");
    assertThat(plan.summary().serviceRestart()).isFalse();
    assertThat(plan.steps().get(1).detail()).startsWith("/public -> ");
    assertThat(plan.steps().get(2).detail()).isEqualTo("uris /public");
    assertThat(plan.steps().get(6).mutating()).isTrue();
    assertThat(adapter.imports).as("planning must not import").isEmpty();
  }

  @Test
  void should_snapshot_full_server_when_update_targets_root_without_sidecar() {
    Plan plan = fx.ops().planImport(importOf(archive, true));

    assertThat(plan.summary().warnings())
        .contains(DefaultExportImportOperations.BEST_EFFORT_WARNING)
        .anyMatch(w -> w.contains("no sidecar"))
        .anyMatch(w -> w.contains("--update"));
    assertThat(plan.summary().resourcesTouched()).containsExactly("/");
    assertThat(plan.steps().get(1).detail()).startsWith("full server -> ");
    assertThat(plan.steps().get(2).detail()).isEqualTo("everything");
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

  @Test
  void should_refuse_planning_when_archive_is_missing() {
    assertThatThrownBy(() -> fx.ops().planImport(importOf(tmp.resolve("nope.zip"), false)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("nope.zip");
    assertThat(adapter.imports).isEmpty();
  }

  @Test
  void should_use_sidecar_uris_for_snapshot_when_sidecar_is_a_subtree_export() throws IOException {
    sidecar(List.of("/organizations/org_1", "/public/reports"), false);

    Plan plan = fx.ops().planImport(importOf(archive, true));

    assertThat(plan.summary().resourcesTouched())
        .containsExactly("/organizations/org_1", "/public/reports");
    assertThat(plan.steps().get(2).detail())
        .startsWith("uris ")
        .contains("/organizations/org_1")
        .contains("/public/reports");
  }
}
