package com.jaspersoft.jrsctl.ops.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.jaspersoft.jrsctl.core.engine.Plan;
import com.jaspersoft.jrsctl.core.engine.RunOutcome;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * ADR-0052, spec §10.5: the run against a fake buildomatic. The schema is created, initialised and
 * loaded in the vendor's order; nothing is created when a refusal applies; a failure drops only the
 * schema this run created, and refuses to drop when default_master.properties no longer names it.
 */
class RepositoryLoadRunTest {

  @TempDir Path tmp;

  private static int indexOf(String text, String needle) {
    int i = text.indexOf(needle);
    assertThat(i).as(needle + " in\n" + text).isNotNegative();
    return i;
  }

  @Test
  void should_create_initialise_and_load_the_schema_in_the_vendor_order() throws Exception {
    try (LoadFixture f = LoadFixture.create(tmp)) {
      Plan plan = f.ops().planLoad(f.options());

      RunOutcome outcome = f.run(plan, "r-load");

      assertThat(outcome).as(String.join("\n", f.logs())).isInstanceOf(RunOutcome.Succeeded.class);
      String log = f.vendorLogText();
      int create = indexOf(log, "js-ant create-js-db");
      int init = indexOf(log, "js-ant init-js-db-pro");
      int minimal = indexOf(log, "js-ant import-minimal-pro");
      int load = indexOf(log, "js-import --input-zip");
      assertThat(create).isLessThan(init);
      assertThat(init).isLessThan(minimal);
      assertThat(minimal).isLessThan(load);
      assertThat(log).contains("--include-server-settings").doesNotContain("--update");
      assertThat(log).doesNotContain("--include-access-events");
      assertThat(f.schemaExists()).isTrue();
      assertThat(SchemaDrop.recorded(f.ctx("r-load"))).contains(LoadFixture.SCHEMA);
      Path record = f.home().snapshots().resolve("r-load").resolve(LoadSteps.RECORD_FILE);
      assertThat(record).exists();
      assertThat(Files.readString(record))
          .contains(LoadFixture.SCHEMA)
          .contains("js-8.2-export.zip");
      assertThat(f.store().auditRows(20))
          .anyMatch(a -> a.action().equals(LoadSteps.AUDIT_COMPLETED));
      assertThat(f.jdbc.executed).noneMatch(sql -> sql.startsWith("DROP DATABASE"));
    }
  }

  /** The target server is usually not deployed yet when the schema is loaded. */
  @Test
  void should_load_when_the_configured_server_is_not_reachable_yet() throws Exception {
    try (LoadFixture f = LoadFixture.create(tmp)) {
      f.fake.unreachable = true;
      f.services = f.fake.build();

      RunOutcome outcome = f.run(f.ops().planLoad(f.options()), "r-load");

      assertThat(outcome).as(String.join("\n", f.logs())).isInstanceOf(RunOutcome.Succeeded.class);
    }
  }

  @Test
  void should_refuse_before_creating_anything_when_js_dbname_differs_from_the_schema()
      throws Exception {
    try (LoadFixture f = LoadFixture.create(tmp)) {
      Plan plan = f.ops().planLoad(f.options());
      f.master(LoadFixture.MASTER.replace("js.dbName=jrs_101", "js.dbName=jasperserver"));

      RunOutcome outcome = f.run(plan, "r-load");

      // the staged properties changed since planning: the fingerprint says so first
      assertThat(outcome)
          .isInstanceOfAny(RunOutcome.PrecheckFailed.class, RunOutcome.FingerprintMismatch.class);
      assertThat(f.vendorLogText()).isEmpty();
      assertThat(f.schemaExists()).isFalse();
    }
  }

