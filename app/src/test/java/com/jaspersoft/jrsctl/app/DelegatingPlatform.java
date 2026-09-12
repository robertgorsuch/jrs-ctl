package com.jaspersoft.jrsctl.app;

import com.jaspersoft.jrsctl.core.platform.FileOps;
import com.jaspersoft.jrsctl.core.platform.Platform;
import com.jaspersoft.jrsctl.core.platform.ProcessRunner;
import com.jaspersoft.jrsctl.core.platform.ServiceConfig;
import com.jaspersoft.jrsctl.core.platform.ServiceController;
import com.jaspersoft.jrsctl.core.platform.TomcatLayout;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** A {@link Platform} that forwards everything, so a test can override one method. */
abstract class DelegatingPlatform implements Platform {

  private final Platform delegate;

  DelegatingPlatform(Platform delegate) {
    this.delegate = Objects.requireNonNull(delegate, "delegate");
  }

  @Override
  public OsFamily os() {
    return delegate.os();
  }

  @Override
  public Arch arch() {
    return delegate.arch();
  }

  @Override
  public ServiceController services(ServiceConfig cfg) {
    return delegate.services(cfg);
  }

  @Override
  public FileOps files() {
    return delegate.files();
  }

  @Override
  public ProcessRunner processes() {
    return delegate.processes();
  }

  @Override
  public Path defaultHome() {
    return delegate.defaultHome();
  }

  @Override
  public Optional<TomcatLayout> detectTomcat(Path installDir) {
    return delegate.detectTomcat(installDir);
  }

  @Override
  public List<Path> candidateInstallDirs() {
    return delegate.candidateInstallDirs();
  }
}
