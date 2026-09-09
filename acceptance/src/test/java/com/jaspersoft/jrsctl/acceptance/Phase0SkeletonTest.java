package com.jaspersoft.jrsctl.acceptance;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
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
    Path root = Path.of(System.getProperty("jrsctl.acceptanceDir")).getParent();
    assertThat(root.resolve("CLAUDE.md")).exists();
    assertThat(root.resolve("docs/spec.md")).exists();
    assertThat(root.resolve("docs/BUILD_STATUS.md")).exists();
    assertThat(root.resolve(".github/workflows/ci.yml")).exists();
    assertThat(Files.isDirectory(root.resolve("docs/decisions"))).isTrue();
  }
}
