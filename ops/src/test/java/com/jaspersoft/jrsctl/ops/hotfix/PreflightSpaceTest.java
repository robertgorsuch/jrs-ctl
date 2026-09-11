package com.jaspersoft.jrsctl.ops.hotfix;

import static org.assertj.core.api.Assertions.assertThat;

import com.jaspersoft.jrsctl.core.engine.CheckResult;
import com.jaspersoft.jrsctl.core.engine.Context;
import com.jaspersoft.jrsctl.core.engine.Plan;
import com.jaspersoft.jrsctl.core.engine.Step;
import com.jaspersoft.jrsctl.core.platform.DiskSpace;
import com.jaspersoft.jrsctl.ops.hotfix.HotfixOperations.ApplyOptions;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Review finding 1.16: the preflight measured one volume, the wrong size. */
class PreflightSpaceTest {

  @TempDir Path tmp;

  /** Only the install base was checked; snapshots and staging may sit on another volume. */
  @Test
  void should_fail_preflight_when_the_snapshot_volume_is_short_of_space() throws IOException {
    try (HotfixFixture f = HotfixFixture.create(tmp)) {
      Plan plan = f.ops().planApply(f.buildWebInf(), new ApplyOptions(false));
      Context ctx = f.ctx("r-space");
      assertThat(HotfixFixture.step(plan, "verify-signature").precheck(ctx))
          .isInstanceOf(CheckResult.Pass.class);
      f.fake.platform.freeSpaceUnder.put(f.fake.home.snapshots(), 10L);

      CheckResult result = HotfixFixture.step(plan, "preflight").precheck(ctx);

      assertThat(result).isInstanceOf(CheckResult.Fail.class);
      assertThat(((CheckResult.Fail) result).message()).contains("snapshot").contains("free");
    }
  }

  /** The snapshot volume must hold the files being replaced or deleted plus the margin. */
  @Test
  void should_count_the_files_being_snapshotted_when_checking_the_snapshot_volume()
      throws IOException {
    try (HotfixFixture f = HotfixFixture.create(tmp)) {
      Plan plan = f.ops().planApply(f.buildWebInf(), new ApplyOptions(false));
      Context ctx = f.ctx("r-space");
      assertThat(HotfixFixture.step(plan, "verify-signature").precheck(ctx))
          .isInstanceOf(CheckResult.Pass.class);
      Step preflight = HotfixFixture.step(plan, "preflight");
      long existing = 0;
      for (String p : List.of(HotfixFixture.FOO, HotfixFixture.FOO_OLDER, HotfixFixture.BAR)) {
        existing += Files.size(f.target(p));
      }

      f.fake.platform.freeSpaceUnder.put(
          f.fake.home.snapshots(), DiskSpace.MARGIN_BYTES + existing - 1);
      assertThat(preflight.precheck(ctx)).isInstanceOf(CheckResult.Fail.class);

      f.fake.platform.freeSpaceUnder.put(
          f.fake.home.snapshots(), DiskSpace.MARGIN_BYTES + existing);
      assertThat(preflight.precheck(ctx)).isInstanceOf(CheckResult.Pass.class);
    }
  }
}
