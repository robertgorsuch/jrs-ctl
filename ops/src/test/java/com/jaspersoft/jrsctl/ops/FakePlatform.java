package com.jaspersoft.jrsctl.ops;

import com.jaspersoft.jrsctl.core.platform.FileOps;
import com.jaspersoft.jrsctl.core.platform.LinuxFileOps;
import com.jaspersoft.jrsctl.core.platform.OperatorPrompt;
import com.jaspersoft.jrsctl.core.platform.Platform;
import com.jaspersoft.jrsctl.core.platform.Platforms;
import com.jaspersoft.jrsctl.core.platform.ProcessRunner;
import com.jaspersoft.jrsctl.core.platform.ServiceConfig;
import com.jaspersoft.jrsctl.core.platform.ServiceController;
import com.jaspersoft.jrsctl.core.platform.TomcatLayout;
import com.jaspersoft.jrsctl.core.platform.WindowsFileOps;
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
 * file system with configurable free space and owner-only answers (and, when {@link #realFiles} is
 * set, real hashing, atomic replace and lock detection for the host OS), a recording service
 * controller, and Tomcat layout detection delegated to the real platform code so fake layouts on
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
  public boolean realFiles;
  public ServiceController.State serviceState = ServiceController.State.RUNNING;
  public final FakeServiceController controller = new FakeServiceController(this, "fake service");

  private final Platform detector;
  private final FileOps hostFiles;

  public FakePlatform(OsFamily os, Path home) {
    this.os = os;
    this.home = home;
    this.hostFiles =
        Platforms.osFamily(System.getProperty("os.name", "")) == OsFamily.WINDOWS
            ? new WindowsFileOps()
            : new LinuxFileOps();
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
    return controller;
  }

  @Override
  public FileOps files() {
    return new FileOps() {
      @Override
      public String sha256(Path file) throws IOException {
        if (realFiles) {
          return hostFiles.sha256(file);
        }
        throw new UnsupportedOperationException("fake platform");
      }

      @Override
      public void atomicReplace(Path source, Path target) throws IOException {
        if (realFiles) {
          hostFiles.atomicReplace(source, target);
          return;
        }
        throw new UnsupportedOperationException("fake platform");
      }

      @Override
      public void copyPreserving(Path source, Path target) throws IOException {
        if (realFiles) {
          hostFiles.copyPreserving(source, target);
          return;
        }
        throw new UnsupportedOperationException("fake platform");
      }

      @Override
      public boolean isLocked(Path file) {
        return realFiles && hostFiles.isLocked(file);
      }

      @Override
      public Optional<String> lockHolder(Path file) {
        return realFiles ? hostFiles.lockHolder(file) : Optional.empty();
      }

      @Override
      public Permissions capturePermissions(Path path) throws IOException {
        return realFiles ? hostFiles.capturePermissions(path) : new Permissions("fake", List.of());
      }

      @Override
      public void applyPermissions(Path path, Permissions permissions) throws IOException {
        if (realFiles) {
          hostFiles.applyPermissions(path, permissions);
        }
      }

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
