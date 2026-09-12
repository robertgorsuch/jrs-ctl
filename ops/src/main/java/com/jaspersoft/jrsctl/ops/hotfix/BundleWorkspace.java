package com.jaspersoft.jrsctl.ops.hotfix;

import com.jaspersoft.jrsctl.core.JrsctlHome;
import com.jaspersoft.jrsctl.core.platform.Trees;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Function;

/**
 * A bundle unpacked somewhere it can be read, for as long as one caller needs it. Invariants: the
 * directory is created under {@code $JRSCTL_HOME/runs}, never in the system temp directory, so an
 * unpacked bundle stays inside the tool's own tree; it is removed whether the body returns or
 * throws, and a removal that fails is left for the next run rather than raised, because a leftover
 * verify directory is harmless; a bundle that is missing or is not a jrsctl ZIP is refused with the
 * operator-facing reason rather than an IO exception.
 */
final class BundleWorkspace {

  private final JrsctlHome home;

  BundleWorkspace(JrsctlHome home) {
    this.home = home;
  }

  /** Unpacks {@code bundle}, hands it to {@code body}, and removes the copy afterwards. */
  <T> T with(Path bundle, Function<HotfixBundle, T> body) {
    if (!Files.isRegularFile(bundle)) {
      throw new HotfixException(
          HotfixException.PRECHECK, "bundle not found: " + bundle, "check the path");
    }
    Path dir;
    try {
      Files.createDirectories(home.runs());
      dir = Files.createTempDirectory(home.runs(), "hotfix-verify-");
    } catch (IOException e) {
      throw new HotfixException(
          HotfixException.PRECHECK,
          "cannot create a working directory under " + home.runs() + ": " + e.getMessage(),
          "check permissions on " + home.runs());
    }
    try {
      HotfixBundle unpacked;
      try {
        unpacked = HotfixBundle.extract(bundle, dir);
      } catch (IOException e) {
        throw new HotfixException(
            HotfixException.SIGNATURE,
            "cannot read bundle " + bundle + ": " + e.getMessage(),
            "check that the file is a jrsctl hotfix ZIP",
            e);
      }
      return body.apply(unpacked);
    } finally {
      try {
        Trees.deleteRecursively(dir);
      } catch (IOException e) {
        // a leftover verify directory is harmless; the next run cleans up
      }
    }
  }
}
