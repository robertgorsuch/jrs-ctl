package com.jaspersoft.jrsctl.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.jaspersoft.jrsctl.core.JrsctlHome;
import com.jaspersoft.jrsctl.core.engine.RunLock;
import com.jaspersoft.jrsctl.core.engine.TerminalState;
import com.jaspersoft.jrsctl.core.json.Json;
import com.jaspersoft.jrsctl.core.platform.FileOps;
import com.jaspersoft.jrsctl.core.platform.Platforms;
import com.jaspersoft.jrsctl.core.snapshot.Snapshot;
import com.jaspersoft.jrsctl.core.snapshot.SnapshotStore;
import com.jaspersoft.jrsctl.core.state.SnapshotRecord;
import com.jaspersoft.jrsctl.core.state.StateStore;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RunsPruneCommandTest {

  @TempDir Path tmp;

  private final FileOps files = Platforms.detect().files();
  private FakeHotfixOperations fake;
  private Path home;
  private JrsctlHome jrsctlHome;
  private Path bundle;

  @BeforeEach
  void setUp() throws Exception {
    fake = new FakeHotfixOperations();
    HotfixOps.factory = services -> fake;
    home = Files.createDirectories(tmp.resolve("home"));
    jrsctlHome = new JrsctlHome(home);
    bundle = tmp.resolve("hf.zip");
    Files.writeString(bundle, "zip");
  }

  @AfterEach
  void tearDown() {
    HotfixOps.factory = HotfixOps.DEFAULT_FACTORY;
  }

  private StateStore open() {
    return StateStore.open(jrsctlHome, Clock.systemUTC());
  }

  /** A real snapshot of one file created {@code age} ago, with its {@code snapshots} row. */
  private Snapshot seed(String runId, Duration age) throws Exception {
    Path src = Files.createDirectories(tmp.resolve("src"));
    Path file = src.resolve(runId + ".txt");
    Files.writeString(file, "content " + runId, StandardCharsets.UTF_8);
    Clock then = Clock.fixed(Instant.now().minus(age), ZoneOffset.UTC);
    Snapshot s =
        new SnapshotStore(jrsctlHome, files, then).create(runId, "snapshot", List.of(file), src);
    try (StateStore store = open()) {
      store.recordSnapshot(
          new SnapshotRecord(
              runId + "/snapshot",
              runId,
              "snapshot",
              s.dir(),
              files.sha256(s.manifestFile()),
              Optional.empty()));
    }
    return s;
  }

  private InitCommandTest.Run prune(String... extra) {
    List<String> args = new ArrayList<>(List.of("runs", "prune"));
    args.addAll(List.of(extra));
    args.addAll(List.of("--home", home.toString(), "--no-color", "--ascii"));
    return InitCommandTest.run(args.toArray(String[]::new));
  }

  private static List<String> fieldNames(JsonNode node) {
    List<String> names = new ArrayList<>();
    node.fieldNames().forEachRemaining(names::add);
    return names;
  }

  @Test
  void should_remove_oldest_snapshot_and_its_row_when_cap_is_one_and_json_requested()
      throws Exception {
    Snapshot old = seed("r-old", Duration.ofDays(2));
    Snapshot fresh = seed("r-new", Duration.ofDays(1));

    InitCommandTest.Run run = prune("--json", "--set", "backups.maxSnapshots=1");

    assertThat(run.code()).as(run.out() + run.err()).isZero();
    JsonNode root = Json.mapper().readTree(run.out());
    assertThat(fieldNames(root)).containsExactly("dryRun", "removed", "kept", "protected");
    assertThat(root.get("dryRun").asBoolean()).isFalse();
    assertThat(root.get("removed")).hasSize(1);
    JsonNode removed = root.get("removed").get(0);
    assertThat(fieldNames(removed)).containsExactly("id", "runId", "stepId", "path");
    assertThat(removed.get("id").asText()).isEqualTo("r-old/snapshot");
    assertThat(removed.get("runId").asText()).isEqualTo("r-old");
    assertThat(removed.get("stepId").asText()).isEqualTo("snapshot");
    assertThat(removed.get("path").asText()).isEqualTo(old.dir().toString());
    assertThat(root.get("kept").asInt()).isEqualTo(1);
    assertThat(root.get("protected").asInt()).isZero();
    assertThat(old.dir()).doesNotExist();
    assertThat(fresh.manifestFile()).exists();
    try (StateStore store = open()) {
      assertThat(store.snapshot("r-old/snapshot")).isEmpty();
      assertThat(store.snapshot("r-new/snapshot")).isPresent();
      assertThat(store.auditRows(5)).anyMatch(a -> a.action().equals("runs.prune"));
      assertThat(store.runs(10)).as("pruning is not a journaled run").isEmpty();
    }
  }

  @Test
  void should_list_candidates_and_change_nothing_when_dry_run() throws Exception {
    Snapshot old = seed("r-old", Duration.ofDays(2));
    seed("r-new", Duration.ofDays(1));

    InitCommandTest.Run run = prune("--dry-run", "--json", "--set", "backups.maxSnapshots=1");

    assertThat(run.code()).as(run.out() + run.err()).isZero();
    JsonNode root = Json.mapper().readTree(run.out());
    assertThat(root.get("dryRun").asBoolean()).isTrue();
    assertThat(root.get("removed")).hasSize(1);
    assertThat(root.get("removed").get(0).get("runId").asText()).isEqualTo("r-old");
    assertThat(root.get("kept").asInt()).isEqualTo(1);
    assertThat(old.manifestFile()).exists();
    try (StateStore store = open()) {
      assertThat(store.snapshots()).hasSize(2);
      assertThat(store.auditRows(5)).noneMatch(a -> a.action().equals("runs.prune"));
    }
  }

  @Test
  void should_print_table_and_summary_when_text_output() throws Exception {
    Snapshot old = seed("r-old", Duration.ofDays(2));
    seed("r-new", Duration.ofDays(1));

    InitCommandTest.Run dry = prune("--dry-run", "--set", "backups.maxSnapshots=1");
    InitCommandTest.Run real = prune("--set", "backups.maxSnapshots=1");
    InitCommandTest.Run again = prune("--set", "backups.maxSnapshots=1");

    assertThat(dry.code()).isZero();
    assertThat(dry.out())
        .contains("SNAPSHOT")
        .contains("r-old/snapshot")
        .contains(old.dir().toString())
        .contains("would remove 1 snapshot(s); keeping 1 (0 protected)")
        .contains("nothing has changed");
    assertThat(real.code()).isZero();
    assertThat(real.out()).contains("removed 1 snapshot(s); kept 1 (0 protected)");
    assertThat(again.code()).isZero();
    assertThat(again.out()).contains("nothing to prune; kept 1 (0 protected)");
  }

  @Test
  void should_exit_9_when_run_lock_is_held() throws Exception {
    seed("r-old", Duration.ofDays(2));
    seed("r-new", Duration.ofDays(1));

    InitCommandTest.Run run;
    try (RunLock held = new RunLock(jrsctlHome, "r-holder", Instant.now())) {
      run = prune("--set", "backups.maxSnapshots=1");
    }

    assertThat(run.code()).isEqualTo(ExitCodes.LOCK_HELD);
    assertThat(run.err()).contains("r-holder").contains("pid");
    try (StateStore store = open()) {
      assertThat(store.snapshots()).hasSize(2);
    }
  }

  @Test
  void should_prune_automatically_when_a_mutating_run_succeeds() throws Exception {
    Snapshot old = seed("r-old", Duration.ofDays(2));
    Snapshot fresh = seed("r-new", Duration.ofDays(1));

    InitCommandTest.Run run =
        InitCommandTest.run(
            "hotfix",
            "apply",
            bundle.toString(),
            "--yes",
            "--set",
            "backups.maxSnapshots=1",
            "--home",
            home.toString(),
            "--no-color",
            "--ascii");

    assertThat(run.code()).as(run.out() + run.err()).isZero();
    assertThat(old.dir()).doesNotExist();
    assertThat(fresh.manifestFile()).exists();
    try (StateStore store = open()) {
      assertThat(store.runs(10).get(0).terminalState()).contains(TerminalState.SUCCEEDED);
      assertThat(store.snapshot("r-old/snapshot")).isEmpty();
      assertThat(store.auditRows(10)).anyMatch(a -> a.action().equals("runs.prune"));
    }
  }

  @Test
  void should_not_prune_when_the_run_fails() throws Exception {
    fake.failStep = Optional.of("atomic-swap");
    Snapshot old = seed("r-old", Duration.ofDays(2));
    seed("r-new", Duration.ofDays(1));

    InitCommandTest.Run run =
        InitCommandTest.run(
            "hotfix",
            "apply",
            bundle.toString(),
            "--yes",
            "--set",
            "backups.maxSnapshots=1",
            "--home",
            home.toString(),
            "--no-color",
            "--ascii");

    assertThat(run.code()).isEqualTo(ExitCodes.FAILED_ROLLED_BACK);
    assertThat(old.manifestFile()).exists();
    try (StateStore store = open()) {
      assertThat(store.snapshots()).hasSize(2);
      assertThat(store.auditRows(10)).noneMatch(a -> a.action().equals("runs.prune"));
    }
  }
}
