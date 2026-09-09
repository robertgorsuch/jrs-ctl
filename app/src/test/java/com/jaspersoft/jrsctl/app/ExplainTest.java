package com.jaspersoft.jrsctl.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Model.OptionSpec;

/**
 * Spec §14 Phase 8 "--explain on every command" and §17 "Embedded help": walks the real command
 * tree and fails when a command lacks {@code --explain} or lacks a structured section in the
 * embedded operator guide, so a command added in a later phase without documentation breaks the
 * build.
 */
class ExplainTest {

  private static final List<String> REQUIRED_LABELS =
      List.of("**Mutates:**", "**Rollback:**", "**Exit codes:**", "**Flags:**");

  @Test
  void should_declare_explain_on_every_command_when_tree_is_built_by_main() {
    List<CommandLine> all = new ArrayList<>();
    collect(Main.commandLine(), all);
    assertThat(all).hasSizeGreaterThan(30);
    for (CommandLine cmd : all) {
      OptionSpec option = cmd.getCommandSpec().findOption(Explain.OPTION);
      assertThat(option).as("%s declares --explain", path(cmd)).isNotNull();
      assertThat(option.description()[0])
          .as("%s --explain is described", path(cmd))
          .contains("without running");
    }
  }

  @Test
  void should_have_structured_guide_section_when_command_is_runnable() {
    List<CommandLine> all = new ArrayList<>();
    collect(Main.commandLine(), all);
    List<String> missing = new ArrayList<>();
    List<String> malformed = new ArrayList<>();
    int checked = 0;
    for (CommandLine cmd : all) {
      if (!needsSection(cmd.getCommandSpec())) {
        continue;
      }
      checked++;
      String relative = relativePath(cmd);
      if (!Explain.hasSection(relative)) {
        missing.add(path(cmd));
        continue;
      }
      String text = Explain.text(relative).orElse("");
      for (String label : REQUIRED_LABELS) {
        if (!text.contains(label)) {
          malformed.add(path(cmd) + " lacks " + label);
        }
      }
    }
    assertThat(checked).isGreaterThan(25);
    assertThat(missing)
        .as("every runnable command has a '### `jrsctl <path> ...`' section in operator-guide.md")
        .isEmpty();
    assertThat(malformed)
        .as("every section states what it mutates, how it rolls back, its exit codes and flags")
        .isEmpty();
  }

  @Test
  void should_print_section_and_exit_zero_when_explain_given_without_required_arguments() {
    StringWriter out = new StringWriter();
    CommandLine cmd = Main.commandLine();
    cmd.setOut(new PrintWriter(out));
    int code = cmd.execute("hotfix", "apply", "--explain");
    assertThat(code).isZero();
    assertThat(out.toString())
        .startsWith("jrsctl hotfix apply")
        .contains("WEB-INF/lib")
        .contains("stops the service first")
        .contains("**Exit codes:**");
  }

  @Test
  void should_explain_deepest_command_when_explain_given_on_root() {
    StringWriter out = new StringWriter();
    CommandLine cmd = Main.commandLine();
    cmd.setOut(new PrintWriter(out));
    int code = cmd.execute("--explain", "hotfix", "build");
    assertThat(code).isZero();
    assertThat(out.toString()).startsWith("jrsctl hotfix build").contains("--key");
  }

  @Test
  void should_print_every_subcommand_section_when_explain_given_on_group() {
    StringWriter out = new StringWriter();
    CommandLine cmd = Main.commandLine();
    cmd.setOut(new PrintWriter(out));
    int code = cmd.execute("keys", "--explain");
    assertThat(code).isZero();
    assertThat(out.toString())
        .contains("jrsctl keys list")
        .contains("jrsctl keys add")
        .contains("jrsctl keys remove")
        .contains("jrsctl keys generate")
        .doesNotContain("jrsctl secrets");
  }

  @Test
  void should_print_whole_command_reference_when_explain_given_alone() {
    StringWriter out = new StringWriter();
    CommandLine cmd = Main.commandLine();
    cmd.setOut(new PrintWriter(out));
    int code = cmd.execute("--explain");
    assertThat(code).isZero();
    assertThat(out.toString()).contains("jrsctl selfcheck").contains("jrsctl console");
  }

  @Test
  void should_derive_command_path_from_heading_when_heading_has_synopsis() {
    assertThat(Explain.commandPath("`jrsctl hotfix apply <bundle> [--plan] [--yes]`"))
        .isEqualTo(Optional.of("hotfix apply"));
    assertThat(Explain.commandPath("`jrsctl export [--uri <uri>]... --out <file>`"))
        .isEqualTo(Optional.of("export"));
    assertThat(Explain.commandPath("`jrsctl upgrade rollback <runId> --to-point B|C`"))
        .isEqualTo(Optional.of("upgrade rollback"));
    assertThat(Explain.commandPath("`jrsctl runs prune [--dry-run]`"))
        .isEqualTo(Optional.of("runs prune"));
    assertThat(Explain.commandPath("Installing (portable archive)")).isEmpty();
  }

  @Test
  void should_exit_zero_and_print_usage_when_help_subcommand_names_a_command() {
    StringWriter out = new StringWriter();
    CommandLine cmd = Main.commandLine();
    cmd.setOut(new PrintWriter(out));
    int code = cmd.execute("help", "runs");
    assertThat(code).isZero();
    assertThat(out.toString()).contains("recover").contains("Usage");
  }

  private static void collect(CommandLine cmd, List<CommandLine> out) {
    out.add(cmd);
    for (CommandLine sub : cmd.getSubcommands().values()) {
      if (!out.contains(sub)) {
        collect(sub, out);
      }
    }
  }

  /** Leaves, and parents that are runnable in their own right (they declare options or params). */
  private static boolean needsSection(CommandSpec spec) {
    if (spec.parent() == null) {
      return false;
    }
    if (spec.subcommands().isEmpty()) {
      return true;
    }
    boolean ownOptions =
        spec.options().stream()
            .anyMatch(
                o -> !o.usageHelp() && !o.versionHelp() && !o.longestName().equals(Explain.OPTION));
    return ownOptions || !spec.positionalParameters().isEmpty();
  }

  private static String path(CommandLine cmd) {
    return cmd.getCommandSpec().qualifiedName(" ");
  }

  private static String relativePath(CommandLine cmd) {
    String full = path(cmd);
    return full.equals("jrsctl") ? "" : full.substring("jrsctl ".length());
  }
}
