package com.jaspersoft.jrsctl.ops.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.jaspersoft.jrsctl.core.engine.Context;
import com.jaspersoft.jrsctl.core.engine.Plan;
import com.jaspersoft.jrsctl.core.engine.Step;
import com.jaspersoft.jrsctl.ops.Idempotency;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Phase 8 idempotency of every {@code repository load} step (spec §6.1, §10.5): a step executed
 * again after a complete execution runs no second vendor command and leaves the home, the
 * buildomatic and the catalogue as one execution did; a compensation run twice drops the schema
 * once.
 */
class RepositoryStepIdempotencyTest {

  @TempDir Path tmp;

  private static Map<String, String> state(LoadFixture f) throws IOException {
    Map<String, String> m = new TreeMap<>();
    m.putAll(Idempotency.tree("buildomatic", f.buildomaticDir));
    m.putAll(Idempotency.tree("snapshots", f.home().snapshots()));
    m.putAll(Idempotency.tree("runs", f.home().runs()));
    m.put("schema", Boolean.toString(f.schemaExists()));
    m.put("vendor-log", f.vendorLogText());
    m.put(
        "drops", Long.toString(f.jdbc.executed.stream().filter(s -> s.startsWith("DROP")).count()));
    return m;
  }

  private static Context start(LoadFixture f, Plan plan, String runId) {
    f.store().recordRunStart(runId, plan.summary().operation(), Optional.empty(), Instant.EPOCH);
    return f.ctx(runId);
  }

  private static void assertReexecutionConverges(LoadFixture f, Plan plan, String runId, String id)
      throws IOException {
    Context ctx = start(f, plan, runId);
    Idempotency.runUpTo(plan, ctx, id, f.events::add);
    Map<String, String> once = state(f);
    Idempotency.executeOk(Idempotency.step(plan, id), ctx, f.events::add);
    assertThat(state(f)).as(String.join("\n", f.logs())).isEqualTo(once);
  }

  private static void assertCompensationConverges(LoadFixture f, Plan plan, String runId, String id)
      throws IOException {
    Context ctx = start(f, plan, runId);
    Idempotency.runAll(plan, ctx, f.events::add);
    Step step = Idempotency.step(plan, id);
    Idempotency.compensateOk(step, ctx, f.events::add);
    Map<String, String> once = state(f);
    assertThat(once.get("schema")).isEqualTo("false");
    assertThat(once.get("drops")).isEqualTo("1");
    Idempotency.compensateOk(step, ctx, f.events::add);
    assertThat(state(f)).as(String.join("\n", f.logs())).isEqualTo(once);
  }

  @Test
  void should_not_mutate_when_read_only_load_steps_execute_twice() throws Exception {
    try (LoadFixture f = LoadFixture.create(tmp)) {
      Plan plan = f.ops().planLoad(f.options());
      Context ctx = start(f, plan, "r-ro");
      List<String> readOnly =
          List.of(
              LoadSteps.DOCTOR,
              LoadSteps.VERIFY_BUILDOMATIC,
              LoadSteps.VERIFY_TARGET_SCHEMA,
              LoadSteps.ADOPT_EXPORT);
      List<String> seen = new ArrayList<>();
      for (Step step : plan.steps()) {
        if (!readOnly.contains(step.id())) {
          break;
        }
        assertThat(step.mutating()).as(step.id()).isFalse();
        Map<String, String> before = state(f);
        Idempotency.executeOk(step, ctx, f.events::add);
        Idempotency.executeOk(step, ctx, f.events::add);
        Idempotency.compensateOk(step, ctx, f.events::add);
        assertThat(state(f)).as(step.id()).isEqualTo(before);
        seen.add(step.id());
      }
      assertThat(seen).containsExactlyElementsOf(readOnly);
    }
  }

  @Test
  void should_create_the_schema_once_when_create_js_db_executes_twice() throws Exception {
    try (LoadFixture f = LoadFixture.create(tmp)) {
      assertReexecutionConverges(f, f.ops().planLoad(f.options()), "r-c", LoadSteps.CREATE_JS_DB);
      assertThat(f.vendorLogText().split("create-js-db", -1)).hasSize(2);
    }
  }

  @Test
  void should_initialise_once_when_init_js_db_executes_twice() throws Exception {
    try (LoadFixture f = LoadFixture.create(tmp)) {
      assertReexecutionConverges(f, f.ops().planLoad(f.options()), "r-i", "init-js-db-pro");
    }
  }

  @Test
  void should_import_the_minimal_content_once_when_import_minimal_executes_twice()
      throws Exception {
    try (LoadFixture f = LoadFixture.create(tmp)) {
      assertReexecutionConverges(f, f.ops().planLoad(f.options()), "r-m", "import-minimal-pro");
    }
  }

  @Test
  void should_import_the_export_once_when_import_export_executes_twice() throws Exception {
    try (LoadFixture f = LoadFixture.create(tmp)) {
      assertReexecutionConverges(f, f.ops().planLoad(f.options()), "r-e", LoadSteps.IMPORT_EXPORT);
      assertThat(f.vendorLogText().split("js-import --input-zip", -1)).hasSize(2);
    }
  }

  @Test
  void should_write_the_same_record_when_record_load_executes_twice() throws Exception {
    try (LoadFixture f = LoadFixture.create(tmp)) {
      assertReexecutionConverges(f, f.ops().planLoad(f.options()), "r-r", LoadSteps.RECORD_LOAD);
    }
  }

  @Test
  void should_drop_the_schema_once_when_create_js_db_compensates_twice() throws Exception {
    try (LoadFixture f = LoadFixture.create(tmp)) {
      assertCompensationConverges(f, f.ops().planLoad(f.options()), "r-cc", LoadSteps.CREATE_JS_DB);
    }
  }

  @Test
  void should_drop_the_schema_once_when_init_js_db_compensates_twice() throws Exception {
    try (LoadFixture f = LoadFixture.create(tmp)) {
      assertCompensationConverges(f, f.ops().planLoad(f.options()), "r-ic", "init-js-db-pro");
    }
  }

  @Test
  void should_drop_the_schema_once_when_import_minimal_compensates_twice() throws Exception {
    try (LoadFixture f = LoadFixture.create(tmp)) {
      assertCompensationConverges(f, f.ops().planLoad(f.options()), "r-mc", "import-minimal-pro");
    }
  }

  @Test
  void should_drop_the_schema_once_when_import_export_compensates_twice() throws Exception {
    try (LoadFixture f = LoadFixture.create(tmp)) {
      assertCompensationConverges(
          f, f.ops().planLoad(f.options()), "r-ec", LoadSteps.IMPORT_EXPORT);
    }
  }

  @Test
  void should_remove_the_record_once_when_record_load_compensates_twice() throws Exception {
    try (LoadFixture f = LoadFixture.create(tmp)) {
      Plan plan = f.ops().planLoad(f.options());
      Context ctx = start(f, plan, "r-rc");
      Idempotency.runAll(plan, ctx, f.events::add);
      Step step = Idempotency.step(plan, LoadSteps.RECORD_LOAD);
      Idempotency.compensateOk(step, ctx, f.events::add);
      Map<String, String> once = state(f);
      assertThat(f.home().snapshots().resolve("r-rc").resolve(LoadSteps.RECORD_FILE))
          .doesNotExist();
      Idempotency.compensateOk(step, ctx, f.events::add);
      assertThat(state(f)).isEqualTo(once);
    }
  }
}
