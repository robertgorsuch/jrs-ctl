package com.jaspersoft.jrsctl.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Issue #71 and spec §17.1: running jrsctl without arguments on a terminal offers a menu that asks
 * only for what each job needs, shows the equivalent command line and runs it through the normal
 * CLI; every option the vendor's documented paths need is reachable from it (field test 2, I4).
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
            () -> pending,
            () -> Optional.of(tmp.resolve("home").resolve("snapshots")));
    return mode.run();
  }

  private static int count(String haystack, String needle) {
    int n = 0;
    for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + 1)) {
      n++;
    }
    return n;
  }

  // ---- the menu itself --------------------------------------------------------------------------

  @Test
  void should_offer_documentation_and_name_the_other_entry_points() {
    guided(List.of(), List.of(), "q");

    assertThat(text.toString())
        .contains("8) Read the documentation")
        .contains("jrsctl --help lists")
        .contains("every command")
        .contains("jrsctl console opens the web console")
        .contains("--json makes any command");
  }

  @Test
  void should_print_the_embedded_documents_and_open_the_chosen_one() {
    guided(List.of(), List.of(), "8", "1", "q");

    assertThat(text.toString()).contains("(operator-guide)").contains("(recovery-runbook)");
    assertThat(ran).containsExactly(List.of("docs", "operator-guide"));
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

  // ---- settings ---------------------------------------------------------------------------------

  @Test
  void should_change_settings_until_enter_without_reprinting_the_table() {
    guided(
        List.of(),
        List.of(),
        "1",
        "2",
        "server.baseUrl",
        "http://x",
        "vendor.javaHome",
        "/jdk",
        "",
        "q");

    assertThat(ran)
        .containsExactly(
            List.of("config", "keys"),
            List.of("config", "set", "server.baseUrl", "http://x"),
            List.of("config", "set", "vendor.javaHome", "/jdk"));
    assertThat(count(text.toString(), "Change one with:")).isEqualTo(1);
  }

  @Test
  void should_reask_an_unknown_setting_instead_of_sending_it() {
    guided(List.of(), List.of(), "1", "2", "nope.key", "server.baseUrl", "http://x", "", "q");

    assertThat(text.toString()).contains("unknown setting nope.key");
    assertThat(ran)
        .containsExactly(
            List.of("config", "keys"), List.of("config", "set", "server.baseUrl", "http://x"));
  }

  @Test
  void should_reask_when_the_installation_directory_does_not_exist() {
    guided(List.of(), List.of(), "1", "1", "/zugzug/whatever", "", "q");

    assertThat(text.toString()).contains("no such directory: /zugzug/whatever");
    assertThat(ran).isEmpty();
  }

  @Test
  void should_search_for_the_installation_when_enter_is_the_first_answer() {
    guided(List.of(), List.of(), "1", "1", "", "q");

    assertThat(ran).containsExactly(List.of("init"));
  }

  // ---- export -----------------------------------------------------------------------------------

  @Test
  void should_run_a_whole_repository_export_over_rest_with_the_file_the_operator_names() {
    int code = guided(List.of(), List.of(), "3", "1", "/backups/repo.zip", "n", "n", "n", "n", "q");

    assertThat(code).isZero();
    assertThat(ran)
        .containsExactly(List.of("export", "--strategy", "rest", "--out", "/backups/repo.zip"));
    assertThat(text.toString())
        .contains("Back up content")
        .contains(
            "1) Everything in the repository, over REST (no vendor tools; the server keeps"
                + " running)")
        .contains("3) Everything including users, roles and settings, with the vendor js-export")
        .contains("jrsctl export --strategy rest --out /backups/repo.zip");
  }

  @Test
  void should_ask_the_export_options_for_a_whole_repository_export() {
    guided(List.of(), List.of(), "3", "1", "/tmp/repo.zip", "y", "y", "y", "y", "q");

    assertThat(ran)
        .containsExactly(
            List.of(
                "export",
                "--strategy",
                "rest",
                "--users-roles",
                "--access-events",
                "--audit-events",
                "--monitoring",
                "--settings",
                "--portable",
                "--out",
                "/tmp/repo.zip"));
    assertThat(text.toString()).contains("Portable (decryptable on another server)?");
  }

  @Test
  void should_ask_about_stopping_for_the_vendor_export_and_skip_the_scope_questions() {
    guided(List.of(), List.of(), "3", "3", "y", "/tmp/all.zip", "n", "q");

    assertThat(text.toString())
        .contains("Stop the server while js-export runs (consistent copy, short outage)?");
    assertThat(ran)
        .containsExactly(
            List.of("export", "--full-server", "--stop-service", "--out", "/tmp/all.zip"));
  }

  @Test
  void should_pass_the_global_options_the_menu_was_started_with() {
    guided(
        List.of("--home", "/srv/jrsctl"),
        List.of(),
        "3",
        "2",
        "/public",
        "/b/public.zip",
        "n",
        "n",
        "n",
        "n",
        "q");

    assertThat(ran)
        .containsExactly(
            List.of(
                "export", "--uri", "/public", "--out", "/b/public.zip", "--home", "/srv/jrsctl"));
  }

  // ---- import -----------------------------------------------------------------------------------

  @Test
  void should_explain_the_pre_import_snapshot_and_pass_no_option_when_every_answer_is_enter()
      throws Exception {
    Path archive = Files.writeString(tmp.resolve("in.zip"), "zip");

    guided(List.of(), List.of(), "4", archive.toString(), "", "", "", "", "", "q");

    assertThat(text.toString())
        .contains(
            "jrsctl first exports the resources the archive will touch (the whole repository when"
                + " the archive has no .jrsctl.json beside it) as a rollback copy under "
                + tmp.resolve("home").resolve("snapshots"));
    assertThat(ran).containsExactly(List.of("import", archive.toString()));
  }

  @Test
  void should_ask_the_import_options_and_pass_the_ones_chosen() throws Exception {
    Path archive = Files.writeString(tmp.resolve("in.zip"), "zip");

    guided(
        List.of(),
        List.of(),
        "4",
        archive.toString(),
        "y",
        "y",
        "skip",
        "vendor",
        "deprecatedImportExportEncSecret",
        "q");

    assertThat(ran)
        .containsExactly(
            List.of(
                "import",
                archive.toString(),
                "--update",
                "--skip-themes",
                "--broken-dependencies",
                "skip",
                "--strategy",
                "vendor",
                "--key-alias",
                "deprecatedImportExportEncSecret"));
    assertThat(text.toString()).contains("Key alias the archive was encrypted with");
  }

  @Test
  void should_reask_an_answer_that_is_not_one_of_the_choices() throws Exception {
    Path archive = Files.writeString(tmp.resolve("in.zip"), "zip");

    guided(List.of(), List.of(), "4", archive.toString(), "", "", "maybe", "include", "", "", "q");

    assertThat(text.toString()).contains("please answer fail, include, skip");
    assertThat(ran)
        .containsExactly(List.of("import", archive.toString(), "--broken-dependencies", "include"));
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
        "n",
        "",
        "",
        "",
        "q");

    assertThat(text.toString()).contains("no such file");
    assertThat(ran).containsExactly(List.of("import", archive.toString()));
  }

  // ---- hotfix -----------------------------------------------------------------------------------

  /**
   * ADR-0027: apply does its own verification and, for an official Jaspersoft package, asks for the
   * checksum; a verify gate in front of it refused every official package (field test 2, H1).
   */
  @Test
  void should_run_apply_directly_when_a_hotfix_is_chosen() throws Exception {
    Path bundle = Files.writeString(tmp.resolve("HF-1.zip"), "zip");

    guided(List.of(), List.of(), "5", "1", bundle.toString(), "q");

    assertThat(ran).containsExactly(List.of("hotfix", "apply", bundle.toString()));
  }

  /** Field test 2, H5: hotfixes are cumulative, so the entry says what a rollback does. */
  @Test
  void should_word_the_rollback_entry_and_offer_cascade() {
    guided(List.of(), List.of(), "5", "2", "JRSHF-10.0.0-20260730-0457", "y", "q");

    assertThat(text.toString())
        .contains("2) Roll back a hotfix (puts back the files it replaced)")
        .contains("Also roll back the hotfixes applied after it, if any?");
    assertThat(ran)
        .containsExactly(
            List.of("hotfix", "list"),
            List.of("hotfix", "rollback", "JRSHF-10.0.0-20260730-0457", "--cascade"));
  }

  @Test
  void should_roll_back_without_cascade_when_the_operator_declines_it() {
    guided(List.of(), List.of(), "5", "2", "JRS-8.2.0-HF-0001", "n", "q");

    assertThat(ran)
        .containsExactly(
            List.of("hotfix", "list"), List.of("hotfix", "rollback", "JRS-8.2.0-HF-0001"));
  }

  // ---- upgrade ----------------------------------------------------------------------------------

  /** ADR-0029: newdb's own export is the backup its rollback rebuilds the database from. */
  @Test
  void should_start_a_newdb_upgrade_without_a_backup_question() throws Exception {
    Path pkg = Files.createDirectories(tmp.resolve("pkg"));

    guided(List.of(), List.of(), "6", "10.0.0", pkg.toString(), "", "", "", "n", "q");

    assertThat(ran)
        .containsExactly(List.of("upgrade", "--to", "10.0.0", "--package", pkg.toString()));
    assertThat(text.toString()).contains("--restore-database").doesNotContain("Database backed up");
  }

  /** Spec §10.2 "Rehearsal": the menu offers the vendor's validation before the real run. */
  @Test
  void should_rehearse_first_when_the_operator_accepts_the_default() throws Exception {
    Path pkg = Files.createDirectories(tmp.resolve("pkg"));

    guided(List.of(), List.of(), "6", "10.0.0", pkg.toString(), "", "", "", "", "q");

    assertThat(ran)
        .containsExactly(
            List.of("upgrade", "--to", "10.0.0", "--package", pkg.toString(), "--test"),
            List.of("upgrade", "--to", "10.0.0", "--package", pkg.toString()));
    assertThat(text.toString()).contains("Rehearse with the vendor's validation first");
  }

  @Test
  void should_ask_mode_tomcat_and_the_backup_for_a_samedb_upgrade() throws Exception {
    Path pkg = Files.createDirectories(tmp.resolve("pkg"));
    Path tomcat = Files.createDirectories(tmp.resolve("tomcat-11"));

    guided(
        List.of(),
        List.of(),
        "6",
        "10.0.0",
        pkg.toString(),
        "samedb",
        tomcat.toString(),
        "n",
        "y",
        "q");

    assertThat(ran)
        .containsExactly(
            List.of(
                "upgrade",
                "--to",
                "10.0.0",
                "--package",
                pkg.toString(),
                "--mode",
                "samedb",
                "--tomcat-dir",
                tomcat.toString(),
                "--db-backup-confirmed"));
    assertThat(text.toString()).doesNotContain("Export taken earlier");
  }

  @Test
  void should_not_start_a_samedb_upgrade_until_the_database_backup_is_confirmed() throws Exception {
    Path pkg = Files.createDirectories(tmp.resolve("pkg"));

    guided(List.of(), List.of(), "6", "10.0.0", pkg.toString(), "samedb", "", "n", "n", "q");

    assertThat(ran).isEmpty();
    assertThat(text.toString()).contains("Back up the repository database first");
  }

  /** ADR-0028: an export taken earlier, and the key it was encrypted with, from the menu. */
  @Test
  void should_ask_the_existing_export_and_its_key_alias_for_a_newdb_upgrade() throws Exception {
    Path pkg = Files.createDirectories(tmp.resolve("pkg"));
    Path export = Files.writeString(tmp.resolve("earlier.zip"), "zip");

    guided(
        List.of(),
        List.of(),
        "6",
        "10.0.0",
        pkg.toString(),
        "",
        "",
        export.toString(),
        "deprecatedImportExportEncSecret",
        "n",
        "q");

    assertThat(ran)
        .containsExactly(
            List.of(
                "upgrade",
                "--to",
                "10.0.0",
                "--package",
                pkg.toString(),
                "--export",
                export.toString(),
                "--key-alias",
                "deprecatedImportExportEncSecret"));
  }

  @Test
  void should_reask_a_mode_that_is_neither_newdb_nor_samedb() throws Exception {
    Path pkg = Files.createDirectories(tmp.resolve("pkg"));

    guided(List.of(), List.of(), "6", "10.0.0", pkg.toString(), "bogus", "newdb", "", "", "n", "q");

    assertThat(text.toString()).contains("please answer newdb, samedb");
    assertThat(ran)
        .containsExactly(List.of("upgrade", "--to", "10.0.0", "--package", pkg.toString()));
  }
}
