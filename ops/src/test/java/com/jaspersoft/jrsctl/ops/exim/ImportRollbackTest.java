package com.jaspersoft.jrsctl.ops.exim;

import static org.assertj.core.api.Assertions.assertThat;

import com.jaspersoft.jrsctl.core.engine.Plan;
import com.jaspersoft.jrsctl.core.engine.RunOutcome;
import com.jaspersoft.jrsctl.core.event.Event;
import com.jaspersoft.jrsctl.jrs.api.ExportImportStrategy;
import com.jaspersoft.jrsctl.jrs.api.ExportRequest;
import com.jaspersoft.jrsctl.jrs.api.Handles;
import com.jaspersoft.jrsctl.jrs.strategy.Sidecar;
import com.jaspersoft.jrsctl.ops.exim.ExportImportOperations.ImportOptions;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The import plan executed by the real Runner against the scriptable fake adapter. */
class ImportRollbackTest {

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
    Files.write(archive, new byte[] {'P', 'K', 3, 4, 5, 6});
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
                false),
            "0000",
            ExportImportStrategy.Kind.REST));
  }

  @AfterEach
  void tearDown() {
    fx.close();
  }

  private ImportOptions options(boolean update) {
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

  @Test
  void should_snapshot_then_import_when_server_reports_ready() {
    Plan plan = fx.ops().planImport(options(false));

    RunOutcome outcome = fx.run(plan, EximFixture.RUN);

    assertThat(outcome).as(fx.events.toString()).isInstanceOf(RunOutcome.Succeeded.class);
    Path snapshot = plan.summary().backupLocations().get(0);
    assertThat(snapshot).exists();
    assertThat(Sidecar.pathFor(snapshot)).exists();
    assertThat(adapter.exports).hasSize(1);
    assertThat(adapter.exports.get(0).uris()).containsExactly("/public");
    assertThat(adapter.exports.get(0).output()).isEqualTo(snapshot);
    assertThat(adapter.imports).hasSize(1);
    assertThat(adapter.imports.get(0).archive()).isEqualTo(archive);
    assertThat(adapter.imports.get(0).request().update()).isFalse();
    assertThat(fx.journal(EximFixture.RUN))
        .containsSubsequence(
            "precheck.import.check-keystore:SUCCEEDED",
            "backup.pre-import-snapshot:SUCCEEDED",
            "backup.export.start:SUCCEEDED",
            "backup.export.sidecar:SUCCEEDED",
            "import.snapshot-rollback:SUCCEEDED",
            "import.start:SUCCEEDED",
            "import.poll:SUCCEEDED",
            "import.verify:SUCCEEDED");
  }

  @Test
  void should_reimport_snapshot_with_update_and_exit_3_when_import_fails() {
    adapter.importPhases.add(Handles.Phase.FAILED);
    Plan plan = fx.ops().planImport(options(false));

    RunOutcome outcome = fx.run(plan, EximFixture.RUN);

    assertThat(outcome).as(fx.events.toString()).isInstanceOf(RunOutcome.RolledBack.class);
    assertThat(outcome.exitCode()).isEqualTo(3);
    assertThat(((RunOutcome.RolledBack) outcome).rolledBackToPhase()).isEqualTo("import");
    Path snapshot = plan.summary().backupLocations().get(0);
    assertThat(snapshot).as("the snapshot survives the rollback").exists();
    assertThat(adapter.imports).hasSize(2);
    assertThat(adapter.imports.get(0).archive()).isEqualTo(archive);
    assertThat(adapter.imports.get(0).request().update()).isFalse();
    assertThat(adapter.imports.get(1).archive()).isEqualTo(snapshot);
    assertThat(adapter.imports.get(1).request().update()).isTrue();
    assertThat(fx.journal(EximFixture.RUN))
        .containsSubsequence(
            "import.start:SUCCEEDED",
            "import.poll:FAILED",
            "import.start:ROLLED_BACK",
            "import.snapshot-rollback:ROLLED_BACK");
    assertThat(fx.services.home().runDir(EximFixture.RUN + "-restore"))
        .as("the restore keeps its own run-scoped files")
        .isDirectory();
    assertThat(fx.events)
        .filteredOn(e -> e instanceof Event.Log)
        .map(e -> ((Event.Log) e).message())
        .anyMatch(m -> m.contains("re-imported"));
  }

  /**
   * A restore that fails is no rollback: when the re-import of the snapshot fails as well, the run
   * ends rollback-incomplete (exit 4). Issue #40 reached exit 3 here because the vendor re-import
   * that threw was counted as a success.
   */
  @Test
  void should_exit_4_when_the_snapshot_reimport_fails_too() {
    adapter.importPhases.add(Handles.Phase.FAILED);
    adapter.importPhases.add(Handles.Phase.FAILED);
    Plan plan = fx.ops().planImport(options(false));

    RunOutcome outcome = fx.run(plan, EximFixture.RUN);

    assertThat(outcome).as(fx.events.toString()).isInstanceOf(RunOutcome.Failed.class);
    assertThat(outcome.exitCode()).isEqualTo(4);
    assertThat(((RunOutcome.Failed) outcome).rollbackIncomplete()).isTrue();
    assertThat(adapter.imports).hasSize(2);
    assertThat(fx.journal(EximFixture.RUN)).doesNotContain("import.snapshot-rollback:ROLLED_BACK");
  }

  /**
   * Issue #41 used to let an import proceed on a snapshot holding a lone {@code resources/} entry
   * and no {@code index.xml}, with a rollback that re-imported nothing. Since field test 2 (E3)
   * planning leaves out folders that do not exist yet, so such an archive at run time means the
   * server exported nothing for a folder it said existed: the snapshot step fails, nothing is
   * imported, and there is no rollback copy to pretend with.
   */
  @Test
  void should_stop_before_the_import_when_the_snapshot_holds_no_index() throws IOException {
    adapter.exportArchive = zipOf("resources/");
    adapter.importPhases.add(Handles.Phase.FAILED);
    Plan plan = fx.ops().planImport(options(false));

    RunOutcome outcome = fx.run(plan, EximFixture.RUN);

    assertThat(outcome).as(fx.events.toString()).isInstanceOf(RunOutcome.RolledBack.class);
    assertThat(outcome.exitCode()).isEqualTo(3);
    assertThat(adapter.imports).as("nothing was imported").isEmpty();
    assertThat(((RunOutcome.RolledBack) outcome).cause())
        .contains("holds no index.xml")
        .contains("no resource matched");
  }

  @Test
  void should_judge_only_a_readable_archive_without_index_xml_as_holding_no_resources()
      throws IOException {
    Path empty = Files.write(tmp.resolve("empty.zip"), zipOf("resources/"));
    Path full = Files.write(tmp.resolve("full.zip"), zipOf("index.xml", "resources/"));
    Path indexLast = Files.write(tmp.resolve("last.zip"), zipOf("resources/", "index.xml"));
    Path garbage = Files.write(tmp.resolve("garbage.zip"), new byte[] {'P', 'K', 0, 0});

    assertThat(RestoreFromPreImportSnapshot.holdsNoResources(empty)).isTrue();
    assertThat(RestoreFromPreImportSnapshot.holdsNoResources(full)).isFalse();
    assertThat(RestoreFromPreImportSnapshot.holdsNoResources(indexLast)).isFalse();
    assertThat(RestoreFromPreImportSnapshot.holdsNoResources(garbage)).isFalse();
    assertThat(RestoreFromPreImportSnapshot.holdsNoResources(tmp.resolve("missing.zip"))).isFalse();
  }

  private static byte[] zipOf(String... names) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
      for (String name : names) {
        zip.putNextEntry(new ZipEntry(name));
        zip.closeEntry();
      }
    }
    return bytes.toByteArray();
  }

  @Test
  void should_exit_2_without_snapshot_when_keystore_fingerprint_mismatches() throws IOException {
    Sidecar.write(
        Sidecar.pathFor(archive),
        new Sidecar(
            Instant.parse("2026-09-01T00:00:00Z"),
            "other",
            "8.2.0",
            Optional.of("ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff"),
            new Sidecar.Flags(
                ExportRequest.Scope.REPOSITORY,
                List.of("/public"),
                true,
                false,
                false,
                false,
                false,
                false),
            "0000",
            ExportImportStrategy.Kind.REST));
    Plan plan = fx.ops().planImport(options(false));

    RunOutcome outcome = fx.run(plan, EximFixture.RUN);

    assertThat(outcome).isInstanceOf(RunOutcome.PrecheckFailed.class);
    assertThat(outcome.exitCode()).isEqualTo(2);
    assertThat(((RunOutcome.PrecheckFailed) outcome).remediation()).contains("--source-keystore");
    assertThat(adapter.exports).as("nothing snapshotted").isEmpty();
    assertThat(adapter.imports).isEmpty();
    assertThat(plan.summary().backupLocations().get(0)).doesNotExist();
  }
}
