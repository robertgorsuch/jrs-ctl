package com.jaspersoft.jrsctl.core.platform;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Finds running Tomcat JVMs on this machine. Invariant: every returned process was alive at scan
 * time and either has {@code catalina} in its command line or is a Tomcat service wrapper; tests
 * substitute a fake so controller state transitions can be simulated without a real server.
 */
interface TomcatProcessFinder {

  List<TomcatProcess> find();

  /** A Tomcat JVM with the install locations that could be read from its command line. */
  record TomcatProcess(
      long pid,
      String commandLine,
      Optional<Path> catalinaHome,
      Optional<Path> catalinaBase,
      Optional<Path> workingDir) {

    /**
     * True when the process runs the Tomcat under {@code dir}: its command line mentions the
     * directory, or its catalina.home/base lies beneath it. Comparison is case-insensitive because
     * Windows paths are.
     */
    boolean belongsTo(Path dir) {
      Path wanted = dir.toAbsolutePath().normalize();
      if (catalinaHome.map(h -> h.startsWith(wanted)).orElse(false)
          || catalinaBase.map(b -> b.startsWith(wanted)).orElse(false)) {
        return true;
      }
      String needle = wanted.toString().toLowerCase(Locale.ROOT);
      return commandLine.toLowerCase(Locale.ROOT).contains(needle);
    }
  }
}
