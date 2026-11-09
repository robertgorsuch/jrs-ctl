package com.jaspersoft.jrsctl.ops.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jaspersoft.jrsctl.core.engine.Plan;
import com.jaspersoft.jrsctl.core.engine.Step;
import com.jaspersoft.jrsctl.ops.FakeLayout;
import com.jaspersoft.jrsctl.ops.FakeServices;
import com.jaspersoft.jrsctl.ops.Idempotency;
import com.jaspersoft.jrsctl.ops.repository.RepositoryOperations.LoadOptions;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** ADR-0052, spec §10.5: what {@code repository load} plans and what it refuses to plan. */
class RepositoryLoadPlanTest {

  @TempDir Path tmp;

  @Test
  void should_plan_five_phases_in_order_with_the_schema_drop_as_the_rollback() throws Exception {
    try (LoadFixture f = LoadFixture.create(tmp)) {
      Plan plan = f.ops().planLoad(f.options());

      assertThat(Idempotency.ids(plan))
          .containsExactly(
              LoadSteps.DOCTOR,
              LoadSteps.VERIFY_BUILDOMATIC,
              LoadSteps.VERIFY_TARGET_SCHEMA,
              LoadSteps.ADOPT_EXPORT,
              LoadSteps.CREATE_JS_DB,
              "init-js-db-pro",
              "import-minimal-pro",
              LoadSteps.IMPORT_EXPORT,
              LoadSteps.RECORD_LOAD);
      assertThat(plan.steps().stream().map(Step::phase).distinct())
          .containsExactly(
              LoadPhases.PREFLIGHT,
              LoadPhases.CREATE,
              LoadPhases.INITIALISE,
              LoadPhases.LOAD,
              LoadPhases.VERIFY);
      assertThat(plan.summary().operation()).isEqualTo(RepositoryOperations.LOAD_OPERATION);
      assertThat(plan.summary().target()).contains(LoadFixture.SCHEMA);
      assertThat(plan.summary().serviceRestart()).isFalse();
      assertThat(plan.summary().resourcesTouched())
          .containsExactly("database " + LoadFixture.SCHEMA);
      assertThat(plan.summary().rollbackPointsByPhase().get(LoadPhases.PREFLIGHT))
          .contains("nothing mutated");
      for (String phase : List.of(LoadPhases.CREATE, LoadPhases.INITIALISE, LoadPhases.LOAD)) {
        assertThat(plan.summary().rollbackPointsByPhase().get(phase))
            .contains("drop")
            .contains(LoadFixture.SCHEMA);
      }
      assertThat(plan.summary().strategy()).contains("js-ant").contains("js-import");
      assertThat(plan.summary().warnings())
          .contains(DefaultRepositoryOperations.OUTSIDE_THE_PLAN_WARNING)
          .contains(DefaultRepositoryOperations.EVENTS_LEFT_BEHIND_WARNING);
      assertThat(plan.fingerprint().inputs())
          .containsEntry("schema", LoadFixture.SCHEMA)
          .containsEntry("exportSha256", f.services.platform().files().sha256(f.export))
          .containsKey("buildomatic")
          .containsKey("masterProperties");
      assertThat(plan.fingerprint().inputs().get("masterProperties")).doesNotContain("TopSecret");
    }
  }

  @Test
  void should_name_the_events_in_the_summary_when_include_events_is_asked() throws Exception {
    try (LoadFixture f = LoadFixture.create(tmp)) {
      Plan plan =
          f.ops().planLoad(new LoadOptions(LoadFixture.SCHEMA, f.export, true, Optional.empty()));

      assertThat(plan.summary().warnings())
          .doesNotContain(DefaultRepositoryOperations.EVENTS_LEFT_BEHIND_WARNING);
      assertThat(Idempotency.step(plan, LoadSteps.IMPORT_EXPORT).detail())
          .contains("--include-access-events")
          .contains("--include-server-settings");
    }
  }

  /** The command is for a buildomatic beside no installation; on a server host, upgrade is. */
  @Test
  void should_refuse_a_configuration_that_names_an_installation() throws Exception {
    Path install = FakeLayout.linux(tmp.resolve("jrs"));
    try (FakeServices fake = FakeServices.in(tmp.resolve("home"))) {
      fake.yaml(
          """
          server:
            baseUrl: http://localhost:8081/jasperserver-pro
            installDir: %s
            auth:
              username: superuser
              passwordRef: env:JRS_PASSWORD
          """
              .formatted(install.toString().replace('\\', '/')));
      DefaultRepositoryOperations ops = new DefaultRepositoryOperations(fake.build());
      Path export = tmp.resolve("x.zip");
      Files.writeString(export, "zip");

      assertThatThrownBy(
              () ->
                  ops.planLoad(
                      new LoadOptions(LoadFixture.SCHEMA, export, false, Optional.empty())))
          .isInstanceOf(RepositoryException.class)
          .hasMessageContaining("tools-only")
          .extracting(e -> ((RepositoryException) e).exitCode())
          .isEqualTo(RepositoryException.PRECHECK);
    }
  }

