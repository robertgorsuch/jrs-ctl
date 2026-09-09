package com.jaspersoft.jrsctl.ops;

import com.jaspersoft.jrsctl.core.platform.FileOps;
import com.jaspersoft.jrsctl.core.platform.OperatorPrompt;
import com.jaspersoft.jrsctl.core.platform.Platform;
import com.jaspersoft.jrsctl.core.platform.Platforms;
import com.jaspersoft.jrsctl.core.platform.ProcessRunner;
import com.jaspersoft.jrsctl.core.platform.ServiceConfig;
import com.jaspersoft.jrsctl.core.platform.ServiceController;
import com.jaspersoft.jrsctl.core.platform.TomcatLayout;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Minimal {@link Platform} for ops tests: a scriptable process runner, file ops backed by the real
 * file system with configurable free space and owner-only answers, a service controller with a
 * fixed state, and Tomcat layout detection delegated to the real platform code so fake layouts on
 * disk are detected exactly as in production.
 */
public final class FakePlatform implements Platform {

  /** A scripted process answer. */
  public record Response(int exitCode, List<String> stdout) {
    public static Response ok(String... lines) {
      return new Response(0, List.of(lines));
    }
  }

  public final Map<String, Response> scripted = new HashMap<>();
  public final List<List<String>> invocations = new ArrayList<>();
  public final List<Path> candidates = new ArrayList<>();
  public OsFamily os;
  public Path home;
  public long freeSpace = 100L << 30;
  public boolean ownerOnly = true;
  public boolean writable = true;
  public ServiceController.State serviceState = ServiceController.State.RUNNING;

  private final Platform detector;

  public FakePlatform(OsFamily os, Path home) {
    this.os = os;
    this.home = home;
    this.detector =
        Platforms.forTesting(
            os, Arch.X86_64, processes(), files(), OperatorPrompt.nonInteractive());
  }

  /** Scripts the answer for a command given as its first {@code prefix.length} words. */
  public FakePlatform on(List<String> command, Response response) {
    scripted.put(String.join(" ", command), response);
    return this;
  }

  @Override
  public OsFamily os() {
    return os;
  }

  @Override
  public Arch arch() {
    return Arch.X86_64;
  }

  @Override
  public ServiceController services(ServiceConfig cfg) {
    return new ServiceController() {
      @Override
      public State state() {
        return serviceState;
      }

      @Override
      public State stop(Duration timeout) {
        return State.STOPPED;
      }

      @Override
      public State start(Duration timeout) {
        return State.RUNNING;
      }

      @Override
      public String describe() {
        return cfg.kind()
            + " "
            + cfg.name().or(() -> cfg.scriptPath().map(Path::toString)).orElse("");
      }
    };
  }

  @Override
  public FileOps files() {
    return new FileOps() {
      @Override
      public String sha256(Path file) {
        throw new UnsupportedOperationException("fake platform");
      }

      @Override
      public void atomicReplace(Path source, Path target) {
        throw new UnsupportedOperationException("fake platform");
      }

      @Override
      public void copyPreserving(Path source, Path target) {
        throw new UnsupportedOperationException("fake platform");
      }

      @Override
      public boolean isLocked(Path file) {
        return false;
      }

      @Override
      public Optional<String> lockHolder(Path file) {
        return Optional.empty();
      }

      @Override
      public Permissions capturePermissions(Path path) {
        return new Permissions("fake", List.of());
      }

      @Override
      public void applyPermissions(Path path, Permissions permissions) {}

      @Override
      public long freeSpaceBytes(Path anyPathOnVolume) throws IOException {
        if (!Files.exists(anyPathOnVolume)) {
          throw new IOException("no such path " + anyPathOnVolume);
        }
        return freeSpace;
      }

      @Override
      public boolean isWritable(Path dir) {
        return writable && Files.isDirectory(dir);
      }

      @Override
      public boolean isOwnerOnly(Path file) {
        return ownerOnly;
      }
    };
  }

  @Override
  public ProcessRunner processes() {
    return new ProcessRunner() {
      @Override
      public Result run(Request request, Consumer<OutputLine> onLine) {
        invocations.add(List.copyOf(request.command()));
        Response response = null;
        String joined = String.join(" ", request.command());
        for (Map.Entry<String, Response> entry : scripted.entrySet()) {
          if (joined.startsWith(entry.getKey())) {
            response = entry.getValue();
            break;
          }
        }
        if (response == null) {
          return new Result(1, false, Duration.ZERO);
        }
        for (String line : response.stdout()) {
          onLine.accept(new OutputLine(OutputLine.Stream.STDOUT, line));
        }
        return new Result(response.exitCode(), false, Duration.ofMillis(1));
      }
    };
  }

  @Override
  public Path defaultHome() {
    return home;
  }

  @Override
  public Optional<TomcatLayout> detectTomcat(Path installDir) {
    return detector.detectTomcat(installDir);
  }

  @Override
  public List<Path> candidateInstallDirs() {
    return List.copyOf(candidates);
  }
}
