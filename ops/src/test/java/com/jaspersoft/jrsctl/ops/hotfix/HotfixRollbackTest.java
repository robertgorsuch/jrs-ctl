package com.jaspersoft.jrsctl.ops.hotfix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jaspersoft.jrsctl.core.engine.Plan;
import com.jaspersoft.jrsctl.core.engine.RunOutcome;
import com.jaspersoft.jrsctl.core.state.HotfixState;
import com.jaspersoft.jrsctl.ops.hotfix.HotfixOperations.ApplyOptions;
import com.jaspersoft.jrsctl.ops.hotfix.HotfixOperations.RollbackOptions;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HotfixRollbackTest {

  private static final ApplyOptions SIGNED = new ApplyOptions(false);
  private static final RollbackOptions PLAIN = new RollbackOptions(false);
  private static final RollbackOptions CASCADE = new RollbackOptions(true);

  @TempDir Path tmp;

  @Test
  void should_restore_original_hashes_and_delete_added_file_when_rolled_back() throws IOException {
    try (HotfixFixture f = HotfixFixture.create(tmp)) {
      String oldFoo = f.sha(HotfixFixture.FOO);
      String olderFoo = f.sha(HotfixFixture.FOO_OLDER);
      String bar = f.sha(HotfixFixture.BAR);
      assertThat(f.run(f.ops().planApply(f.buildWebInf(), SIGNED), "r-apply"))
          .isInstanceOf(RunOutcome.Succeeded.class);
      f.fake.platform.controller.events.clear();

      Plan plan = f.ops().planRollback(HotfixFixture.ID, PLAIN);
      assertThat(HotfixFixture.ids(plan))
          .containsExactly(
              "stop-service",
              "restore-snapshot",
              "start-service",
              "wait-for-server",
              "record-rolled-back");
      assertThat(plan.byPhase().keySet()).containsExactly("rollback");
      assertThat(plan.summary().operation()).isEqualTo("hotfix.rollback");
      assertThat(plan.summary().backupLocations())
          .containsExactly(f.fake.home.snapshots().resolve("r-apply").resolve("snapshot"));

      assertThat(f.run(plan, "r-rollback")).isInstanceOf(RunOutcome.Succeeded.class);
      assertThat(f.sha(HotfixFixture.FOO)).isEqualTo(oldFoo);
      assertThat(f.sha(HotfixFixture.FOO_OLDER)).isEqualTo(olderFoo);
      assertThat(f.sha(HotfixFixture.BAR)).isEqualTo(bar);
      assertThat(f.target(HotfixFixture.FIX)).doesNotExist();
      assertThat(f.fake.platform.controller.events).containsExactly("stop", "start");
      assertThat(f.ops().list())
          .singleElement()
          .extracting(h -> h.state())
          .isEqualTo(HotfixState.ROLLED_BACK);
      assertThat(f.store().installedHotfixes()).isEmpty();
    }
  }

  @Test
  void should_allow_reapply_after_rollback() throws IOException {
    try (HotfixFixture f = HotfixFixture.create(tmp)) {
      assertThat(f.run(f.ops().planApply(f.buildWebInf(), SIGNED), "r-apply1"))
          .isInstanceOf(RunOutcome.Succeeded.class);
      assertThat(f.run(f.ops().planRollback(HotfixFixture.ID, PLAIN), "r-rollback1"))
          .isInstanceOf(RunOutcome.Succeeded.class);
      assertThat(f.run(f.ops().planApply(f.buildWebInf(), SIGNED), "r-apply2"))
          .isInstanceOf(RunOutcome.Succeeded.class);
      assertThat(f.ops().list())
          .singleElement()
          .extracting(h -> h.installedRunId())
          .isEqualTo("r-apply2");
      assertThat(Files.readString(f.target(HotfixFixture.FOO))).isEqualTo(HotfixFixture.NEW_FOO);
    }
  }

  @Test
  void should_refuse_rollback_when_id_unknown_or_not_installed() throws IOException {
    try (HotfixFixture f = HotfixFixture.create(tmp)) {
      assertThatThrownBy(() -> f.ops().planRollback("JRS-1.0.0-HF-9999", PLAIN))
          .isInstanceOf(HotfixException.class)
          .hasMessageContaining("unknown hotfix");
      assertThat(f.run(f.ops().planApply(f.buildWebInf(), SIGNED), "r-apply"))
          .isInstanceOf(RunOutcome.Succeeded.class);
      assertThat(f.run(f.ops().planRollback(HotfixFixture.ID, PLAIN), "r-rollback"))
          .isInstanceOf(RunOutcome.Succeeded.class);
      assertThatThrownBy(() -> f.ops().planRollback(HotfixFixture.ID, PLAIN))
          .isInstanceOf(HotfixException.class)
          .hasMessageContaining("not installed");
    }
  }

  @Test
  void should_refuse_rollback_when_later_hotfix_owns_same_file() throws IOException {
    try (HotfixFixture f = HotfixFixture.create(tmp)) {
      installBoth(f);
      assertThatThrownBy(() -> f.ops().planRollback(HotfixFixture.ID, PLAIN))
          .isInstanceOf(HotfixException.class)
          .hasMessageContaining("blocked by later hotfixes")
          .hasMessageContaining(HotfixFixture.ID2);
      assertThat(HotfixFixture.ids(f.ops().planRollback(HotfixFixture.ID2, PLAIN)))
          .contains("restore-snapshot");
    }
  }

  @Test
  void should_roll_back_newest_first_and_restore_originals_when_cascade() throws IOException {
    try (HotfixFixture f = HotfixFixture.create(tmp)) {
      String oldFoo = f.sha(HotfixFixture.FOO);
      installBoth(f);
      assertThat(Files.readString(f.target(HotfixFixture.FOO))).isEqualTo(HotfixFixture.NEWER_FOO);

      Plan plan = f.ops().planRollback(HotfixFixture.ID, CASCADE);
      assertThat(plan.byPhase().keySet())
          .containsExactly("rollback:" + HotfixFixture.ID2, "rollback:" + HotfixFixture.ID);
      assertThat(HotfixFixture.ids(plan))
          .containsExactly(
              "stop-service:" + HotfixFixture.ID2,
              "restore-snapshot:" + HotfixFixture.ID2,
              "start-service:" + HotfixFixture.ID2,
              "wait-for-server:" + HotfixFixture.ID2,
              "record-rolled-back:" + HotfixFixture.ID2,
              "stop-service:" + HotfixFixture.ID,
              "restore-snapshot:" + HotfixFixture.ID,
              "start-service:" + HotfixFixture.ID,
              "wait-for-server:" + HotfixFixture.ID,
              "record-rolled-back:" + HotfixFixture.ID);
      assertThat(f.run(plan, "r-cascade")).isInstanceOf(RunOutcome.Succeeded.class);
      assertThat(f.sha(HotfixFixture.FOO)).isEqualTo(oldFoo);
      assertThat(f.target(HotfixFixture.FOO_OLDER)).exists();
      assertThat(f.target(HotfixFixture.BAR)).exists();
      assertThat(f.target(HotfixFixture.FIX)).doesNotExist();
      assertThat(f.store().installedHotfixes()).isEmpty();
    }
  }

  @Test
  void should_re_apply_hotfix_when_rollback_fails_after_restore() throws IOException {
    try (HotfixFixture f = HotfixFixture.create(tmp)) {
      assertThat(f.run(f.ops().planApply(f.buildWebInf(), SIGNED), "r-apply"))
          .isInstanceOf(RunOutcome.Succeeded.class);
      Plan plan = f.ops().planRollback(HotfixFixture.ID, PLAIN);
      f.fake.platform.controller.events.clear();
      f.fake.unreachable = true;
      RunOutcome outcome = f.run(plan, "r-rollback");
      assertThat(outcome).isInstanceOf(RunOutcome.RolledBack.class);
      assertThat(((RunOutcome.RolledBack) outcome).cause()).contains("did not answer");
      assertThat(f.fake.platform.controller.events)
          .containsExactly("stop", "start", "stop", "start");
      assertThat(Files.readString(f.target(HotfixFixture.FOO))).isEqualTo(HotfixFixture.NEW_FOO);
      assertThat(f.target(HotfixFixture.FIX)).exists();
      assertThat(f.target(HotfixFixture.BAR)).doesNotExist();
      assertThat(f.target(HotfixFixture.FOO_OLDER)).doesNotExist();
      assertThat(f.store().installedHotfixes()).hasSize(1);
    }
  }

  private static void installBoth(HotfixFixture f) throws IOException {
    assertThat(f.run(f.ops().planApply(f.buildWebInf(), SIGNED), "r-first"))
        .isInstanceOf(RunOutcome.Succeeded.class);
    assertThat(
            f.run(
                f.ops().planApply(f.buildSecond("[\"" + HotfixFixture.ID + "\"]"), SIGNED),
                "r-second"))
        .isInstanceOf(RunOutcome.Succeeded.class);
    assertThat(f.store().installedHotfixes()).hasSize(2);
  }
}