  @Test
  void should_refuse_before_creating_anything_when_the_schema_already_exists() throws Exception {
    try (LoadFixture f = LoadFixture.create(tmp)) {
      f.schemaExists(true);

      RunOutcome outcome = f.run(f.ops().planLoad(f.options()), "r-load");

      assertThat(outcome).isInstanceOf(RunOutcome.PrecheckFailed.class);
      RunOutcome.PrecheckFailed failed = (RunOutcome.PrecheckFailed) outcome;
      assertThat(failed.stepId()).isEqualTo(LoadSteps.VERIFY_TARGET_SCHEMA);
      assertThat(failed.message()).contains(LoadFixture.SCHEMA).contains("exists");
      assertThat(failed.remediation()).contains("DBA");
      assertThat(f.vendorLogText()).isEmpty();
      assertThat(f.jdbc.executed).noneMatch(sql -> sql.startsWith("DROP DATABASE"));
    }
  }

  @Test
  void should_refuse_before_creating_anything_when_the_buildomatic_would_touch_an_app_server()
      throws Exception {
    try (LoadFixture f =
        LoadFixture.createWithMaster(
            tmp,
            LoadFixture.MASTER.replace(
                "appServerType=skipAppServerCheck", "appServerType=tomcat"))) {
      RunOutcome outcome = f.run(f.ops().planLoad(f.options()), "r-load");

      assertThat(outcome).isInstanceOf(RunOutcome.PrecheckFailed.class);
      assertThat(((RunOutcome.PrecheckFailed) outcome).message()).contains("appServerType");
      assertThat(f.vendorLogText()).isEmpty();
    }
  }

  @Test
  void should_drop_only_the_schema_this_run_created_when_a_later_step_fails() throws Exception {
    try (LoadFixture f = LoadFixture.create(tmp)) {
      f.failTarget("import-minimal-pro");

      RunOutcome outcome = f.run(f.ops().planLoad(f.options()), "r-load");

      assertThat(outcome).as(String.join("\n", f.logs())).isInstanceOf(RunOutcome.RolledBack.class);
      assertThat(f.jdbc.executed)
          .filteredOn(sql -> sql.startsWith("DROP DATABASE"))
          .containsExactly("DROP DATABASE \"" + LoadFixture.SCHEMA + "\"");
      assertThat(f.schemaExists()).isFalse();
      assertThat(f.vendorLogText()).doesNotContain("js-import --input-zip");
    }
  }

  @Test
  void should_drop_the_schema_when_the_import_of_the_export_fails() throws Exception {
    try (LoadFixture f = LoadFixture.create(tmp)) {
      f.failImport();

      RunOutcome outcome = f.run(f.ops().planLoad(f.options()), "r-load");

      assertThat(outcome).as(String.join("\n", f.logs())).isInstanceOf(RunOutcome.RolledBack.class);
      assertThat(f.schemaExists()).isFalse();
      assertThat(f.home().snapshots().resolve("r-load").resolve(LoadSteps.RECORD_FILE))
          .doesNotExist();
    }
  }

  /**
   * The guard of ADR-0052: a stale or edited default_master.properties cannot redirect the drop.
   */
  @Test
  void should_refuse_to_drop_when_default_master_no_longer_names_the_created_schema()
      throws Exception {
    try (LoadFixture f = LoadFixture.create(tmp)) {
      Plan plan = f.ops().planLoad(f.options());
      // the fake create-js-db also rewrites the properties, as an operator editing them mid-run
      f.failTarget("import-minimal-pro");
      f.jdbc.onExecute = sql -> {};
      String edited = LoadFixture.MASTER.replace("js.dbName=jrs_101", "js.dbName=production");
      f.jdbc.rowAnswer =
          sql -> {
            if (sql.contains("pg_database")) {
              try {
                f.master(edited);
              } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
              }
              return f.schemaExists();
            }
            return true;
          };

      RunOutcome outcome = f.run(plan, "r-load");

      assertThat(outcome).as(String.join("\n", f.logs())).isInstanceOf(RunOutcome.Failed.class);
      RunOutcome.Failed failed = (RunOutcome.Failed) outcome;
      assertThat(failed.rollbackIncomplete()).isTrue();
      // the runner reports the compensation's refusal as the cause, so the DBA's statement is there
      assertThat(failed.cause()).contains("production").contains("DROP DATABASE");
      assertThat(f.jdbc.executed).noneMatch(sql -> sql.startsWith("DROP DATABASE"));
      assertThat(f.schemaExists()).isTrue();
    }
  }
}
