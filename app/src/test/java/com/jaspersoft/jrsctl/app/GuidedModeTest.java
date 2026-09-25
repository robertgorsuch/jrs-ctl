package com.jaspersoft.jrsctl.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
    return guided(Map.of(), base, pending, answers);
  }

  /** {@code settings} is what the configuration holds; empty means there is none yet. */
  private int guided(
      Map<String, String> settings, List<String> base, List<String> pending, String... answers) {
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
            () -> Optional.of(tmp.resolve("home").resolve("snapshots")),
            () -> settings);
    return mode.run();
  }

  private static Map<String, String> someSettings() {
    Map<String, String> m = new java.util.LinkedHashMap<>();
    m.put("server.baseUrl", "http://old:8080/jasperserver-pro");
    m.put("server.auth.passwordRef", "env:JRS_PASSWORD");
    m.put("vendor.javaHome", "/old/jdk");
    return m;
  }

  // ---- the menu itself --------------------------------------------------------------------------

  @Test
  void should_offer_documentation_and_name_the_other_entry_points() {
    guided(List.of(), List.of(), "q");

    assertThat(text.toString())
        .contains("8) Read the documentation")
        .contains("jrsctl --help lists")
        .contains("every command")
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

  /** Field test 3: the settings are listed, numbered, before anything is asked. */
  @Test
  void should_show_the_settings_numbered_when_the_settings_entry_opens() {
    guided(someSettings(), List.of(), List.of(), "1", "", "q");

    assertThat(text.toString())
        .contains("1) server.baseUrl = http://old:8080/jasperserver-pro")
        .contains("3) vendor.javaHome = /old/jdk")
        .contains("1) Change a setting");
    assertThat(ran).isEmpty();
  }

  /** Field test 3: a setting is picked by name or by number, the old value offered for editing. */
  @Test
  void should_change_a_setting_when_it_is_picked_by_name_or_by_number() {
    guided(
        someSettings(),
        List.of(),
        List.of(),
        "1",
        "1",
        "server.baseUrl",
        "http://x",
        "3",
        "/jdk",
        "",
        "q");

    assertThat(ran)
        .containsExactly(
            List.of("config", "set", "server.baseUrl", "http://x"),
            List.of("config", "set", "vendor.javaHome", "/jdk"));
    assertThat(text.toString()).contains("vendor.javaHome: [/old/jdk]");
  }

  @Test
  void should_keep_the_value_when_enter_accepts_the_one_shown() {
    guided(someSettings(), List.of(), List.of(), "1", "1", "3", "", "", "q");

    assertThat(text.toString()).contains("unchanged");
    assertThat(ran).isEmpty();
  }

  @Test
  void should_ask_for_a_password_hidden_when_a_secret_setting_is_picked() {
    guided(someSettings(), List.of(), List.of(), "1", "1", "2", "", "q");

    assertThat(ran).containsExactly(List.of("config", "set", "server.auth.passwordRef"));
  }

  @Test
  void should_reask_an_unknown_setting_instead_of_sending_it() {
    guided(
        someSettings(), List.of(), List.of(), "1", "1", "nope.key", "99", "1", "http://x", "", "q");

    assertThat(text.toString()).contains("no setting nope.key").contains("no setting 99");
    assertThat(ran).containsExactly(List.of("config", "set", "server.baseUrl", "http://x"));
  }

  @Test
  void should_detect_again_when_settings_exist_and_the_operator_asks() {
    guided(someSettings(), List.of(), List.of(), "1", "2", "", "q");

    assertThat(ran).containsExactly(List.of("init"));
  }

  @Test
  void should_reask_when_the_installation_directory_does_not_exist() {
    guided(List.of(), List.of(), "1", "/zugzug/whatever", "", "q");

    assertThat(text.toString())
        .contains("There are no settings yet")
        .contains("no such directory: /zugzug/whatever");
    assertThat(ran).isEmpty();
  }

  @Test
  void should_search_for_the_installation_when_enter_is_the_first_answer() {
    guided(List.of(), List.of(), "1", "", "q");

    assertThat(ran).containsExactly(List.of("init"));
  }

  // ---- export -----------------------------------------------------------------------------------

  @Test
  void should_run_a_whole_repository_export_over_rest_with_the_file_the_operator_names() {
    int code =
        guided(
            List.of(), List.of(), "3", "1", "n", "/backups/repo.zip", "n", "n", "n", "n", "", "q");

    assertThat(code).isZero();
    assertThat(ran)
        .containsExactly(List.of("export", "--strategy", "rest", "--out", "/backups/repo.zip"));
    assertThat(text.toString())
        .contains("Back up content")
        .contains("1) Everything")
        .contains("2) One folder")
        .doesNotContain("3) Everything")
        .contains("Include scheduled report jobs and calendars?")
        .contains("jrsctl export --strategy rest --out /backups/repo.zip");
  }

  @Test
  void should_ask_the_export_options_for_a_whole_repository_export() {
    guided(List.of(), List.of(), "3", "1", "n", "/tmp/repo.zip", "y", "y", "y", "y", "org1", "q");

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
                "--legacy-key",
                "--organization",
                "org1",
                "--out",
                "/tmp/repo.zip"));
    assertThat(text.toString())
        .contains("Encrypt with the Legacy key (deprecatedImportExportEncSecret)");
  }

  @Test
  void should_ask_about_stopping_for_the_vendor_export_and_skip_the_scope_questions() {
    guided(List.of(), List.of(), "3", "1", "y", "y", "/tmp/all.zip", "n", "", "q");

    assertThat(text.toString()).contains("Stop the server while js-export runs");
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
        "",
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

    guided(List.of(), List.of(), "4", archive.toString(), "", "", "", "", "", "", "", "q");

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
        "",
        "y",
        "skip",
        "buildomatic",
        "deprecatedImportExportEncSecret",
        "org1",
        "y",
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
                "buildomatic",
                "--key-alias",
                "deprecatedImportExportEncSecret",
                "--organization",
                "org1",
                "--merge-organization"));
    assertThat(text.toString())
        .contains("Import into organisation")
        .contains("Merge when the archive's organisation id differs");
    assertThat(text.toString())
        .contains("No .jrsctl.json beside the archive")
        .contains("Legacy key (deprecatedImportExportEncSecret");
  }

  /** Field test 3: with a sidecar the key alias is known, so nothing is asked about it. */
  @Test
  void should_take_the_key_alias_from_the_sidecar_without_asking_when_there_is_one()
      throws Exception {
    Path archive = Files.writeString(tmp.resolve("in.zip"), "zip");
    com.jaspersoft.jrsctl.jrs.strategy.Sidecar.write(
        com.jaspersoft.jrsctl.jrs.strategy.Sidecar.pathFor(archive),
        new com.jaspersoft.jrsctl.jrs.strategy.Sidecar(
            java.time.Instant.parse("2026-09-25T10:00:00Z"),
            "http://src",
            "10.0.0",
            Optional.empty(),
            new com.jaspersoft.jrsctl.jrs.strategy.Sidecar.Flags(
                com.jaspersoft.jrsctl.jrs.api.ExportRequest.Scope.EVERYTHING,
                List.of("/"),
                false,
                false,
                false,
                false,
                false,
                false,
                Optional.of("deprecatedImportExportEncSecret")),
            "abc",
            com.jaspersoft.jrsctl.jrs.api.ExportImportStrategy.Kind.REST));

    guided(List.of(), List.of(), "4", archive.toString(), "", "", "", "", "", "q");

    assertThat(text.toString())
        .contains("says it was encrypted with the key alias deprecatedImportExportEncSecret")
        .doesNotContain("Was it exported with the legacy");
    assertThat(ran).containsExactly(List.of("import", archive.toString()));
  }

  @Test
  void should_pass_the_legacy_key_alias_when_the_operator_answers_yes() throws Exception {
    Path archive = Files.writeString(tmp.resolve("in.zip"), "zip");

    guided(List.of(), List.of(), "4", archive.toString(), "", "", "", "", "yes", "", "q");

    assertThat(ran)
        .containsExactly(
            List.of(
                "import", archive.toString(), "--key-alias", "deprecatedImportExportEncSecret"));
  }

  /** ADR-0040: leaving out the rollback copy takes an explicit "n"; Enter keeps it. */
  @Test
  void should_pass_no_snapshot_only_when_the_rollback_copy_is_declined() throws Exception {
    Path archive = Files.writeString(tmp.resolve("in.zip"), "zip");

    guided(List.of(), List.of(), "4", archive.toString(), "y", "n", "", "", "", "", "", "q");

    assertThat(text.toString()).contains("cannot put back what it overwrote");
    assertThat(ran)
        .containsExactly(List.of("import", archive.toString(), "--update", "--no-snapshot"));
  }

  @Test
  void should_reask_an_answer_that_is_not_one_of_the_choices() throws Exception {
    Path archive = Files.writeString(tmp.resolve("in.zip"), "zip");

    guided(
        List.of(),
        List.of(),
        "4",
        archive.toString(),
        "",
        "",
        "",
        "maybe",
        "include",
        "",
        "",
        "",
        "q");

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
        "",
        "n",
        "",
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

    guided(List.of(), List.of(), "6", "10.0.0", pkg.toString(), "", "", "", "", "n", "q");

    assertThat(ran)
        .containsExactly(List.of("upgrade", "--to", "10.0.0", "--package", pkg.toString()));
    assertThat(text.toString()).contains("--restore-database").doesNotContain("Database backed up");
  }

  /** Issue #106: the events the newdb script leaves behind are a question, off by default. */
  @Test
  void should_ask_for_the_events_after_the_export_for_a_newdb_upgrade() throws Exception {
    Path pkg = Files.createDirectories(tmp.resolve("pkg"));

    guided(List.of(), List.of(), "6", "10.0.0", pkg.toString(), "", "", "", "y", "n", "q");

    assertThat(ran)
        .containsExactly(
            List.of("upgrade", "--to", "10.0.0", "--package", pkg.toString(), "--include-events"));
    assertThat(text.toString()).contains("js-upgrade-newdb leaves them behind");
  }

  /** Spec §10.2 "Rehearsal": the menu offers the vendor's validation before the real run. */
  @Test
  void should_rehearse_first_when_the_operator_accepts_the_default() throws Exception {
    Path pkg = Files.createDirectories(tmp.resolve("pkg"));

    guided(List.of(), List.of(), "6", "10.0.0", pkg.toString(), "", "", "", "", "", "q");

    assertThat(ran)
        .containsExactly(
            List.of("upgrade", "--to", "10.0.0", "--package", pkg.toString(), "--test"),
            List.of("upgrade", "--to", "10.0.0", "--package", pkg.toString()));
    assertThat(text.toString()).contains("Rehearse with buildomatic's own validation first");
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
                "--mode",
                "samedb",
                "--tomcat-dir",
                tomcat.toString(),
                "--db-backup-confirmed"));
    assertThat(text.toString()).doesNotContain("Export taken earlier");
  }

  /** Issue #108: the vendor's password migration is a samedb question, off by default. */
  @Test
  void should_ask_for_the_password_migration_after_the_backup_for_a_samedb_upgrade()
      throws Exception {
    Path pkg = Files.createDirectories(tmp.resolve("pkg"));

    guided(List.of(), List.of(), "6", "10.1.0", pkg.toString(), "samedb", "", "n", "y", "y", "q");

    assertThat(ran)
        .containsExactly(
            List.of(
                "upgrade",
                "--to",
                "10.1.0",
                "--package",
                pkg.toString(),
                "--mode",
                "samedb",
                "--db-backup-confirmed",
                "--migrate-passwords"));
    assertThat(text.toString()).contains("js-ant migrate-passwords");
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
        "",
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

    guided(
        List.of(),
        List.of(),
        "6",
        "10.0.0",
        pkg.toString(),
        "bogus",
        "newdb",
        "",
        "",
        "",
        "n",
        "q");

    assertThat(text.toString()).contains("please answer newdb, samedb");
    assertThat(ran)
        .containsExactly(List.of("upgrade", "--to", "10.0.0", "--package", pkg.toString()));
  }

  /**
   * The menu says buildomatic, never "vendor", and names no company: the operator's own words for
   * the tools, and the product name alone.
   */
  @Test
  void should_say_buildomatic_and_name_no_company_when_the_menu_is_shown() throws Exception {
    Path archive = Files.writeString(tmp.resolve("in.zip"), "zip");

    guided(List.of(), List.of(), "4", archive.toString(), "", "", "", "buildomatic", "", "", "q");

    assertThat(text.toString())
        .contains("Strategy: auto, rest or buildomatic")
        .doesNotContainIgnoringCase("vendor")
        .doesNotContain("Actian");
    assertThat(ran)
        .containsExactly(List.of("import", archive.toString(), "--strategy", "buildomatic"));
  }
}
