package com.jaspersoft.jrsctl.ops.exim;

import static org.assertj.core.api.Assertions.assertThat;

import com.jaspersoft.jrsctl.core.engine.CheckResult;
import com.jaspersoft.jrsctl.core.engine.Context;
import com.jaspersoft.jrsctl.core.engine.Plan;
import com.jaspersoft.jrsctl.core.engine.RetryPolicy;
import com.jaspersoft.jrsctl.core.engine.RunOutcome;
import com.jaspersoft.jrsctl.core.engine.Step;
import com.jaspersoft.jrsctl.core.engine.StepResult;
import com.jaspersoft.jrsctl.core.event.EventSink;
import com.jaspersoft.jrsctl.jrs.api.ExportImportStrategy;
import com.jaspersoft.jrsctl.jrs.api.ExportRequest;
import com.jaspersoft.jrsctl.jrs.strategy.Sidecar;
import com.jaspersoft.jrsctl.ops.Idempotency;
import com.jaspersoft.jrsctl.ops.exim.ExportImportOperations.ImportOptions;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Phase 8 idempotency of the ops-level export/import steps (spec §6.1, §9.4): the read-only
 * snapshot announcement writes nothing however often it runs, a rephased step is its delegate in
 * every respect, and the snapshot-rollback anchor's compensation re-imports the snapshot once even
 * when it is run twice, because the nested restore run keeps its own task handle.
 */
class EximStepIdempotencyTest {

  @TempDir Path tmp;

  private static Map<String, String> files(Path root) throws IOException {
    return Idempotency.tree(
        "tmp",
        root,
        p ->
            p.getFileName().toString().contains("state.db") ? Optional.of("db") : Optional.empty());
  }

  @Test
  void should_not_mutate_when_pre_import_snapshot_executes_twice() throws IOException {
    try (EximFixture fx = new EximFixture(tmp, EximFakeAdapter::new)) {
      ExportRequest request =
          new ExportRequest(
              ExportRequest.Scope.REPOSITORY,
              Set.of("/public"),
              true,
              false,
              false,
              false,
              false,
              false,
              tmp.resolve("snap").resolve("pre.zip"));
      Step step = new PreImportSnapshot(request, ExportImportStrategy.Kind.REST);
      Context ctx = fx.context("r-pre");
      Map<String, String> before = files(tmp);

      assertThat(step.mutating()).isFalse();
      Idempotency.executeOk(step, ctx);
      Idempotency.executeOk(step, ctx);
      Idempotency.compensateOk(step, ctx);

      assertThat(files(tmp)).isEqualTo(before);
    }
  }

  @Test
  void should_delegate_unchanged_when_rephased_step_executes_twice() throws IOException {
    try (EximFixture fx = new EximFixture(tmp, EximFakeAdapter::new)) {
      CountingStep inner = new CountingStep();
      Step rephased = Rephased.into("backup", inner);
      Context ctx = fx.context("r-re");

      Idempotency.executeOk(rephased, ctx);
      Idempotency.executeOk(rephased, ctx);
      Idempotency.compensateOk(rephased, ctx);

      assertThat(rephased.id()).isEqualTo("backup.inner");
      assertThat(rephased.phase()).isEqualTo("backup");
      assertThat(rephased.title()).isEqualTo("inner title");
      assertThat(rephased.detail()).isEqualTo("inner detail");
      assertThat(rephased.mutating()).isTrue();
      assertThat(rephased.irreversible()).isFalse();
      assertThat(rephased.retryPolicy()).isEqualTo(RetryPolicy.HTTP_DEFAULT);
      assertThat(inner.executions).isEqualTo(2);
      assertThat(inner.compensations).isEqualTo(1);
    }
  }

  @Test
  void should_reimport_the_snapshot_once_when_restore_from_pre_import_snapshot_compensates_twice()
      throws IOException {
    EximFakeAdapter adapter = new EximFakeAdapter();
    try (EximFixture fx = new EximFixture(tmp, () -> adapter)) {
      Path archive = tmp.resolve("in").resolve("public.zip");
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
      Plan plan =
          fx.ops()
              .planImport(
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
                      Optional.empty()));
      assertThat(fx.run(plan, EximFixture.RUN)).isInstanceOf(RunOutcome.Succeeded.class);
      assertThat(adapter.imports).hasSize(1);
      Step anchor = Idempotency.step(plan, RestoreFromPreImportSnapshot.ID);
      Context ctx = fx.context(EximFixture.RUN);
      Path snapshot = plan.summary().backupLocations().get(0);

      Idempotency.compensateOk(anchor, ctx);
      Idempotency.compensateOk(anchor, ctx);

      assertThat(adapter.imports)
          .as("one restore import, reused by the second compensation")
          .hasSize(2);
      assertThat(adapter.imports.get(1).archive()).isEqualTo(snapshot);
      assertThat(adapter.imports.get(1).request().update()).isTrue();
      assertThat(
              fx.services.home().runDir(EximFixture.RUN + "-restore").resolve("import-handle.txt"))
          .hasContent("imp-2");
    }
  }

  /** Counts executions and compensations. */
  private static final class CountingStep implements Step {
    int executions;
    int compensations;

    @Override
    public String id() {
      return "inner";
    }

    @Override
    public String title() {
      return "inner title";
    }

    @Override
    public String phase() {
      return "export";
    }

    @Override
    public String detail() {
      return "inner detail";
    }

    @Override
    public RetryPolicy retryPolicy() {
      return RetryPolicy.HTTP_DEFAULT;
    }

    @Override
    public CheckResult precheck(Context ctx) {
      return CheckResult.pass();
    }

    @Override
    public StepResult execute(Context ctx, EventSink out) {
      executions++;
      return StepResult.ok();
    }

    @Override
    public StepResult compensate(Context ctx, EventSink out) {
      compensations++;
      return StepResult.ok();
    }
  }
}
