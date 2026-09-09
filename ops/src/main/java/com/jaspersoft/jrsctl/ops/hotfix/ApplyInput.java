package com.jaspersoft.jrsctl.ops.hotfix;

import com.jaspersoft.jrsctl.core.config.Config;
import com.jaspersoft.jrsctl.core.engine.Context;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable inputs of one apply plan: the bundle, its manifest, the resolved targets and the
 * options. Invariants: {@code sqlScripts} holds exactly the manifest's SQL entries for the
 * configured database type (empty when no database is configured); the bundle is unpacked under the
 * run directory by the first verify step and read from there by every later step.
 */
record ApplyInput(
    Path bundle,
    String bundleSha256,
    Manifest manifest,
    HotfixPaths paths,
    List<FileTarget> targets,
    boolean allowUnsigned,
    Optional<Config.DatabaseType> dbType,
    List<Manifest.SqlEntry> sqlScripts) {

  static final String BUNDLE_DIR = "bundle";

  ApplyInput {
    Objects.requireNonNull(bundle, "bundle");
    Objects.requireNonNull(bundleSha256, "bundleSha256");
    Objects.requireNonNull(manifest, "manifest");
    Objects.requireNonNull(paths, "paths");
    targets = List.copyOf(targets);
    Objects.requireNonNull(dbType, "dbType");
    sqlScripts = List.copyOf(sqlScripts);
  }

  boolean restartRequired() {
    return manifest.restart() == Manifest.Restart.REQUIRED;
  }

  boolean hasSql() {
    return !sqlScripts.isEmpty();
  }

  Path bundleDir(Context ctx) {
    return ctx.home().runDir(ctx.runId()).resolve(BUNDLE_DIR);
  }

  Path stagingDir(Context ctx) {
    return ctx.home().stagingDir(ctx.runId());
  }

  Path staged(Context ctx, FileTarget target) {
    return stagingDir(ctx).resolve(target.manifestPath().replace('\\', '/'));
  }

  Path payload(Context ctx, FileTarget target) {
    return bundleDir(ctx).resolve(target.entry().bundlePath());
  }

  /** Files that exist now and will be replaced or deleted. */
  List<Path> snapshotPaths() {
    return targets.stream().flatMap(t -> t.snapshotPaths().stream()).toList();
  }

  /** Every path the plan touches. */
  List<Path> touched() {
    return targets.stream().flatMap(t -> t.touched().stream()).toList();
  }
}
