package com.jaspersoft.jrsctl.ops.hotfix;

import com.jaspersoft.jrsctl.core.platform.FileOps;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Executes manifest {@code prechecks} and {@code postchecks} (spec §8.1). Invariants: every check
 * runs and every failure is reported; file checks resolve paths exactly like {@code files[].path};
 * http checks compare the status code with {@code expect} (default 200) and treat an unreachable
 * server as a failure of that check, never as an exception.
 */
final class CheckRunner {

  private final HotfixPaths paths;
  private final FileOps files;
  private final HttpProbe http;

  CheckRunner(HotfixPaths paths, FileOps files, HttpProbe http) {
    this.paths = paths;
    this.files = files;
    this.http = http;
  }

  /** Failure messages, one per failed check; empty when all pass. */
  List<String> run(List<Manifest.Check> checks) {
    List<String> failures = new ArrayList<>();
    for (Manifest.Check check : checks) {
      one(check).ifPresent(failures::add);
    }
    return List.copyOf(failures);
  }

  private java.util.Optional<String> one(Manifest.Check check) {
    String label = check.describe();
    try {
      return switch (check.type()) {
        case FILE_EXISTS -> {
          Path p = paths.resolve(check.path().orElse(""));
          yield Files.isRegularFile(p)
              ? java.util.Optional.empty()
              : java.util.Optional.of(label + ": " + p + " does not exist");
        }
        case FILE_ABSENT -> {
          Path p = paths.resolve(check.path().orElse(""));
          yield Files.exists(p)
              ? java.util.Optional.of(label + ": " + p + " exists")
              : java.util.Optional.empty();
        }
        case SHA256 -> {
          Path p = paths.resolve(check.path().orElse(""));
          if (!Files.isRegularFile(p)) {
            yield java.util.Optional.of(label + ": " + p + " does not exist");
          }
          String actual = files.sha256(p);
          yield actual.equals(check.sha256().orElse(""))
              ? java.util.Optional.empty()
              : java.util.Optional.of(
                  label + ": " + p + " hashes to " + actual + ", expected " + check.sha256().get());
        }
        case HTTP -> {
          int expected = check.expect().orElse(200);
          int status = http.status(check.url().orElse("/"));
          yield status == expected
              ? java.util.Optional.empty()
              : java.util.Optional.of(label + ": got " + status);
        }
      };
    } catch (IOException | RuntimeException e) {
      return java.util.Optional.of(label + ": " + e.getMessage());
    }
  }
}
