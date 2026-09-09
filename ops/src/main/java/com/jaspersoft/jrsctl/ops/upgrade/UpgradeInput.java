package com.jaspersoft.jrsctl.ops.upgrade;

import com.jaspersoft.jrsctl.core.engine.Context;
import com.jaspersoft.jrsctl.jrs.api.ServerIdentity;
import com.jaspersoft.jrsctl.ops.hotfix.HotfixPaths;
import com.jaspersoft.jrsctl.ops.upgrade.UpgradeOperations.UpgradeOptions;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Everything the upgrade steps need that was resolved at planning time: the operator's options, the
 * installation layout, the inspected target package and the server identity as seen when the plan
 * was built (empty when the server was unreachable). Invariants: paths are absolute and normalised;
 * {@code masterOverrides} never contains a key whose name contains "pass" (spec §7.4 copies the
 * database settings minus passwords); the record is immutable.
 */
record UpgradeInput(
    UpgradeOptions options,
    HotfixPaths paths,
    String webappName,
    TargetPackage target,
    Optional<ServerIdentity> identity,
    Map<String, String> masterOverrides) {

  static final String BUILDOMATIC = "buildomatic";

  UpgradeInput {
    Objects.requireNonNull(options, "options");
    Objects.requireNonNull(paths, "paths");
    Objects.requireNonNull(webappName, "webappName");
    Objects.requireNonNull(target, "target");
    Objects.requireNonNull(identity, "identity");
    masterOverrides = Map.copyOf(masterOverrides);
  }

  Path installDir() {
    return paths.installDir();
  }

  Path tomcatDir() {
    return paths.tomcatDir();
  }

  Path webappDir() {
    return tomcatDir().resolve("webapps").resolve(webappName);
  }

  Path installedBuildomatic() {
    return installDir().resolve(BUILDOMATIC);
  }

  Optional<Path> targetBuildomatic() {
    return target.buildomatic().map(b -> b.dir());
  }

  Optional<String> currentVersion() {
    return identity.map(ServerIdentity::version);
  }

  SnapshotSet snapshots(Context ctx) {
    return SnapshotSet.of(ctx.home(), ctx.runId(), ctx.platform().os());
  }

  /** Configuration files that exist right now and belong in the point-B backup. */
  List<Path> configFiles() {
    List<Path> files = new ArrayList<>();
    add(files, installedBuildomatic().resolve("default_master.properties"));
    Path localhost = tomcatDir().resolve("conf").resolve("Catalina").resolve("localhost");
    if (Files.isDirectory(localhost)) {
      try (DirectoryStream<Path> xmls = Files.newDirectoryStream(localhost, "*.xml")) {
        List<Path> sorted = new ArrayList<>();
        for (Path xml : xmls) {
          if (Files.isRegularFile(xml)) {
            sorted.add(xml);
          }
        }
        sorted.sort(null);
        files.addAll(sorted);
      } catch (IOException e) {
        // an unreadable context directory is reported by the backup step itself
      }
    }
    add(files, webappDir().resolve("META-INF").resolve("context.xml"));
    add(files, webappDir().resolve("WEB-INF").resolve("js.jdbc.properties"));
    return List.copyOf(files);
  }

  private static void add(List<Path> files, Path file) {
    if (Files.isRegularFile(file)) {
      files.add(file.toAbsolutePath().normalize());
    }
  }
}
