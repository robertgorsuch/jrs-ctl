package com.jaspersoft.jrsctl.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * #351: a jrsctl home that exists but is not a folder is refused before anything opens a file under
 * it: exit 2 with the path and a remedy, never an "unexpected" error with a stack trace, whatever
 * the command.
 */
class HomeNotAFolderTest {

  @TempDir Path tmp;

  @Test
  void should_refuse_every_command_with_exit_2_when_the_home_is_a_file() throws IOException {
    Path file = Files.writeString(tmp.resolve("home"), "not a folder");

    for (String[] command :
        new String[][] {{"runs", "list"}, {"runs", "prune", "--yes"}, {"doctor"}}) {
      InitCommandTest.Run run = run(file, command);

      assertThat(run.code()).as(String.join(" ", command)).isEqualTo(ExitCodes.PRECHECK_FAILED);
      assertThat(run.err())
          .as(String.join(" ", command))
          .contains("the jrsctl home " + file.toAbsolutePath() + " is not a folder")
          .contains("--home <dir> or JRSCTL_HOME")
          .doesNotContain("unexpected");
    }
    assertThat(Files.readString(file)).isEqualTo("not a folder");
  }

  @Test
  void should_refuse_with_exit_2_when_the_home_is_a_link_to_nothing() throws IOException {
    Path link = tmp.resolve("home");
    try {
      Files.createSymbolicLink(link, tmp.resolve("gone"));
    } catch (IOException | UnsupportedOperationException e) {
      // Windows needs developer mode or an elevated account to create a symbolic link
      assumeThat(false).as("cannot create a symbolic link here: " + e).isTrue();
    }

    InitCommandTest.Run run = run(link, "runs", "list");

    assertThat(run.code()).isEqualTo(ExitCodes.PRECHECK_FAILED);
    assertThat(run.err()).contains("is a link to nothing");
    assertThat(Files.exists(tmp.resolve("gone"))).isFalse();
  }

  private static InitCommandTest.Run run(Path home, String... command) {
    String[] args = new String[command.length + 3];
    System.arraycopy(command, 0, args, 0, command.length);
    args[command.length] = "--home";
    args[command.length + 1] = home.toString();
    args[command.length + 2] = "--no-color";
    return InitCommandTest.run(args);
  }
}
