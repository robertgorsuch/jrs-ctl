package com.jaspersoft.jrsctl.ops.retention;

import static org.assertj.core.api.Assertions.assertThat;

import com.jaspersoft.jrsctl.core.snapshot.Snapshot;
import com.jaspersoft.jrsctl.core.snapshot.SnapshotStore;
import com.jaspersoft.jrsctl.core.state.HotfixInstalled;
import com.jaspersoft.jrsctl.core.state.HotfixState;
import com.jaspersoft.jrsctl.core.state.RunLock;
import com.jaspersoft.jrsctl.core.state.SnapshotRecord;
import com.jaspersoft.jrsctl.core.state.StateStore;
import com.jaspersoft.jrsctl.ops.FakeServices;
import com.jaspersoft.jrsctl.ops.Services;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RetentionPrunerTest {

  @TempDir Path tmp;

  private FakeServices fake;
  private Services services;
  private Instant now;

  @AfterEach
  void tearDown() {
    if (fake != null) {
      fake.close();
    }
  }

  private void services(int retentionDays, int maxSnapshots) throws IOException {
    fake = FakeServices.in(tmp.resolve("home"));
    fake.platform.realFiles = true;
    fake.yaml(
        """
        backups:
          retentionDays: %d
          maxSnapshots: %d
        """
            .formatted(retentionDays, maxSnapshots));
    services = fake.build();
    now = fake.clock.instant();
  }

  private RetentionPruner pruner() {
    return new RetentionPruner(
        services, new SnapshotStore(fake.home, fake.platform.files(), fake.clock), "tester");
  }

  /** A real snapshot of one small file, created {@code age} before now, with its state row. */
  private Snapshot snapshot(
      String runId, String stepId, Duration age, Optional<String> referencedBy) throws IOException {
    Path src = Files.createDirectories(tmp.resolve("src"));
    Path file = src.resolve(runId + "-" + stepId + ".txt");
    Files.writeString(file, "content of " + runId, StandardCharsets.UTF_8);
    SnapshotStore at =
        new SnapshotStore(
            fake.home, fake.platform.files(), Clock.fixed(now.minus(age), ZoneOffset.UTC));
    Snapshot s = at.create(runId, stepId, List.of(file), src);
    fake.stateStore()
        .recordSnapshot(
            new SnapshotRecord(
                runId + "/" + stepId,
                runId,
                stepId,
                s.dir(),
                fake.platform.files().sha256(s.manifestFile()),
                referencedBy));
    return s;
  }

  private static List<String> ids(RetentionPruner.Result result) {
    return result.removed().stream().map(RetentionPruner.Removed::id).toList();
  }

  @Test
  void should_remove_snapshots_older_than_retention_when_count_is_under_cap() throws Exception {
    services(30, 20);
    Snapshot old = snapshot("r-old", "snapshot", Duration.ofDays(40), Optional.empty());
    Snapshot fresh = snapshot("r-new", "snapshot", Duration.ofDays(1), Optional.empty());

    RetentionPruner.Result result = pruner().prune(false);

    assertThat(result.dryRun()).isFalse();
    assertThat(ids(result)).containsExactly("r-old/snapshot");
    assertThat(result.removed().get(0).path()).isEqualTo(old.dir());
    assertThat(result.kept()).isEqualTo(1);
    assertThat(result.protectedCount()).isZero();
    assertThat(old.dir()).doesNotExist();
    assertThat(fresh.manifestFile()).exists();
    StateStore store = fake.stateStore();
    assertThat(store.snapshot("r-old/snapshot")).isEmpty();
    assertThat(store.snapshot("r-new/snapshot")).isPresent();
    assertThat(store.auditRows(5))
        .anyMatch(
            a ->
                a.action().equals(RetentionPruner.AUDIT_ACTION)
                    && a.actor().equals("tester")
                    && a.detail().orElse("").contains("removed 1 snapshot(s)"));
  }

  @Test
  void should_remove_oldest_unprotected_when_count_exceeds_max_snapshots() throws Exception {
    services(0, 1);
    snapshot("r-a", "snapshot", Duration.ofDays(3), Optional.empty());
    snapshot("r-b", "snapshot", Duration.ofDays(2), Optional.empty());
    Snapshot c = snapshot("r-c", "snapshot", Duration.ofDays(1), Optional.empty());

    RetentionPruner.Result result = pruner().prune(false);

    assertThat(ids(result)).containsExactly("r-a/snapshot", "r-b/snapshot");
    assertThat(result.kept()).isEqualTo(1);
    assertThat(c.manifestFile()).exists();
    assertThat(fake.home.snapshots().resolve("r-a")).doesNotExist();
    assertThat(fake.stateStore().snapshots())
        .extracting(SnapshotRecord::id)
        .containsExactly("r-c/snapshot");
  }

  @Test
  void should_keep_referenced_snapshots_when_pruning_by_count() throws Exception {
    services(0, 1);
    Snapshot hotfixed = snapshot("r-hf", "snapshot", Duration.ofDays(5), Optional.of("HF-1"));
    fake.stateStore()
        .recordHotfixInstalled(
            new HotfixInstalled(
                "HF-1", "1", "t", "r-hf", Optional.of("r-hf/snapshot"), HotfixState.INSTALLED, now),
            List.of());
    snapshot("r-b", "snapshot", Duration.ofDays(2), Optional.empty());
    snapshot("r-c", "snapshot", Duration.ofDays(1), Optional.empty());

    RetentionPruner.Result result = pruner().prune(false);

    assertThat(ids(result)).containsExactly("r-b/snapshot", "r-c/snapshot");
    assertThat(result.kept()).isEqualTo(1);
    assertThat(result.protectedCount()).isEqualTo(1);
    assertThat(hotfixed.manifestFile()).exists();
    assertThat(fake.stateStore().snapshot("r-hf/snapshot")).isPresent();
  }

  @Test
  void should_change_nothing_when_dry_run() throws Exception {
    services(0, 1);
    Snapshot a = snapshot("r-a", "snapshot", Duration.ofDays(2), Optional.empty());
    Snapshot b = snapshot("r-b", "snapshot", Duration.ofDays(1), Optional.empty());

    RetentionPruner.Result result = pruner().prune(true);

    assertThat(result.dryRun()).isTrue();
    assertThat(ids(result)).containsExactly("r-a/snapshot");
    assertThat(result.kept()).isEqualTo(1);
    assertThat(a.manifestFile()).exists();
    assertThat(b.manifestFile()).exists();
    assertThat(fake.stateStore().snapshots()).hasSize(2);
    assertThat(fake.stateStore().auditRows(5))
        .noneMatch(row -> row.action().equals(RetentionPruner.AUDIT_ACTION));
  }

  @Test
  void should_keep_the_finished_runs_snapshots_when_pruning_after_success() throws Exception {
    services(0, 1);
    Snapshot own = snapshot("r-done", "snapshot", Duration.ofDays(3), Optional.empty());
    snapshot("r-b", "snapshot", Duration.ofDays(2), Optional.empty());
    snapshot("r-c", "snapshot", Duration.ofDays(1), Optional.empty());

    Optional<RetentionPruner.Result> result = pruner().afterSuccessfulRun("r-done");

    assertThat(result).isPresent();
    assertThat(ids(result.get())).containsExactly("r-b/snapshot", "r-c/snapshot");
    assertThat(result.get().protectedCount()).isEqualTo(1);
    assertThat(own.manifestFile()).exists();
    assertThat(fake.stateStore().snapshot("r-done/snapshot")).isPresent();
    assertThat(fake.stateStore().auditRows(5))
        .anyMatch(row -> row.action().equals(RetentionPruner.AUDIT_ACTION));
  }

  @Test
  void should_skip_without_failing_when_run_lock_is_held_during_automatic_prune() throws Exception {
    services(0, 1);
    Snapshot a = snapshot("r-a", "snapshot", Duration.ofDays(2), Optional.empty());
    snapshot("r-b", "snapshot", Duration.ofDays(1), Optional.empty());

    Optional<RetentionPruner.Result> result;
    try (RunLock held = new RunLock(fake.home, "r-other", now)) {
      result = pruner().afterSuccessfulRun("r-b");
    }

    assertThat(result).isEmpty();
    assertThat(a.manifestFile()).exists();
    assertThat(fake.stateStore().snapshots()).hasSize(2);
  }

  @Test
  void should_not_audit_when_automatic_prune_removes_nothing() throws Exception {
    services(30, 20);
    snapshot("r-a", "snapshot", Duration.ofDays(1), Optional.empty());

    Optional<RetentionPruner.Result> result = pruner().afterSuccessfulRun("r-a");

    assertThat(result).isPresent();
    assertThat(result.get().removed()).isEmpty();
    assertThat(fake.stateStore().auditRows(5))
        .noneMatch(row -> row.action().equals(RetentionPruner.AUDIT_ACTION));
  }

  @Test
  void should_drop_stale_rows_when_their_directory_is_gone() throws Exception {
    services(30, 20);
    snapshot("r-a", "snapshot", Duration.ofDays(1), Optional.empty());
    fake.stateStore()
        .recordSnapshot(
            new SnapshotRecord(
                "r-ghost/snapshot",
                "r-ghost",
                "snapshot",
                fake.home.snapshots().resolve("r-ghost").resolve("snapshot"),
                "0000",
                Optional.empty()));

    RetentionPruner.Result result = pruner().prune(false);

    assertThat(result.removed()).isEmpty();
    assertThat(fake.stateStore().snapshot("r-ghost/snapshot")).isEmpty();
    assertThat(fake.stateStore().snapshot("r-a/snapshot")).isPresent();
  }
}
