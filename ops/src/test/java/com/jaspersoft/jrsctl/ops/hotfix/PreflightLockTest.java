package com.jaspersoft.jrsctl.ops.hotfix;

import static org.assertj.core.api.Assertions.assertThat;

import com.jaspersoft.jrsctl.core.engine.CheckResult;
import com.jaspersoft.jrsctl.core.engine.Context;
import com.jaspersoft.jrsctl.core.engine.Plan;
import com.jaspersoft.jrsctl.core.platform.ServiceController;
import com.jaspersoft.jrsctl.ops.hotfix.HotfixOperations.ApplyOptions;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

@EnabledOnOs(OS.WINDOWS)
class PreflightLockTest {

  @TempDir Path tmp;

  @Test
  void should_fail_preflight_when_target_locked_and_service_stopped() throws IOException {
    try (HotfixFixture f = HotfixFixture.create(tmp)) {
      Plan plan = f.ops().planApply(f.buildWebInf(), new ApplyOptions(false));
      Context ctx = f.ctx("r-lock");
      assertThat(HotfixFixture.step(plan, "verify-signature").precheck(ctx))
          .isInstanceOf(CheckResult.Pass.class);
      f.fake.platform.serviceState = ServiceController.State.STOPPED;
      try (RandomAccessFile held =
          new RandomAccessFile(f.target(HotfixFixture.FOO).toFile(), "rw")) {
        CheckResult result = HotfixFixture.step(plan, "preflight").precheck(ctx);
        assertThat(result).isInstanceOf(CheckResult.Fail.class);
        assertThat(((CheckResult.Fail) result).message())
            .contains("foo-1.2.3.jar")
            .contains("locked");
        CheckResult swap = HotfixFixture.step(plan, "atomic-swap").precheck(ctx);
        assertThat(swap).isInstanceOf(CheckResult.Fail.class);
        assertThat(((CheckResult.Fail) swap).message()).contains("still locked");
      }
      assertThat(HotfixFixture.step(plan, "preflight").precheck(ctx))
          .isInstanceOf(CheckResult.Pass.class);
    }
  }

  @Test
  void should_report_lock_as_detail_when_service_running_and_restart_required() throws IOException {
    try (HotfixFixture f = HotfixFixture.create(tmp)) {
      Plan plan = f.ops().planApply(f.buildWebInf(), new ApplyOptions(false));
      Context ctx = f.ctx("r-lock2");
      HotfixFixture.step(plan, "verify-signature").precheck(ctx);
      try (RandomAccessFile held =
          new RandomAccessFile(f.target(HotfixFixture.FOO).toFile(), "rw")) {
        CheckResult result = HotfixFixture.step(plan, "preflight").precheck(ctx);
        assertThat(result).isInstanceOf(CheckResult.Warn.class);
        assertThat(((CheckResult.Warn) result).message())
            .contains("locked")
            .contains("service will be stopped");
      }
    }
  }
}
