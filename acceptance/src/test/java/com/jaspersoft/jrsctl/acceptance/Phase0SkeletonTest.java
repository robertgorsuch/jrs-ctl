package com.jaspersoft.jrsctl.acceptance;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Spec §14 Phase 0: multi-module build, CLAUDE.md, {@code --version}, {@code selfcheck}, CI. */
@Tag("phase0")
class Phase0SkeletonTest {

  private final Cli cli = new Cli();

  @Test
  void version_prints_product_and_vendor() throws Exception {
    Cli.Result r = cli.run("--version").assertExit(0);
    assertThat(r.stdout()).contains("jrsctl " + System.getProperty("jrsctl.version"));
    assertThat(r.stdout()).contains("Actian Jaspersoft");
  }

  @Test
  void selfcheck_passes_on_the_packaged_jar() throws Exception {
    Cli.Result r = cli.run("selfcheck").assertExit(0);
    assertThat(r.stdout()).contains("PASS").contains("selfcheck ok").doesNotContain("FAIL");
  }

  @Test
  void selfcheck_json_is_machine_readable() throws Exception {
    Cli.Result r = cli.run("selfcheck", "--json").assertExit(0);
    assertThat(r.stdout().trim()).startsWith("{").contains("\"items\"");
  }

  @Test
  void repo_carries_claude_md_and_spec_and_ci_workflow() {
    Path root = repoRoot();
    assertThat(root.resolve("CLAUDE.md")).exists();
    assertThat(root.resolve("docs/spec.md")).exists();
    assertThat(root.resolve("docs/BUILD_STATUS.md")).exists();
    assertThat(root.resolve(".github/workflows/ci.yml")).exists();
    assertThat(Files.isDirectory(root.resolve("docs/decisions"))).isTrue();
  }

  /**
   * A workflow file that GitHub cannot parse fails every run in zero seconds with no jobs, which
   * looks like a red build but is really a silent gate. Every file under {@code .github/workflows}
   * must parse as YAML and declare at least one job.
   */
  @Test
  void every_workflow_file_parses_as_yaml_and_declares_jobs() throws Exception {
    Path workflows = repoRoot().resolve(".github/workflows");
    List<Path> files;
    try (Stream<Path> listing = Files.list(workflows)) {
      files =
          listing.filter(p -> p.getFileName().toString().matches(".*\\.ya?ml")).sorted().toList();
    }
    assertThat(files).as("workflow files under %s", workflows).isNotEmpty();
    YAMLMapper yaml = new YAMLMapper();
    for (Path file : files) {
      JsonNode tree;
      try (InputStream in = Files.newInputStream(file)) {
        tree = yaml.readTree(in);
      } catch (Exception e) {
        throw new AssertionError("workflow " + file.getFileName() + " is not valid YAML: " + e, e);
      }
      assertThat(tree.path("jobs").isObject())
          .as("workflow %s must declare a jobs mapping", file.getFileName())
          .isTrue();
      assertThat(tree.path("jobs").size())
          .as("workflow %s must declare at least one job", file.getFileName())
          .isPositive();
    }
  }

  private static Path repoRoot() {
    return Path.of(System.getProperty("jrsctl.acceptanceDir")).getParent();
  }
}
