package com.jaspersoft.jrsctl.core.platform;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * {@link Platform} for Linux. Invariants: the default home is {@code /var/lib/jrsctl} when that
 * tree can be written (root or a dedicated service account), otherwise {@code ~/.jrsctl}; install
 * candidates are the install dirs of running Tomcats (from {@code /proc/<pid>/cwd} and {@code
 * catalina.home}) followed by the well-known locations under {@code /opt}, {@code /usr/local} and
 * every home directory.
 */
public final class LinuxPlatform extends AbstractPlatform {

  public LinuxPlatform(Arch arch, ProcessRunner runner, FileOps files, OperatorPrompt prompt) {
    this(arch, runner, files, prompt, Optional.empty(), TomcatProcesses.INSTANCE);
  }

  private LinuxPlatform(
      Arch arch,
      ProcessRunner runner,
      FileOps files,
      OperatorPrompt prompt,
      Optional<Path> installDir,
      TomcatProcessFinder tomcats) {
    super(arch, runner, files, prompt, installDir, tomcats);
  }

  @Override
  public Platform withInstallDir(Path installDir) {
    return new LinuxPlatform(
        arch(), processes(), files(), prompt(), Optional.of(installDir), tomcats());
  }

  @Override
  public OsFamily os() {
    return OsFamily.LINUX;
  }

  @Override
  public Path defaultHome() {
    return homeOrFallback(Path.of("/var/lib"));
  }

  @Override
  public List<Path> candidateInstallDirs() {
    List<Path> candidates = new ArrayList<>(installDirsFromProcesses());
    candidates.addAll(glob(Path.of("/opt"), "jasperreports-server*"));
    candidates.addAll(glob(Path.of("/opt/jaspersoft"), "*"));
    candidates.addAll(glob(Path.of("/usr/local"), "jasperreports-server*"));
    for (Path home : glob(Path.of("/home"), "*")) {
      candidates.addAll(glob(home, "jasperreports-server*"));
    }
    return existingUnique(candidates);
  }
}
