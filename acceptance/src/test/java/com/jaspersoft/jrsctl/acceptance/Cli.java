package com.jaspersoft.jrsctl.acceptance;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Runs the packaged {@code jrsctl.jar} as a separate process, the way an operator would. Invariant:
 * acceptance tests never call application classes directly; the shaded jar is the unit under test.
 */
final class Cli {

  record Result(int exitCode, String stdout, String stderr) {
    Result assertExit(int expected) {
      assertThat(exitCode)
          .as("exit code\nstdout:\n%s\nstderr:\n%s", stdout, stderr)
          .isEqualTo(expected);
      return this;
    }
  }

  private final Path jar;
  private final String java;
  private final Map<String, String> env;

  Cli(Map<String, String> env) {
    this.jar = Path.of(System.getProperty("jrsctl.jar"));
    this.java = System.getProperty("jrsctl.java");
    this.env = env;
    assertThat(jar).as("shaded jar built by the app module").exists();
  }

  Cli() {
    this(Map.of());
  }

  Result run(String... args) throws IOException, InterruptedException {
    List<String> cmd = new ArrayList<>();
    cmd.add(java);
    cmd.add("-jar");
    cmd.add(jar.toString());
    cmd.addAll(List.of(args));
    ProcessBuilder pb = new ProcessBuilder(cmd);
    pb.environment().putAll(env);
    Path out = Files.createTempFile("jrsctl-out", ".txt");
    Path err = Files.createTempFile("jrsctl-err", ".txt");
    pb.redirectOutput(out.toFile());
    pb.redirectError(err.toFile());
    Process p = pb.start();
    if (!p.waitFor(120, TimeUnit.SECONDS)) {
      p.destroyForcibly();
      throw new IllegalStateException("jrsctl did not exit within 120s: " + cmd);
    }
    String o = Files.readString(out, StandardCharsets.UTF_8);
    String e = Files.readString(err, StandardCharsets.UTF_8);
    Files.deleteIfExists(out);
    Files.deleteIfExists(err);
    return new Result(p.exitValue(), o, e);
  }
}
