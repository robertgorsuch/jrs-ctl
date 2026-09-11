package com.jaspersoft.jrsctl.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MappingIterator;
import com.jaspersoft.jrsctl.core.JrsctlHome;
import com.jaspersoft.jrsctl.core.json.Json;
import com.jaspersoft.jrsctl.core.state.HotfixFile;
import com.jaspersoft.jrsctl.core.state.HotfixInstalled;
import com.jaspersoft.jrsctl.core.state.HotfixState;
import com.jaspersoft.jrsctl.core.state.RunLock;
import com.jaspersoft.jrsctl.core.state.StateStore;
import com.jaspersoft.jrsctl.core.state.TerminalState;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HotfixCommandTest {

  @TempDir Path tmp;

  private FakeHotfixOperations fake;
  private Path home;
  private Path bundle;

  @BeforeEach
  void setUp() throws Exception {
    fake = new FakeHotfixOperations();
    HotfixOps.factory = services -> fake;
    home = Files.createDirectories(tmp.resolve("home"));
    bundle = tmp.resolve("hf.zip");
    Files.writeString(bundle, "zip");
  }

  @AfterEach
  void tearDown() {
    HotfixOps.factory = HotfixOps.DEFAULT_FACTORY;
  }

  private InitCommandTest.Run apply(String... extra) {
    List<String> args = new ArrayList<>(List.of("hotfix", "apply", bundle.toString()));
    args.addAll(List.of(extra));
    args.addAll(List.of("--home", home.toString(), "--no-color"));
    return InitCommandTest.run(args.toArray(String[]::new));
  }

  static List<JsonNode> documents(String text) throws Exception {
    List<JsonNode> docs = new ArrayList<>();
    try (MappingIterator<JsonNode> it = Json.mapper().readerFor(JsonNode.class).readValues(text)) {
      while (it.hasNext()) {
        docs.add(it.next());
      }
    }
    return docs;
  }

  @Test
  void should_print_phases_and_steps_and_exit_0_without_running_when_plan_flag_given()
      throws Exception {
    InitCommandTest.Run run = apply("--plan");

    assertThat(run.code()).as(run.out() + run.err()).isZero();
    assertThat(run.out())
        .contains("Plan  hotfix.apply  " + FakeHotfixOperations.ID)
        .contains("  verify")
        .contains("  backup")
        .contains("  apply")
        .contains("  record")
        .contains("01  Verify signature")
        .contains("06  Record installed")
        .contains("! no service restart needed")
        .contains("Fingerprint  sha256:")
        .contains("nothing has changed");
    assertThat(fake.executed).isEmpty();
    try (StateStore store = StateStore.open(new JrsctlHome(home), Clock.systemUTC())) {
      assertThat(store.loadPlan(fake.lastPlanId)).isPresent();
      assertThat(store.loadPlan(fake.lastPlanId).get().operation()).isEqualTo("hotfix.apply");
      assertThat(store.runs(10)).isEmpty();
    }
  }

  @Test
  void should_run_every_step_and_print_ok_lines_when_yes_given() throws Exception {
    InitCommandTest.Run run = apply("--yes");

    assertThat(run.code()).as(run.out() + run.err()).isZero();
    assertThat(fake.executed).containsExactlyElementsOf(FakeHotfixOperations.APPLY_STEPS);
    assertThat(run.out())
        .contains("OK    01  Verify signature")
        .contains("OK    06  Record installed")
        .contains("succeeded");
    try (StateStore store = StateStore.open(new JrsctlHome(home), Clock.systemUTC())) {
      assertThat(store.runs(10)).hasSize(1);
      assertThat(store.runs(10).get(0).terminalState()).contains(TerminalState.SUCCEEDED);
      assertThat(store.loadPlan(fake.lastPlanId).get().consumedByRunId())
          .contains(store.runs(10).get(0).runId());
    }
  }

  @Test
  void should_print_outcome_block_and_exit_3_when_a_step_fails() {
    fake.failStep = Optional.of("atomic-swap");

    InitCommandTest.Run run = apply("--yes");

    assertThat(run.code()).as(run.out() + run.err()).isEqualTo(ExitCodes.FAILED_ROLLED_BACK);
    assertThat(run.out())
        .contains("FAIL  05  Atomic swap")
        .contains("UNDO  05  Atomic swap")
        .contains("UNDO  04  Stage files")
        .contains("rolled back to phase apply")
        .contains("step")
        .contains("cause")
        .contains("simulated failure of atomic-swap")
        .contains("affected")
        .contains("backup")
        .contains("snap-1")
        .contains("next")
        .contains("inspect the fake and retry");
    assertThat(fake.compensated).containsExactly("atomic-swap", "stage-files");
  }

  @Test
  void should_emit_parseable_event_lines_and_final_outcome_when_json_given() throws Exception {
    InitCommandTest.Run run = apply("--yes", "--json");

    assertThat(run.code()).as(run.out() + run.err()).isZero();
    List<JsonNode> docs = documents(run.out());
    assertThat(docs.get(0).get("planId").asText()).isEqualTo(fake.lastPlanId);
    assertThat(docs.get(0).get("steps")).hasSize(6);
    List<String> types = new ArrayList<>();
    for (JsonNode d : docs.subList(1, docs.size() - 1)) {
      types.add(d.get("type").asText());
    }
    assertThat(types).contains("PlanCreated", "StepRunning", "StepSucceeded", "RunSucceeded");
    JsonNode last = docs.get(docs.size() - 1);
    assertThat(last.get("outcome").get("type").asText()).isEqualTo("Succeeded");
    assertThat(last.get("outcome").get("exitCode").asInt()).isZero();
  }

  @Test
  void should_exit_8_and_name_recover_command_when_a_run_is_pending() {
    try (StateStore store = StateStore.open(new JrsctlHome(home), Clock.systemUTC())) {
      store.recordRunStart("r-pending", "hotfix.apply", Optional.empty(), Instant.now());
    }

    InitCommandTest.Run run = apply("--yes");

    assertThat(run.code()).isEqualTo(ExitCodes.RECOVERY_REQUIRED);
    assertThat(run.err())
        .contains("r-pending")
        .contains("jrsctl runs recover r-pending --resume")
        .contains("jrsctl runs recover r-pending --rollback");
    assertThat(fake.executed).isEmpty();
  }

  @Test
  void should_exit_9_and_name_holder_when_run_lock_is_held() {
    try (RunLock held = new RunLock(new JrsctlHome(home), "r-other", Instant.now())) {
      InitCommandTest.Run run = apply("--yes");

      assertThat(run.code()).as(run.out() + run.err()).isEqualTo(ExitCodes.LOCK_HELD);
      assertThat(run.err()).contains("r-other").contains("pid");
      assertThat(fake.executed).isEmpty();
    }
  }

  /** Review finding 4.4: {@code --non-interactive} never confirms; only {@code --yes} does. */
  @Test
  void should_exit_2_without_running_when_non_interactive_and_yes_not_given() {
    InitCommandTest.Run run = apply("--non-interactive");

    assertThat(run.code()).isEqualTo(ExitCodes.PRECHECK_FAILED);
    assertThat(run.err()).contains("--yes");
    assertThat(fake.executed).isEmpty();
  }

  /**
   * Review finding 4.4: with stdout piped there is no console, but the operator is still there; the
   * question goes to stdout and the answer comes from stdin instead of a refusal with exit 2.
   */
  @Test
  void should_ask_on_stdin_and_not_run_when_the_answer_is_no() {
    InitCommandTest.Run run = withStdin("n\n", () -> apply());

    assertThat(run.code()).isEqualTo(ExitCodes.SUCCESS);
    assertThat(run.out()).contains("Run this plan?").contains("not run; nothing has changed");
    assertThat(fake.executed).isEmpty();
  }

  @Test
  void should_ask_on_stdin_and_run_when_the_answer_is_yes() {
    InitCommandTest.Run run = withStdin("y\n", () -> apply());

    assertThat(run.code()).isEqualTo(ExitCodes.SUCCESS);
    assertThat(fake.executed).isNotEmpty();
  }

  @Test
  void should_treat_end_of_stdin_as_no_and_not_run() {
    InitCommandTest.Run run = withStdin("", () -> apply());

    assertThat(run.code()).isEqualTo(ExitCodes.SUCCESS);
    assertThat(fake.executed).isEmpty();
  }

  private static InitCommandTest.Run withStdin(
      String text, java.util.function.Supplier<InitCommandTest.Run> body) {
    java.io.InputStream saved = System.in;
    System.setIn(
        new java.io.ByteArrayInputStream(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    try {
      return body.get();
    } finally {
      System.setIn(saved);
    }
  }

  @Test
  void should_exit_7_when_verify_report_is_not_ok() throws Exception {
    fake.hashesValid = false;
    InitCommandTest.Run bad =
        InitCommandTest.run(
            "hotfix", "verify", bundle.toString(), "--home", home.toString(), "--no-color");
    fake.hashesValid = true;
    InitCommandTest.Run good =
        InitCommandTest.run(
            "hotfix", "verify", bundle.toString(), "--home", home.toString(), "--json");

    assertThat(bad.code()).isEqualTo(ExitCodes.SIGNATURE_FAILED);
    assertThat(bad.out()).contains("x FAIL").contains("hashes").contains("rejected");
    assertThat(good.code()).isZero();
    JsonNode report = Json.mapper().readTree(good.out());
    assertThat(report.get("signatureValid").asBoolean()).isTrue();
    assertThat(report.get("manifestId").asText()).isEqualTo(FakeHotfixOperations.ID);
  }

  @Test
  void should_refuse_unsigned_bundle_unless_allow_unsigned_given() {
    fake.signatureValid = false;

    InitCommandTest.Run refused = apply("--yes");
    InitCommandTest.Run allowed = apply("--plan", "--allow-unsigned");

    assertThat(refused.code()).isEqualTo(ExitCodes.SIGNATURE_FAILED);
    assertThat(refused.err()).contains("--allow-unsigned");
    assertThat(allowed.code()).as(allowed.err()).isZero();
    assertThat(fake.lastAllowUnsigned).isTrue();
    try (StateStore store = StateStore.open(new JrsctlHome(home), Clock.systemUTC())) {
      assertThat(store.auditRows(10))
          .anyMatch(a -> a.action().equals("hotfix.apply.allow-unsigned"));
    }
  }

  @Test
  void should_exit_2_when_planning_fails() {
    fake.planFailure = Optional.of(new IllegalStateException("manifest.json is missing"));

    InitCommandTest.Run run = apply("--yes");

    assertThat(run.code()).isEqualTo(ExitCodes.PRECHECK_FAILED);
    assertThat(run.err()).contains("manifest.json is missing");
  }

  @Test
  void should_list_installed_hotfixes_with_file_counts_when_json_given() throws Exception {
    HotfixInstalled installed =
        new HotfixInstalled(
            FakeHotfixOperations.ID,
            "1",
            FakeHotfixOperations.TITLE,
            "r-1",
            Optional.of("snap-1"),
            HotfixState.INSTALLED,
            Instant.parse("2026-09-01T10:00:00Z"));
    try (StateStore store = StateStore.open(new JrsctlHome(home), Clock.systemUTC())) {
      store.recordHotfixInstalled(
          installed,
          List.of(
              new HotfixFile(
                  installed.id(), Path.of("a.js"), "replace", Optional.of("x"), Optional.of("y")),
              new HotfixFile(
                  installed.id(), Path.of("b.txt"), "add", Optional.empty(), Optional.of("z"))));
    }
    fake.installed = List.of(installed);

    InitCommandTest.Run json =
        InitCommandTest.run("hotfix", "list", "--json", "--home", home.toString());
    InitCommandTest.Run text =
        InitCommandTest.run("hotfix", "list", "--home", home.toString(), "--no-color");

    assertThat(json.code()).isZero();
    JsonNode rows = Json.mapper().readTree(json.out());
    assertThat(rows.get(0).get("id").asText()).isEqualTo(FakeHotfixOperations.ID);
    assertThat(rows.get(0).get("state").asText()).isEqualTo("INSTALLED");
    assertThat(rows.get(0).get("files").asInt()).isEqualTo(2);
    assertThat(text.out()).contains(FakeHotfixOperations.ID).contains("INSTALLED").contains("2");
  }

  @Test
  void should_show_rollback_plan_and_exit_0_when_plan_flag_given() {
    InitCommandTest.Run run =
        InitCommandTest.run(
            "hotfix",
            "rollback",
            FakeHotfixOperations.ID,
            "--cascade",
            "--plan",
            "--home",
            home.toString(),
            "--no-color");

    assertThat(run.code()).as(run.out() + run.err()).isZero();
    assertThat(run.out()).contains("Plan  hotfix.rollback").contains("01  Restore snapshot");
    assertThat(fake.lastRollbackId).contains(FakeHotfixOperations.ID);
    assertThat(fake.lastCascade).isTrue();
    assertThat(fake.executed).isEmpty();
  }

  @Test
  void should_write_bundle_and_exit_0_when_build_given_valid_key_reference() {
    Path out = tmp.resolve("built.zip");
    InitCommandTest.Run run =
        InitCommandTest.run(
            "hotfix",
            "build",
            tmp.toString(),
            "--key",
            "file:" + tmp.resolve("k.key"),
            "--out",
            out.toString(),
            "--home",
            home.toString());
    InitCommandTest.Run badRef =
        InitCommandTest.run(
            "hotfix",
            "build",
            tmp.toString(),
            "--key",
            "nope",
            "--out",
            out.toString(),
            "--home",
            home.toString());

    assertThat(run.code()).as(run.err()).isZero();
    assertThat(run.out()).contains("wrote ");
    assertThat(out).exists();
    assertThat(badRef.code()).isEqualTo(ExitCodes.USAGE);
  }
}
