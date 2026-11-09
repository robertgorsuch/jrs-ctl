package com.jaspersoft.jrsctl.ops.passwords;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jaspersoft.jrsctl.core.engine.Plan;
import com.jaspersoft.jrsctl.core.engine.Step;
import com.jaspersoft.jrsctl.ops.FakeServices;
import com.jaspersoft.jrsctl.ops.Idempotency;
import com.jaspersoft.jrsctl.ops.passwords.PasswordOperations.MigrateOptions;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** ADR-0053, spec §10.6: what {@code passwords migrate} plans and what it refuses to plan. */
class PasswordsPlanTest {

  @TempDir Path tmp;

  @Test
  void should_plan_preflight_migrate_and_verify_with_the_vendor_targets_in_order()
      throws Exception {
    try (PasswordFixture f = PasswordFixture.create(tmp)) {
      Plan plan = f.ops().planMigrate(f.options());

      assertThat(Idempotency.ids(plan))
          .containsExactly(
              PasswordSteps.DOCTOR,
              PasswordSteps.VERIFY_TOOLS,
              PasswordSteps.CONFIRM_DB_BACKUP,
              PasswordSteps.DRY_RUN,
              PasswordSteps.MIGRATE,
              PasswordSteps.DETECT);
      assertThat(plan.steps().stream().map(Step::phase).distinct())
          .containsExactly(PasswordPhases.PREFLIGHT, PasswordPhases.MIGRATE, PasswordPhases.VERIFY);
      assertThat(Idempotency.step(plan, PasswordSteps.MIGRATE).irreversible()).isTrue();
      assertThat(plan.summary().operation()).isEqualTo(PasswordOperations.MIGRATE_OPERATION);
      assertThat(plan.summary().target()).contains("10.1.0");
      assertThat(plan.summary().serviceRestart()).isFalse();
      assertThat(plan.summary().warnings())
          .contains(DefaultPasswordOperations.IRREVERSIBLE_WARNING)
          .contains(DefaultPasswordOperations.SKIPPED_USERS_WARNING)
          .noneMatch(w -> w.contains("password.strategy="));
      assertThat(plan.summary().rollbackPointsByPhase().get(PasswordPhases.MIGRATE))
          .contains("irreversible");
      assertThat(plan.fingerprint().inputs())
          .containsEntry("serverVersion", "10.1.0")
          .containsEntry("cleanup", "false")
          .containsKey("storageConfigSha256");
    }
  }

  @Test
  void should_add_the_cleanup_phase_when_asked() throws Exception {
    try (PasswordFixture f = PasswordFixture.create(tmp)) {
      Plan plan = f.ops().planMigrate(new MigrateOptions(true, true));

      assertThat(Idempotency.ids(plan)).endsWith(PasswordSteps.DETECT, PasswordSteps.CLEANUP);
      assertThat(Idempotency.step(plan, PasswordSteps.CLEANUP).phase())
          .isEqualTo(PasswordPhases.CLEANUP);
      assertThat(Idempotency.step(plan, PasswordSteps.CLEANUP).irreversible()).isTrue();
    }
  }

  /** The utility reads its settings from the deployed webapp, so no webapp means no migration. */
  @Test
  void should_refuse_a_configuration_without_the_installation_on_this_machine() throws Exception {
    try (FakeServices fake = FakeServices.in(tmp.resolve("home"))) {
      fake.yaml(
          """
          server:
            baseUrl: http://localhost:8081/jasperserver-pro
            auth:
              username: superuser
              passwordRef: env:JRS_PASSWORD
          """);
      DefaultPasswordOperations ops = new DefaultPasswordOperations(fake.build());

      assertThatThrownBy(() -> ops.planMigrate(new MigrateOptions(true, false)))
          .isInstanceOf(PasswordException.class)
          .hasMessageContaining("deployed webapp")
          .extracting(e -> ((PasswordException) e).exitCode())
          .isEqualTo(PasswordException.PRECHECK);
      assertThatThrownBy(ops::status)
          .isInstanceOf(PasswordException.class)
          .hasMessageContaining("names no installation");
    }
  }

  @Test
  void should_refuse_a_tools_only_configuration() throws Exception {
    Path buildomatic = Files.createDirectories(tmp.resolve("buildomatic"));
    try (FakeServices fake = FakeServices.in(tmp.resolve("home"))) {
      fake.yaml(
          """
          server:
            baseUrl: http://localhost:8081/jasperserver-pro
            buildomaticDir: %s
            auth:
              username: superuser
              passwordRef: env:JRS_PASSWORD
          """
              .formatted(buildomatic.toString().replace('\\', '/')));
      DefaultPasswordOperations ops = new DefaultPasswordOperations(fake.build());

      assertThatThrownBy(() -> ops.planMigrate(new MigrateOptions(true, false)))
          .isInstanceOf(PasswordException.class)
          .hasMessageContaining("tools-only");
    }
  }

  @Test
  void should_refuse_with_exit_6_a_server_older_than_10_1() throws Exception {
    try (PasswordFixture f = PasswordFixture.createWithVersion(tmp, "9.0.0")) {
      assertThatThrownBy(() -> f.ops().planMigrate(f.options()))
          .isInstanceOf(PasswordException.class)
          .hasMessageContaining("9.0.0")
          .hasMessageContaining("10.1")
          .extracting(e -> ((PasswordException) e).exitCode())
          .isEqualTo(PasswordException.UNSUPPORTED);
    }
  }

  @Test
  void should_warn_when_the_webapp_is_still_configured_for_legacy_storage() throws Exception {
    try (PasswordFixture f = PasswordFixture.createWithStrategy(tmp, "legacy")) {
      Plan plan = f.ops().planMigrate(f.options());

      assertThat(plan.summary().warnings()).anyMatch(w -> w.contains("password.strategy=legacy"));
    }
  }
}
