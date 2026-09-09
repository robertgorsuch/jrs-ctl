package com.jaspersoft.jrsctl.ops.hotfix;

import static org.assertj.core.api.Assertions.assertThat;

import com.jaspersoft.jrsctl.core.engine.Plan;
import com.jaspersoft.jrsctl.core.engine.RunOutcome;
import com.jaspersoft.jrsctl.ops.db.JdbcException;
import com.jaspersoft.jrsctl.ops.hotfix.HotfixOperations.ApplyOptions;
import com.jaspersoft.jrsctl.ops.hotfix.HotfixOperations.RollbackOptions;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HotfixSqlTest {

  private static final ApplyOptions SIGNED = new ApplyOptions(false);
  private static final String FORWARD =
      "-- create the fix table\nCREATE TABLE fix (id int);\nINSERT INTO fix\n  VALUES (1);\n";
  private static final String ROLLBACK = "DROP TABLE fix;\n";

  @TempDir Path tmp;

  private static String sqlManifest(String rollback, String extra) {
    return """
        {
          "id": "%s",
          "version": "1",
          "title": "Schema fix",
          "applies": { "versions": [">=8.0.0 <9.0.0"] },
          "files": [ { "action": "add", "path": "%s" } ],
          "sql": [ { "db": "postgresql", "file": "sql/postgresql/001.sql", "idempotent": true%s } ],
          "restart": "none",
          "postchecks": [ { "type": "http", "url": "/login.html", "expect": 200 } ],
          "rollback": "%s"%s
        }
        """
        .formatted(
            HotfixFixture.ID,
            HotfixFixture.SCRIPT,
            rollback.equals("snapshot")
                ? ", \"rollbackFile\": \"sql/postgresql/001-rollback.sql\""
                : "",
            rollback,
            extra);
  }

  private static Map<String, String> files(boolean withRollback) {
    Map<String, String> files = new HashMap<>();
    files.put("payload/" + HotfixFixture.SCRIPT, HotfixFixture.SCRIPT_BYTES);
    files.put("sql/postgresql/001.sql", FORWARD);
    if (withRollback) {
      files.put("sql/postgresql/001-rollback.sql", ROLLBACK);
    }
    return files;
  }

  @Test
  void should_run_scripts_through_connector_and_roll_them_back_when_rolled_back()
      throws IOException {
    try (HotfixFixture f = HotfixFixture.create(tmp, "")) {
      // no database section: validate-manifest must refuse
      Path zip = f.build(f.bundleDir("sql", sqlManifest("snapshot", ""), files(true)));
      Plan noDb = f.ops().planApply(zip, SIGNED);
      assertThat(HotfixFixture.ids(noDb)).doesNotContain("apply-sql");
      assertThat(noDb.summary().warnings()).anyMatch(w -> w.contains("database section"));
      RunOutcome refused = f.run(noDb, "r-nodb");
      assertThat(refused).isInstanceOf(RunOutcome.PrecheckFailed.class);
      assertThat(((RunOutcome.PrecheckFailed) refused).message()).contains("database section");
    }
    try (HotfixFixture f = withDatabase(tmp.resolve("with-db"))) {
      Path zip = f.build(f.bundleDir("sql", sqlManifest("snapshot", ""), files(true)));
      Plan plan = f.ops().planApply(zip, SIGNED);
      assertThat(HotfixFixture.ids(plan))
          .containsExactly(
              "verify-signature",
              "validate-manifest",
              "preflight",
              "run-prechecks",
              "snapshot",
              "stage-files",
              "atomic-swap",
              "apply-sql",
              "run-postchecks",
              "record-installed");
      assertThat(HotfixFixture.step(plan, "apply-sql").irreversible()).isFalse();
      assertThat(f.run(plan, "r-sql")).isInstanceOf(RunOutcome.Succeeded.class);
      assertThat(f.jdbc.executed)
          .containsExactly(
              "SELECT 1", "CREATE TABLE fix (id int)", "INSERT INTO fix\n  VALUES (1)");
      assertThat(f.jdbc.connections).allMatch(c -> c.contains("jdbc:postgresql://db.example/jrs"));

      f.jdbc.executed.clear();
      Plan rollback = f.ops().planRollback(HotfixFixture.ID, new RollbackOptions(false));
      assertThat(HotfixFixture.ids(rollback))
          .containsExactly("restore-snapshot", "run-sql-rollback", "record-rolled-back");
      assertThat(f.run(rollback, "r-sql-rollback")).isInstanceOf(RunOutcome.Succeeded.class);
      assertThat(f.jdbc.executed).containsExactly("DROP TABLE fix");
      assertThat(f.target(HotfixFixture.SCRIPT)).doesNotExist();
    }
  }

  @Test
  void should_run_rollback_scripts_when_a_later_step_fails() throws IOException {
    try (HotfixFixture f = withDatabase(tmp)) {
      Path zip = f.build(f.bundleDir("sql", sqlManifest("snapshot", ""), files(true)));
      f.httpStatuses.put("/login.html", 503);
      RunOutcome outcome = f.run(f.ops().planApply(zip, SIGNED), "r-sql-fail");
      assertThat(outcome).isInstanceOf(RunOutcome.RolledBack.class);
      assertThat(((RunOutcome.RolledBack) outcome).cause()).contains("got 503");
      assertThat(f.jdbc.executed)
          .containsExactly(
              "SELECT 1",
              "CREATE TABLE fix (id int)",
              "INSERT INTO fix\n  VALUES (1)",
              "DROP TABLE fix");
      assertThat(f.target(HotfixFixture.SCRIPT)).doesNotExist();
      assertThat(f.ops().list()).isEmpty();
    }
  }

  @Test
  void should_mark_apply_sql_irreversible_and_warn_when_manifest_says_so() throws IOException {
    try (HotfixFixture f = withDatabase(tmp)) {
      Path zip =
          f.build(
              f.bundleDir(
                  "irreversible",
                  sqlManifest(
                      "irreversible", ", \"rollbackNote\": \"restore the database from backup\""),
                  files(false)));
      Plan plan = f.ops().planApply(zip, SIGNED);
      assertThat(HotfixFixture.step(plan, "apply-sql").irreversible()).isTrue();
      assertThat(plan.summary().warnings())
          .anyMatch(w -> w.contains("restore the database from backup"))
          .anyMatch(w -> w.contains("operator's responsibility"));
      assertThat(f.run(plan, "r-irr")).isInstanceOf(RunOutcome.Succeeded.class);
      Plan rollback = f.ops().planRollback(HotfixFixture.ID, new RollbackOptions(false));
      assertThat(HotfixFixture.ids(rollback)).doesNotContain("run-sql-rollback");
      assertThat(rollback.summary().warnings()).anyMatch(w -> w.contains("irreversible"));
    }
  }

  @Test
  void should_fail_preflight_when_database_unreachable() throws IOException {
    try (HotfixFixture f = withDatabase(tmp)) {
      Path zip = f.build(f.bundleDir("sql", sqlManifest("snapshot", ""), files(true)));
      f.jdbc.connectFailure =
          Optional.of(new JdbcException(JdbcException.Kind.CONNECT_FAILED, "refused"));
      RunOutcome outcome = f.run(f.ops().planApply(zip, SIGNED), "r-db-down");
      assertThat(outcome).isInstanceOf(RunOutcome.PrecheckFailed.class);
      assertThat(((RunOutcome.PrecheckFailed) outcome).stepId()).isEqualTo("preflight");
      assertThat(((RunOutcome.PrecheckFailed) outcome).message()).contains("database: refused");
    }
  }

  private static HotfixFixture withDatabase(Path root) throws IOException {
    return HotfixFixture.create(root, HotfixFixture.databaseYaml(root));
  }
}
