package com.jaspersoft.jrsctl.core.platform;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * OS abstraction (spec §5.3). All process, service and file-system side effects go through this
 * interface so ops code is platform-neutral and tests can substitute fakes. Invariants: no shell is
 * ever invoked, arguments are passed as lists; file operations preserve ownership and ACLs; all I/O
 * streams and never loads a whole file into memory.
 */
public interface Platform {

  OsFamily os();

  Arch arch();

  ServiceController services(ServiceConfig cfg);

  FileOps files();

  ProcessRunner processes();

  /**
   * Default {@code $JRSCTL_HOME}: the system location (ProgramData on Windows, /var/lib on Linux)
   * when it exists or can be created, else the per-user {@code ~/.jrsctl}. Callers resolve the home
   * through {@code JrsctlHomeResolver}, which refuses (exit 2) when the system home exists but this
   * user cannot write to it, rather than quietly starting a second journal beside someone else's;
   * this method alone does not make that check.
   */
  Path defaultHome();

  /**
   * Inspects an install dir for the Tomcat layout; empty when it does not look like a JRS install.
   */
  Optional<TomcatLayout> detectTomcat(Path installDir);

  /** Places {@code init} should look for an installation, most likely first. */
  List<Path> candidateInstallDirs();

  enum OsFamily {
    WINDOWS,
    LINUX
  }

  enum Arch {
    X86_64,
    OTHER
  }
}
