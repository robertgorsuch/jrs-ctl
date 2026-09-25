package com.jaspersoft.jrsctl.ops.hotfix;

import static org.assertj.core.api.Assertions.assertThat;

import com.jaspersoft.jrsctl.core.engine.CheckResult;
import com.jaspersoft.jrsctl.core.engine.Context;
import com.jaspersoft.jrsctl.core.engine.Plan;
import com.jaspersoft.jrsctl.core.engine.RunOutcome;
import com.jaspersoft.jrsctl.core.platform.ServiceController;
import com.jaspersoft.jrsctl.ops.hotfix.HotfixOperations.ApplyOptions;
import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Issue #157: a swap run by an account that may not give the replaced files back to their owner
 * left them owned by that account, and the run still succeeded. Preflight (apply) and the restore
 * precheck (rollback) now refuse before anything changes, naming the owner and what to run as.
 */
class OwnerRestoreTest {

  @TempDir Path tmp;

  @Test
  void should_refuse_preflight_when_the_replaced_files_owner_cannot_be_restored()
      throws IOException {
    try (HotfixFixture f = HotfixFixture.create(tmp)) {
      Plan plan = f.ops().planApply(f.buildWebInf(), new ApplyOptions(false));
      Context ctx = f.ctx("r-owner");
      HotfixFixture.step(plan, "verify-signature").precheck(ctx);
      f.fake.platform.serviceState = ServiceController.State.STOPPED;
      f.fake.platform.ownerRestorable = false;

      CheckResult preflight = HotfixFixture.step(plan, "preflight").precheck(ctx);

      assertThat(preflight).isInstanceOf(CheckResult.Fail.class);
      assertThat(((CheckResult.Fail) preflight).message())
          .contains("cannot give")
          .contains("back to their owner")
          .contains("elevated");
    }
  }

  @Test
  void should_pass_preflight_when_the_owner_can_be_restored() throws IOException {
    try (HotfixFixture f = HotfixFixture.create(tmp)) {
      Plan plan = f.ops().planApply(f.buildWebInf(), new ApplyOptions(false));
      Context ctx = f.ctx("r-owner-ok");
      HotfixFixture.step(plan, "verify-signature").precheck(ctx);
      f.fake.platform.serviceState = ServiceController.State.STOPPED;

      CheckResult preflight = HotfixFixture.step(plan, "preflight").precheck(ctx);

      assertThat(preflight).isNotInstanceOf(CheckResult.Fail.class);
    }
  }

  @Test
  void should_refuse_a_rollback_whose_files_owner_cannot_be_restored() throws IOException {
    try (HotfixFixture f = HotfixFixture.create(tmp)) {
      RunOutcome applied =
          f.run(f.ops().planApply(f.buildWebInf(), new ApplyOptions(false)), "r-owner-apply");
      assertThat(applied).isInstanceOf(RunOutcome.Succeeded.class);
      String id = f.store().hotfixes().get(0).id();
      Plan rollback = f.ops().planRollback(id, new HotfixOperations.RollbackOptions(false));
      f.fake.platform.ownerRestorable = false;

      CheckResult restore =
          rollback.steps().stream()
              .filter(s -> s.id().startsWith(RollbackSteps.RESTORE_SNAPSHOT))
              .findFirst()
              .orElseThrow()
              .precheck(f.ctx("r-owner-rollback"));

      assertThat(restore).isInstanceOf(CheckResult.Fail.class);
      assertThat(((CheckResult.Fail) restore).message()).contains("back to their owner");
    }
  }
}
