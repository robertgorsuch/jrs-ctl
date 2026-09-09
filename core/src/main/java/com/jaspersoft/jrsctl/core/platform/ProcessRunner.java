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

  record OutputLine(Stream stream, String text) {
    public enum Stream {
      STDOUT,
      STDERR
    }
  }
}
