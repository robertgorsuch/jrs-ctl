package com.jaspersoft.jrsctl.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Issue #71: running jrsctl without arguments on a terminal offers a menu that asks only for what
 * each job needs, shows the equivalent command line and runs it through the normal CLI.
 */
class GuidedModeTest {

  @TempDir Path tmp;

  private final StringWriter text = new StringWriter();
  private final List<List<String>> ran = new ArrayList<>();

  @AfterEach
  void restore() {
    Prompter.reset();
  }

  private int guided(List<String> base, List<String> pending, String... answers) {
    Prompter.override(new StringReader(String.join("\n", answers) + "\n"));
    GuidedMode mode =
        new GuidedMode(
            new PrintWriter(text, true),
            base,
            args -> {
              ran.add(List.of(args));
              return 0;
            },
            () -> pending);
    return mode.run();
  }

  @Test
  void should_run_a_whole_repository_export_with_the_file_the_operator_names() {
    int code = guided(List.of(), List.of(), "3", "1", "/backups/repo.zip", "q");

    assertThat(code).isZero();
    assertThat(ran).containsExactly(List.of("export", "--out", "/backups/repo.zip"));
    assertThat(text.toString())
        .contains("Back up content")
        .contains("jrsctl export --out /backups/repo.zip");
  }

  @Test
  void should_pass_the_global_options_the_menu_was_started_with() {
    guided(List.of("--home", "/srv/jrsctl"), List.of(), "3", "2", "/public", "/b/public.zip", "q");

    assertThat(ran)
        .containsExactly(
            List.of(
                "export", "--uri", "/public", "--out", "/b/public.zip", "--home", "/srv/jrsctl"));
  }

  @Test
  void should_verify_a_hotfix_before_applying_it() throws Exception {
    Path bundle = Files.writeString(tmp.resolve("HF-1.zip"), "zip");

    guided(List.of(), List.of(), "5", "1", bundle.toString(), "q");

    assertThat(ran)
        .containsExactly(
            List.of("hotfix", "verify", bundle.toString()),
            List.of("hotfix", "apply", bundle.toString()));
  }

  @Test
  void should_ask_again_for_a_file_that_does_not_exist() throws Exception {
    Path archive = Files.writeString(tmp.resolve("in.zip"), "zip");

    guided(
        List.of(),
        List.of(),
        "4",
        tmp.resolve("missing.zip").toString(),
        archive.toString(),
        "n",
        "q");

    assertThat(text.toString()).contains("no such file");
    assertThat(ran).containsExactly(List.of("import", archive.toString()));
  }

  @Test
  void should_not_start_an_upgrade_until_the_database_backup_is_confirmed() throws Exception {
    Path pkg = Files.createDirectories(tmp.resolve("pkg"));

    guided(List.of(), List.of(), "6", "10.0.0", pkg.toString(), "n", "q");

    assertThat(ran).isEmpty();
    assertThat(text.toString()).contains("Back up the repository database first");
  }

  @Test
  void should_show_runs_that_need_recovery_before_the_menu() {
    guided(List.of(), List.of("r-20260916-1"), "q");

    String out = text.toString();
    assertThat(out).contains("r-20260916-1").contains("jrsctl runs recover");
    assertThat(out.indexOf("r-20260916-1")).isLessThan(out.indexOf("Back up content"));
  }

  @Test
  void should_quit_cleanly_when_input_ends() {
    int code = guided(List.of(), List.of());

    assertThat(code).isZero();
    assertThat(ran).isEmpty();
  }

  @Test
  void should_keep_usage_and_exit_1_without_a_terminal_when_no_command_is_given() {
    StringWriter err = new StringWriter();
    picocli.CommandLine cmd = Main.commandLine();
    cmd.setErr(new PrintWriter(err));

    int code = cmd.execute();

    assertThat(code).isEqualTo(ExitCodes.USAGE);
    assertThat(err.toString()).contains("Usage");
  }
}
