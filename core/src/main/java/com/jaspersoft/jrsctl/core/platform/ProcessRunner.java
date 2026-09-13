package com.jaspersoft.jrsctl.core.platform;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Runs external programs without a shell (spec §5.3). Invariant: arguments are a list, never a
 * command string; stdout and stderr are streamed line by line to the consumer as they arrive; the
 * process is killed when the timeout elapses and the result says so.
 */
public interface ProcessRunner {

  record Request(
      List<String> command,
      Optional<Path> workingDir,
      Map<String, String> environment,
      Duration timeout) {}

  record Result(int exitCode, boolean timedOut, Duration elapsed) {
    public boolean ok() {
      return exitCode == 0 && !timedOut;
    }
  }

  /** Each line of output is delivered to {@code onLine} tagged with its stream. */
  Result run(Request request, Consumer<OutputLine> onLine);

  /**
   * Starts a program and returns without waiting for it: for a launcher such as {@code xdg-open} or
   * {@code rundll32} that may hand the job to a foreground program and not return until that
   * program exits, where {@link #run} would kill it and everything it started when the budget
   * elapsed (assessment item P1). Its output is discarded. Empty when the program started; the
   * reason otherwise. The default runs the program with a short budget, which is what test fakes
   * need to record the invocation.
   */
  default Optional<String> launch(List<String> command) {
    Result result =
        run(new Request(command, Optional.empty(), Map.of(), Duration.ofSeconds(15)), line -> {});
    return result.ok()
        ? Optional.empty()
        : Optional.of(result.timedOut() ? "timed out" : "exit " + result.exitCode());
  }

  record OutputLine(Stream stream, String text) {
    public enum Stream {
      STDOUT,
      STDERR
    }
  }
}
