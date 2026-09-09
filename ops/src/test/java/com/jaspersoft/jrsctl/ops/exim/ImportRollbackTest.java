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
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
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
