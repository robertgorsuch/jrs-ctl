package com.jaspersoft.jrsctl.core.platform;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Derives a {@link ServiceController.State} from running Tomcat processes. Invariant: only {@code
 * RUNNING} and {@code STOPPED} are ever produced, because a process listing cannot distinguish a
 * starting JVM from a serving one; when an install directory is given only processes belonging to
 * it count, otherwise any Tomcat does.
 */
final class TomcatState {

  private TomcatState() {}

  static ServiceController.State of(TomcatProcessFinder finder, Optional<Path> installDir) {
    boolean running =
        finder.find().stream().anyMatch(p -> installDir.map(p::belongsTo).orElse(true));
    return running ? ServiceController.State.RUNNING : ServiceController.State.STOPPED;
  }
}