  @Test
  void should_refuse_a_configuration_without_a_buildomatic_or_an_export() throws Exception {
    try (FakeServices fake = FakeServices.in(tmp.resolve("home"))) {
      fake.yaml(
          """
          server:
            baseUrl: http://localhost:8081/jasperserver-pro
            auth:
              username: superuser
              passwordRef: env:JRS_PASSWORD
          """);
      DefaultRepositoryOperations ops = new DefaultRepositoryOperations(fake.build());

      assertThatThrownBy(
              () ->
                  ops.planLoad(
                      new LoadOptions(
                          LoadFixture.SCHEMA, tmp.resolve("none.zip"), false, Optional.empty())))
          .isInstanceOf(RepositoryException.class)
          .hasMessageContaining("tools-only");
    }
    try (LoadFixture f = LoadFixture.create(tmp.resolve("ok"))) {
      assertThatThrownBy(
              () ->
                  f.ops()
                      .planLoad(
                          new LoadOptions(
                              LoadFixture.SCHEMA,
                              tmp.resolve("none.zip"),
                              false,
                              Optional.empty())))
          .isInstanceOf(RepositoryException.class)
          .hasMessageContaining("none.zip");
    }
  }

  @Test
  void should_refuse_a_schema_name_that_is_not_a_plain_identifier() throws Exception {
    try (LoadFixture f = LoadFixture.create(tmp)) {
      assertThatThrownBy(
              () -> f.ops().planLoad(new LoadOptions("jrs-101", f.export, false, Optional.empty())))
          .isInstanceOf(RepositoryException.class)
          .hasMessageContaining("schema name")
          .extracting(e -> ((RepositoryException) e).exitCode())
          .isEqualTo(RepositoryException.USAGE);
    }
  }

  /** The one mistake the command exists to make impossible, refused with exit 6 (ADR-0052). */
  @Test
  void should_refuse_with_exit_6_when_the_configured_database_url_names_the_schema()
      throws Exception {
    try (LoadFixture f = LoadFixture.createWithConfiguredUrl(tmp, LoadFixture.SCHEMA)) {
      f.master(LoadFixture.MASTER.replace("js.dbName=jrs_101", "js.dbName=jasperserver"));

      assertThatThrownBy(() -> f.ops().planLoad(f.options()))
          .isInstanceOf(RepositoryException.class)
          .hasMessageContaining("database.url")
          .hasMessageContaining(LoadFixture.SCHEMA)
          .extracting(e -> ((RepositoryException) e).exitCode())
          .isEqualTo(RepositoryException.UNSUPPORTED);
    }
  }

  @Test
  void should_refuse_with_exit_6_a_database_type_without_a_catalog_rule() throws Exception {
    try (LoadFixture f = LoadFixture.create(tmp)) {
      f.fake.yaml(
          Files.readString(f.home().configFile())
              .replace("type: postgresql", "type: oracle")
              .replace(
                  "url: jdbc:postgresql://db.example.internal:5432/jrs_101",
                  "url: jdbc:oracle:thin:@db.example.internal:1521:jrs_101"));
      f.services = f.fake.build();

      assertThatThrownBy(() -> f.ops().planLoad(f.options()))
          .isInstanceOf(RepositoryException.class)
          .hasMessageContaining("oracle")
          .extracting(e -> ((RepositoryException) e).exitCode())
          .isEqualTo(RepositoryException.UNSUPPORTED);
    }
  }

  @Test
  void should_refuse_when_the_database_password_reference_is_not_configured() throws Exception {
    try (LoadFixture f = LoadFixture.create(tmp)) {
      f.fake.yaml(
          Files.readString(f.home().configFile())
              .replace("  passwordRef: env:JRS_DB_PASSWORD\n", ""));
      f.services = f.fake.build();

      assertThatThrownBy(() -> f.ops().planLoad(f.options()))
          .isInstanceOf(RepositoryException.class)
          .hasMessageContaining("database.passwordRef")
          .extracting(e -> ((RepositoryException) e).exitCode())
          .isEqualTo(RepositoryException.PRECHECK);
    }
  }

  /** ADR-0052: the key alias comes from the flag, else from the sidecar, else nothing. */
  @Test
  void should_take_the_key_alias_from_the_sidecar_when_none_is_given() throws Exception {
    try (LoadFixture f = LoadFixture.create(tmp)) {
      com.jaspersoft.jrsctl.jrs.strategy.Sidecar.write(
          com.jaspersoft.jrsctl.jrs.strategy.Sidecar.pathFor(f.export),
          new com.jaspersoft.jrsctl.jrs.strategy.Sidecar(
              java.time.Instant.EPOCH,
              "http://old:8080/jasperserver-pro|8.2.0|PRO|MULTI|b1",
              "8.2.0",
              Optional.empty(),
              new com.jaspersoft.jrsctl.jrs.strategy.Sidecar.Flags(
                  com.jaspersoft.jrsctl.jrs.api.ExportRequest.Scope.EVERYTHING,
                  List.of(),
                  true,
                  false,
                  false,
                  false,
                  true,
                  true,
                  Optional.of("deprecatedImportExportEncSecret"),
                  Optional.empty()),
              f.services.platform().files().sha256(f.export),
              com.jaspersoft.jrsctl.jrs.api.ExportImportStrategy.Kind.VENDOR_CLI));

      Plan plan = f.ops().planLoad(f.options());

      assertThat(Idempotency.step(plan, LoadSteps.IMPORT_EXPORT).detail())
          .contains("--keyalias deprecatedImportExportEncSecret");
      assertThat(plan.fingerprint().inputs())
          .containsEntry("keyAlias", "deprecatedImportExportEncSecret");
      assertThat(plan.summary().warnings()).anyMatch(w -> w.contains("8.2.0"));
    }
  }
}
