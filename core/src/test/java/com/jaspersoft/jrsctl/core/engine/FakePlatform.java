package com.jaspersoft.jrsctl.core.engine;

import com.jaspersoft.jrsctl.core.platform.FileOps;
import com.jaspersoft.jrsctl.core.platform.Platform;
import com.jaspersoft.jrsctl.core.platform.ProcessRunner;
import com.jaspersoft.jrsctl.core.platform.ServiceConfig;
import com.jaspersoft.jrsctl.core.platform.ServiceController;
import com.jaspersoft.jrsctl.core.platform.TomcatLayout;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/** Minimal {@link Platform} for engine tests: no real files, processes or services are touched. */
public final class FakePlatform implements Platform {

  private final Path home;

  public FakePlatform(Path home) {
    this.home = home;
  }

  @Override
  public OsFamily os() {
    return OsFamily.LINUX;
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
        return State.STOPPED;
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
        return "fake service";
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
      public long freeSpaceBytes(Path anyPathOnVolume) {
        return Long.MAX_VALUE;
      }

      @Override
      public boolean isWritable(Path dir) {
        return true;
      }

      @Override
      public boolean isOwnerOnly(Path file) {
        return true;
      }
    };
  }

  @Override
  public ProcessRunner processes() {
    return new ProcessRunner() {
      @Override
      public Result run(Request request, Consumer<OutputLine> onLine) {
        throw new UnsupportedOperationException("fake platform runs no processes");
      }
    };
  }

  @Override
  public Path defaultHome() {
    return home;
  }

  @Override
  public Optional<TomcatLayout> detectTomcat(Path installDir) {
    return Optional.empty();
  }

  @Override
  public List<Path> candidateInstallDirs() {
    return List.of();
  }
}
