package com.jaspersoft.jrsctl.ops.upgrade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jaspersoft.jrsctl.core.engine.Plan;
import com.jaspersoft.jrsctl.core.engine.RunOptions;
import com.jaspersoft.jrsctl.core.engine.RunOutcome;
import com.jaspersoft.jrsctl.core.snapshot.Snapshot;
import com.jaspersoft.jrsctl.core.snapshot.SnapshotStore;
import com.jaspersoft.jrsctl.core.state.HotfixInstalled;
import com.jaspersoft.jrsctl.core.state.HotfixState;
import com.jaspersoft.jrsctl.core.state.SnapshotRecord;
import com.jaspersoft.jrsctl.ops.upgrade.UpgradeOperations.RollbackPoint;
import com.jaspersoft.jrsctl.ops.upgrade.UpgradeOperations.UpgradeOptions;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UpgradeRunTest {

  @TempDir Path tmp;

  private static UpgradeOptions newdb(UpgradeFixture f) {
    return UpgradeOptions.newdb(UpgradeFixture.NEW_VERSION, f.packageDir);
  }

  @Test
  void should_back_up_upgrade_and_record_when_vendor_script_succeeds() throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      f.store()
          .recordHotfixInstalled(
              new HotfixInstalled(
                  "JRS-8.2.0-HF-0001",
                  "1",
                  "old fix",
                  "r-old",
                  Optional.empty(),
                  HotfixState.INSTALLED,
                  Instant.EPOCH),
              List.of());
      String oldWebappHash = f.sha(f.webappDir.resolve("scripts").resolve("app.js"));
      Plan plan = f.ops().planUpgrade(newdb(f));

      RunOutcome outcome = f.run(plan, "r-up-1", RunOptions.DEFAULT);

      assertThat(outcome).as(String.join("\n", f.logs())).isInstanceOf(RunOutcome.Succeeded.class);
      SnapshotSet set = SnapshotSet.of(f.fake.home, "r-up-1", f.os);
      assertThat(set.fullExport()).exists();
      assertThat(SnapshotSet.shaFileFor(set.fullExport())).exists();
      assertThat(set.webappArchive()).exists();
      assertThat(set.buildomaticArchive()).exists();
      assertThat(set.manifest()).exists();
      assertThat(f.snapshots().find("r-up-1", "backup-keystore")).isPresent();
      assertThat(f.snapshots().find("r-up-1", "backup-config")).isPresent();
      assertThat(UpgradeFixture.read(f.webappDir.resolve("version.txt"))).isEqualTo("9.0.0");
      assertThat(f.sha(f.webappDir.resolve("scripts").resolve("app.js")))
          .isNotEqualTo(oldWebappHash);
      assertThat(UpgradeFixture.read(f.vendorLog)).contains("upgrade-newdb");
      Path staged = f.packageDir.resolve("buildomatic").resolve("default_master.properties");
      String master = UpgradeFixture.read(staged);
      assertThat(master)
          .contains("dbHost=localhost")
          .contains("appServerType=tomcat")
          .doesNotContain("TopSecret");
      assertThat(master).contains("appServerDir=");
      assertThat(f.store().hotfix("JRS-8.2.0-HF-0001").orElseThrow().state())
          .isEqualTo(HotfixState.SUPERSEDED);
      List<SnapshotRecord> rows = f.store().snapshots("r-up-1");
      assertThat(rows).anyMatch(r -> r.referencedBy().equals(Optional.of("upgrade")));
      assertThat(f.store().auditRows(20)).anyMatch(a -> a.action().equals("upgrade.completed"));
      assertThat(f.fake.platform.controller.events).contains("stop", "start");
      assertThat(f.logs()).anyMatch(m -> m.contains("JRS-8.2.0-HF-0001 -> SUPERSEDED"));
    }
  }

  @Test
  void should_restore_webapp_from_backup_when_smoke_fails_and_rollback_all_requested()
      throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      String oldHash = f.sha(f.webappDir.resolve("scripts").resolve("app.js"));
      f.fake.adapter.schedulerReachable = false;
      Plan plan = f.ops().planUpgrade(newdb(f));

      RunOutcome outcome = f.run(plan, "r-up-2", RunOptions.withRollbackAll());

      assertThat(outcome).as(String.join("\n", f.logs())).isInstanceOf(RunOutcome.RolledBack.class);
      RunOutcome.RolledBack rolled = (RunOutcome.RolledBack) outcome;
      assertThat(rolled.cause()).contains("smoke failed").contains("scheduler");
      assertThat(UpgradeFixture.read(f.webappDir.resolve("version.txt"))).isEqualTo("8.2.0");
      assertThat(f.sha(f.webappDir.resolve("scripts").resolve("app.js"))).isEqualTo(oldHash);
      assertThat(f.webappDir.resolve("WEB-INF").resolve("lib").resolve("jasperserver-9.0.0.jar"))
          .doesNotExist();
      assertThat(f.packageDir.resolve("buildomatic").resolve("default_master.properties"))
          .doesNotExist();
      assertThat(SnapshotSet.of(f.fake.home, "r-up-2", f.os).webappArchive())
          .as("backups kept")
          .exists();
      assertThat(f.logs()).anyMatch(m -> m.contains("webapp restored from"));
    }
  }

  @Test
  void should_only_roll_back_the_verify_phase_when_smoke_fails_without_rollback_all()
      throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      f.fake.adapter.schedulerReachable = false;
      Plan plan = f.ops().planUpgrade(newdb(f));

      RunOutcome outcome = f.run(plan, "r-up-3", RunOptions.DEFAULT);

      assertThat(outcome).isInstanceOf(RunOutcome.RolledBack.class);
      assertThat(((RunOutcome.RolledBack) outcome).rolledBackToPhase()).isEqualTo("verify");
      assertThat(UpgradeFixture.read(f.webappDir.resolve("version.txt"))).isEqualTo("9.0.0");
      assertThat(f.events)
          .anyMatch(e -> e.toString().contains("upgrade rollback r-up-3 --to-point B"));
    }
  }

  @Test
  void should_refuse_to_plan_a_rollback_when_the_config_snapshot_was_pruned() throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      Plan up = f.ops().planUpgrade(newdb(f));
      assertThat(f.run(up, "r-up-pruned", RunOptions.DEFAULT))
          .isInstanceOf(RunOutcome.Succeeded.class);
      Path config = f.fake.home.snapshots().resolve("r-up-pruned").resolve(SnapshotSet.CONFIG_STEP);
      assertThat(config).isDirectory();
      Archives.deleteRecursively(config);

      assertThatThrownBy(() -> f.ops().planRollback("r-up-pruned", RollbackPoint.B))
          .isInstanceOf(UpgradeException.class)
          .hasMessageContaining(SnapshotSet.CONFIG_STEP)
          .hasMessageContaining("point B")
          .asInstanceOf(InstanceOfAssertFactories.type(UpgradeException.class))
          .satisfies(e -> assertThat(e.exitCode()).isEqualTo(UpgradeException.PRECHECK))
          .satisfies(
              e -> assertThat(e.remediation()).contains("old webapp against the new database"));
    }
  }

  @Test
  void should_refuse_to_plan_a_rollback_when_the_webapp_archive_was_corrupted() throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      Plan up = f.ops().planUpgrade(newdb(f));
      assertThat(f.run(up, "r-up-bad", RunOptions.DEFAULT))
          .isInstanceOf(RunOutcome.Succeeded.class);
      SnapshotSet set = SnapshotSet.of(f.fake.home, "r-up-bad", f.fake.platform.os());
      UpgradeFixture.write(set.webappArchive(), "not the archive that was recorded");

      assertThatThrownBy(() -> f.ops().planRollback("r-up-bad", RollbackPoint.B))
          .isInstanceOf(UpgradeException.class)
          .hasMessageContaining("point B")
          .hasMessageContaining("was recorded");
    }
  }

  @Test
  void should_prune_every_snapshot_of_a_run_together_or_none_of_them() throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      Plan up = f.ops().planUpgrade(newdb(f));
      assertThat(f.run(up, "r-up-unit", RunOptions.DEFAULT))
          .isInstanceOf(RunOutcome.Succeeded.class);
      SnapshotStore snapshots = f.snapshots();
      int total = snapshots.list().size();
      assertThat(total).as("the upgrade keeps more than one snapshot").isGreaterThan(1);

      // A cap that would cut the run in half must take the whole run or leave it whole.
      List<Snapshot> candidates = snapshots.pruneCandidates(Duration.ZERO, total - 1, Set.of());

      assertThat(candidates).isNotEmpty();
      assertThat(candidates.stream().map(Snapshot::runId).distinct()).containsExactly("r-up-unit");
      assertThat(candidates).hasSize(total);
    }
  }

  @Test
  void should_restore_archived_webapp_when_rolling_back_a_successful_upgrade() throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      String oldHash = f.sha(f.webappDir.resolve("scripts").resolve("app.js"));
      Plan up = f.ops().planUpgrade(newdb(f));
      assertThat(f.run(up, "r-up-4", RunOptions.DEFAULT)).isInstanceOf(RunOutcome.Succeeded.class);
      assertThat(UpgradeFixture.read(f.webappDir.resolve("version.txt"))).isEqualTo("9.0.0");
      UpgradeFixture.write(
          f.installDir.resolve("buildomatic").resolve("new-file.txt"), "from 9.0.0");

      Plan back = f.ops().planRollback("r-up-4", RollbackPoint.C);
      assertThat(UpgradeFixture.ids(back))
          .containsExactly(
              "stop-service",
              "restore-webapp",
              "restore-buildomatic",
              "restore-config",
              "restore-keystore",
              "start-service",
              "wait-for-server",
              "record-rollback");
      assertThat(back.summary().warnings()).contains(DefaultUpgradeOperations.SAMEDB_WARNING);
      assertThat(back.summary().warnings()).anyMatch(w -> w.contains("point C"));
      RunOutcome outcome = f.run(back, "r-rb-4", RunOptions.DEFAULT);

      assertThat(outcome).as(String.join("\n", f.logs())).isInstanceOf(RunOutcome.Succeeded.class);
      assertThat(UpgradeFixture.read(f.webappDir.resolve("version.txt"))).isEqualTo("8.2.0");
      assertThat(f.sha(f.webappDir.resolve("scripts").resolve("app.js"))).isEqualTo(oldHash);
      assertThat(f.installDir.resolve("buildomatic").resolve("new-file.txt")).doesNotExist();
      assertThat(
              f.fake
                  .home
                  .runDir("r-rb-4")
                  .resolve("aside")
                  .resolve("jasperserver-pro")
                  .resolve("version.txt"))
          .as("replaced tree kept aside")
          .hasContent("9.0.0");
      assertThat(f.store().auditRows(20)).anyMatch(a -> a.action().equals("upgrade.rolled-back"));
    }
  }

  @Test
  void should_refuse_rollback_plan_when_run_is_unknown_or_not_an_upgrade() throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      org.assertj.core.api.Assertions.assertThatThrownBy(
              () -> f.ops().planRollback("r-nope", RollbackPoint.B))
          .isInstanceOf(UpgradeException.class)
          .hasMessageContaining("unknown run");
      f.store().recordRunStart("r-hf", "hotfix.apply", Optional.empty(), Instant.EPOCH);
      org.assertj.core.api.Assertions.assertThatThrownBy(
              () -> f.ops().planRollback("r-hf", RollbackPoint.B))
          .isInstanceOf(UpgradeException.class)
          .hasMessageContaining("not an upgrade");
    }
  }

  @Test
  void should_fail_at_doctor_precheck_and_mutate_nothing_when_layout_is_broken() throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      f.fake.platform.writable = false;
      Plan plan = f.ops().planUpgrade(newdb(f));

      RunOutcome outcome = f.run(plan, "r-up-5", RunOptions.DEFAULT);

      assertThat(outcome).isInstanceOf(RunOutcome.PrecheckFailed.class);
      assertThat(((RunOutcome.PrecheckFailed) outcome).stepId()).isEqualTo("doctor");
      assertThat(((RunOutcome.PrecheckFailed) outcome).message()).contains("permissions");
      assertThat(Files.exists(SnapshotSet.of(f.fake.home, "r-up-5", f.os).dir())).isFalse();
    }
  }
}
