package com.jaspersoft.jrsctl.acceptance;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarFile;
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
  void packaged_jar_carries_no_mock_backend() throws Exception {
    Path jar = Path.of(System.getProperty("jrsctl.jar"));
    try (JarFile packaged = new JarFile(jar.toFile())) {
      assertThat(packaged.getEntry("web/app.js")).as("the console UI ships").isNotNull();
      assertThat(packaged.getEntry("web/mock.js"))
          .as("the sample backend must not be reachable from a real console")
          .isNull();
      assertThat(packaged.getEntry("web/README.md")).isNull();
    }
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

  /**
   * A workflow that runs on a moving tag executes whatever that tag points at tomorrow, and a job
   * without a timeout can hold a runner for six hours. Every {@code uses:} must name a 40-hex
   * commit and every job must set {@code timeout-minutes} (assessment item 5.5).
   */
  @Test
  void every_workflow_pins_actions_to_a_commit_and_bounds_every_job() throws Exception {
    Path workflows = repoRoot().resolve(".github/workflows");
    YAMLMapper yaml = new YAMLMapper();
    List<String> problems = new java.util.ArrayList<>();
    try (Stream<Path> listing = Files.list(workflows)) {
      for (Path file :
          listing.filter(p -> p.getFileName().toString().matches(".*\\.ya?ml")).toList()) {
        JsonNode tree;
        try (InputStream in = Files.newInputStream(file)) {
          tree = yaml.readTree(in);
        }
        tree.path("jobs")
            .properties()
            .forEach(
                job -> {
                  String where = file.getFileName() + " job " + job.getKey();
                  if (!job.getValue().path("timeout-minutes").isNumber()) {
                    problems.add(where + " has no timeout-minutes");
                  }
                  for (JsonNode step : job.getValue().path("steps")) {
                    String uses = step.path("uses").asText("");
                    if (!uses.isEmpty() && !uses.matches("[^@]+@[0-9a-f]{40}")) {
                      problems.add(where + " uses " + uses + " (not pinned to a commit)");
                    }
                  }
                });
      }
    }
    assertThat(problems).as("workflow hygiene").isEmpty();
  }

  /**
   * The repository once shipped a GPL-3.0 {@code LICENSE} beside a README that said "All rights
   * reserved. Licensed under the Apache License, Version 2.0" (ADR-0010). Every place that names
   * the licence must name the one in {@code LICENSE}, by its SPDX identifier, so a stray edit
   * cannot reopen the contradiction.
   */
  @Test
  void licence_is_gpl_3_and_every_document_says_so() throws Exception {
    Path root = repoRoot();
    String licence = Files.readString(root.resolve("LICENSE"));
    assertThat(licence).startsWith("                    GNU GENERAL PUBLIC LICENSE");
    assertThat(licence).contains("Version 3, 29 June 2007");
    assertThat(root.resolve("docs/decisions/0010-gpl-3-licence.md")).exists();

    for (String file :
        List.of(
            "README.md",
            "CONTRIBUTING.md",
            "pom.xml",
            "dist/src/image/README.txt",
            "dist/src/image/LICENSE-THIRD-PARTY.txt")) {
      String text = Files.readString(root.resolve(file));
      assertThat(text).as(file + " names the licence by SPDX id").contains("GPL-3.0-only");
      assertThat(text)
          .as(file + " does not contradict LICENSE")
          .doesNotContainIgnoringCase("Apache License, Version 2.0")
          .doesNotContainIgnoringCase("all rights reserved");
    }
  }

  private static Path repoRoot() {
    return Path.of(System.getProperty("jrsctl.acceptanceDir")).getParent();
  }
}
