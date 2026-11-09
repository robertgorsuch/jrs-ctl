package com.jaspersoft.jrsctl.ops.passwords;

import static org.assertj.core.api.Assertions.assertThat;

import com.jaspersoft.jrsctl.core.engine.Context;
import com.jaspersoft.jrsctl.core.engine.Plan;
import com.jaspersoft.jrsctl.core.engine.Step;
import com.jaspersoft.jrsctl.ops.Idempotency;
import com.jaspersoft.jrsctl.ops.passwords.PasswordOperations.MigrateOptions;
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
 * Phase 8 idempotency of every {@code passwords migrate} step (spec §6.1, §10.6): a read-only step
 * executed twice runs nothing twice that matters and leaves everything as it was; an irreversible
 * step executed again after a complete execution runs its vendor target once; compensations are
 * no-ops.
 */
class PasswordStepIdempotencyTest {

  @TempDir Path tmp;

  private static Map<String, String> state(PasswordFixture f) throws IOException {
    Map<String, String> m = new TreeMap<>();
    m.putAll(Idempotency.tree("install", f.installDir));
    m.putAll(Idempotency.tree("runs", f.home().runs()));
    m.put("migrated", Boolean.toString(f.migrated()));
    m.put("cleaned", Boolean.toString(f.cleaned()));
    return m;
  }

  private static Context start(PasswordFixture f, Plan plan, String runId) {
    f.store().recordRunStart(runId, plan.summary().operation(), Optional.empty(), Instant.EPOCH);
    return f.ctx(runId);
  }

  /** How many vendor-log lines are exactly {@code command}, whatever the line ending. */
  private static int count(String text, String command) {
    return (int) text.lines().map(String::strip).filter(command::equals).count();
  }

  @Test
  void should_not_mutate_when_read_only_password_steps_execute_twice() throws Exception {
    try (PasswordFixture f = PasswordFixture.create(tmp)) {
      Plan plan = f.ops().planMigrate(new MigrateOptions(true, true));
      Context ctx = start(f, plan, "r-ro");
      List<String> readOnly =
          List.of(
              PasswordSteps.DOCTOR,
              PasswordSteps.VERIFY_TOOLS,
              PasswordSteps.CONFIRM_DB_BACKUP,
              PasswordSteps.DRY_RUN,
              PasswordSteps.DETECT);
      List<String> seen = new ArrayList<>();
      for (Step step : plan.steps()) {
        Idempotency.executeOk(step, ctx, f.events::add);
        if (readOnly.contains(step.id())) {
          assertThat(step.mutating()).as(step.id()).isFalse();
          Map<String, String> before = state(f);
          Idempotency.executeOk(step, ctx, f.events::add);
          Idempotency.compensateOk(step, ctx, f.events::add);
          assertThat(state(f)).as(step.id()).isEqualTo(before);
          seen.add(step.id());
        }
      }
      assertThat(seen).containsExactlyElementsOf(readOnly);
    }
  }

  @Test
  void should_migrate_once_when_migrate_executes_twice() throws Exception {
    try (PasswordFixture f = PasswordFixture.create(tmp)) {
      Plan plan = f.ops().planMigrate(f.options());
      Context ctx = start(f, plan, "r-m");
      Idempotency.runUpTo(plan, ctx, PasswordSteps.MIGRATE, f.events::add);
      Map<String, String> once = state(f);
      Step migrate = Idempotency.step(plan, PasswordSteps.MIGRATE);

      Idempotency.executeOk(migrate, ctx, f.events::add);
      Idempotency.compensateOk(migrate, ctx, f.events::add);
      Idempotency.compensateOk(migrate, ctx, f.events::add);

      assertThat(state(f)).isEqualTo(once);
      assertThat(count(f.vendorLogText(), "js-ant migrate-passwords")).isEqualTo(1);
      assertThat(f.migrated()).isTrue();
    }
  }

  @Test
  void should_clean_once_when_cleanup_executes_twice() throws Exception {
    try (PasswordFixture f = PasswordFixture.create(tmp)) {
      Plan plan = f.ops().planMigrate(new MigrateOptions(true, true));
      Context ctx = start(f, plan, "r-c");
      Idempotency.runAll(plan, ctx, f.events::add);
      Map<String, String> once = state(f);
      Step cleanup = Idempotency.step(plan, PasswordSteps.CLEANUP);

      Idempotency.executeOk(cleanup, ctx, f.events::add);
      Idempotency.compensateOk(cleanup, ctx, f.events::add);

      assertThat(state(f)).isEqualTo(once);
      assertThat(count(f.vendorLogText(), "js-ant cleanup-password-migration")).isEqualTo(1);
    }
  }
}
