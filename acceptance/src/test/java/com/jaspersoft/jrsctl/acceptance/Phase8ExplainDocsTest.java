package com.jaspersoft.jrsctl.acceptance;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Spec §14 Phase 8, "offline docs embedded" and "{@code --explain} on every command", exercised
 * through the packaged jar with no home, configuration or server: the explanations come from the
 * operator guide embedded at build time, {@code docs} lists and prints the embedded documents, and
 * {@code help <command>} prints usage.
 */
@Tag("phase8")
class Phase8ExplainDocsTest {

  private final Cli cli = new Cli();

  @Test
  void hotfix_apply_explain_states_the_service_stop_rule_without_running() throws Exception {
    Cli.Result r = cli.run("hotfix", "apply", "--explain").assertExit(0);
    assertThat(r.stdout())
        .startsWith("jrsctl hotfix apply")
        .contains("WEB-INF/lib")
        .contains("WEB-INF/classes")
        .contains("stops the service first")
        .contains("**Mutates:**")
        .contains("**Rollback:**")
        .contains("**Exit codes:**")
        .contains("**Flags:**");
    assertThat(r.stderr()).isEmpty();
  }

  @Test
  void upgrade_explain_states_the_database_backup_responsibility() throws Exception {
    Cli.Result r = cli.run("upgrade", "--explain").assertExit(0);
    assertThat(r.stdout())
        .startsWith("jrsctl upgrade")
        .contains("database backup")
        .contains("--db-backup-confirmed")
        .contains("samedb")
        .contains("files only");
  }

  @Test
  void explain_is_accepted_anywhere_and_on_groups() throws Exception {
    Cli.Result deep = cli.run("--explain", "runs", "recover").assertExit(0);
    assertThat(deep.stdout()).startsWith("jrsctl runs recover").contains("--resume");
    Cli.Result group = cli.run("secrets", "--explain").assertExit(0);
    assertThat(group.stdout()).contains("jrsctl secrets init").contains("jrsctl secrets list");
  }

  @Test
  void docs_lists_the_embedded_documents() throws Exception {
    Cli.Result r = cli.run("docs").assertExit(0);
    assertThat(r.stdout())
        .contains("operator-guide")
        .contains("hotfix-authoring")
        .contains("security")
        .contains("readme");
    Cli.Result json = cli.run("docs", "--json").assertExit(0);
    assertThat(json.stdout().trim()).startsWith("[").contains("\"name\"").contains("\"bytes\"");
  }

  @Test
  void docs_prints_the_operator_guide() throws Exception {
    Cli.Result r = cli.run("docs", "operator-guide").assertExit(0);
    assertThat(r.stdout())
        .startsWith("# jrsctl operator guide")
        .contains("### `jrsctl hotfix apply")
        .contains("### `jrsctl runs prune");
    cli.run("docs", "hotfix-authoring").assertExit(0);
    cli.run("docs", "security").assertExit(0);
    cli.run("docs", "readme").assertExit(0);
    cli.run("docs", "no-such-document").assertExit(1);
  }

  @Test
  void help_subcommand_prints_usage_of_a_command() throws Exception {
    Cli.Result r = cli.run("help", "runs").assertExit(0);
    assertThat(r.stdout()).contains("Usage").contains("recover").contains("--explain");
  }
}
