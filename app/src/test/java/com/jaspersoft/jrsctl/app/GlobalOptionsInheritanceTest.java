package com.jaspersoft.jrsctl.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.jaspersoft.jrsctl.core.json.Json;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Model.OptionSpec;
import picocli.CommandLine.ParseResult;

/** #354: global options given before the subcommand reach the command that runs. */
class GlobalOptionsInheritanceTest {

  @TempDir Path tmp;

  @Test
  void should_name_every_global_option_in_a_group_when_options_are_added() {
    List<String> declared =
        CommandSpec.forAnnotatedObject(new GlobalOptions()).options().stream()
            .map(OptionSpec::longestName)
            .sorted()
            .toList();

    List<String> grouped =
        GlobalOptionsInheritance.GROUPS.stream().flatMap(List::stream).sorted().toList();

    assertThat(grouped).isEqualTo(declared);
  }

  @Test
  void should_carry_options_given_before_the_subcommand_down_to_it() {
    Path home = tmp.resolve("h");

    GlobalOptions leaf =
        leaf(
            "--home",
            home.toString(),
            "--json",
            "--yes",
            "--ascii",
            "--no-color",
            "--set",
            "server.baseUrl=http://x:1/",
            "runs",
            "list");

    assertThat(leaf.home()).contains(home);
    assertThat(leaf.json()).isTrue();
    assertThat(leaf.yes()).isTrue();
    assertThat(leaf.ascii()).isTrue();
    assertThat(leaf.color()).isEqualTo(GlobalOptions.Color.NEVER);
    assertThat(leaf.set()).isEqualTo(Map.of("server.baseUrl", "http://x:1/"));
  }

  @Test
  void should_let_the_subcommand_win_when_both_positions_give_an_option() {
    Path before = tmp.resolve("before");
    Path after = tmp.resolve("after");

    GlobalOptions leaf =
        leaf(
            "--home",
            before.toString(),
            "--no-color",
            "--set",
            "a=1",
            "--set",
            "b=1",
            "runs",
            "list",
            "--home",
            after.toString(),
            "--color",
            "always",
            "--set",
            "b=2");

    assertThat(leaf.home()).contains(after);
    assertThat(leaf.color())
        .as("--color after the command decides, whatever --no-color before it says")
        .isEqualTo(GlobalOptions.Color.ALWAYS);
    assertThat(leaf.set()).isEqualTo(Map.of("a", "1", "b", "2"));
  }

  @Test
  void should_leave_the_subcommand_as_parsed_when_nothing_is_given_before_it() {
    GlobalOptions leaf = leaf("runs", "list", "--json");

    assertThat(leaf.json()).isTrue();
    assertThat(leaf.home()).isEmpty();
    assertThat(leaf.set()).isEmpty();
    assertThat(leaf.color()).isEqualTo(GlobalOptions.Color.AUTO);
  }

  @Test
  void should_use_the_home_given_before_the_command_when_running_it() throws Exception {
    Path home = Files.createDirectories(tmp.resolve("home"));

    InitCommandTest.Run run = InitCommandTest.run("--home", home.toString(), "home", "show");

    assertThat(run.code()).as(run.err()).isZero();
    assertThat(run.out()).contains("jrsctl home: " + home.toAbsolutePath()).contains("--home");
  }

  @Test
  void should_apply_json_and_set_given_before_the_command_when_running_it() throws Exception {
    Path home = Files.createDirectories(tmp.resolve("home"));

    InitCommandTest.Run show =
        InitCommandTest.run(
            "--json",
            "--set",
            "server.baseUrl=http://before.invalid:1/",
            "config",
            "show",
            "--home",
            home.toString());

    assertThat(show.code()).as(show.err()).isZero();
    JsonNode doc = Json.mapper().readTree(show.out());
    assertThat(doc.toString()).contains("http://before.invalid:1/");
  }

  @Test
  void should_put_the_log_in_the_last_home_given_when_home_appears_twice() {
    Path first = tmp.resolve("first");
    Path last = tmp.resolve("last");

    Path chosen =
        LogFile.home(
            new String[] {"--home", first.toString(), "runs", "list", "--home=" + last}, Map.of());

    assertThat(chosen).isEqualTo(last);
  }

  /** Parses like the process does, applies the inheritance and returns the leaf's options. */
  private static GlobalOptions leaf(String... args) {
    CommandLine cmd = Main.commandLine();
    ParseResult parsed = cmd.parseArgs(args);
    GlobalOptionsInheritance.apply(parsed);
    ParseResult deepest = parsed;
    while (deepest.hasSubcommand()) {
      deepest = deepest.subcommand();
    }
    List<GlobalOptions> found =
        deepest.commandSpec().mixins().values().stream()
            .map(CommandSpec::userObject)
            .filter(GlobalOptions.class::isInstance)
            .map(GlobalOptions.class::cast)
            .collect(Collectors.toList());
    assertThat(found).hasSize(1);
    return found.get(0);
  }
}
