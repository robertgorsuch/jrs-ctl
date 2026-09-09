package com.jaspersoft.jrsctl.ops.hotfix;

import com.jaspersoft.jrsctl.core.platform.FileOps;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * One manifest file entry resolved onto the server: the absolute target, its hash before the hotfix
 * (empty when absent) and the {@code replaces} siblings with their hashes. Invariants: hashes are
 * captured at plan time and become fingerprint inputs, so a target that changes between plan and
 * run is refused by the Runner; siblings live in the target's directory by construction.
 */
record FileTarget(
    Manifest.FileEntry entry, Path target, Optional<String> before, List<Sibling> replaces) {

  /** A file removed after the new one lands. */
  record Sibling(Path path, Optional<String> before) {}

  FileTarget {
    replaces = List.copyOf(replaces);
  }

  Manifest.Action action() {
    return entry.action();
  }

  String manifestPath() {
    return entry.path();
  }

  /** Hash the target must have after the swap; empty for deletes. */
  Optional<String> after() {
    return action() == Manifest.Action.DELETE ? Optional.empty() : entry.sha256();
  }

  boolean existedBefore() {
    return before.isPresent();
  }

  /** Files that exist now and will be replaced or deleted, so the snapshot must hold them. */
  List<Path> snapshotPaths() {
    List<Path> out = new ArrayList<>();
    if (existedBefore() && action() != Manifest.Action.ADD) {
      out.add(target);
    }
    for (Sibling s : replaces) {
      if (s.before().isPresent()) {
        out.add(s.path());
      }
    }
    return out;
  }

  /** Every path this entry touches. */
  List<Path> touched() {
    List<Path> out = new ArrayList<>();
    out.add(target);
    for (Sibling s : replaces) {
      out.add(s.path());
    }
    return out;
  }

  static List<FileTarget> resolve(Manifest manifest, HotfixPaths paths, FileOps files) {
    List<FileTarget> out = new ArrayList<>();
    for (Manifest.FileEntry entry : manifest.files()) {
      Path target = paths.resolve(entry.path());
      List<Sibling> siblings = new ArrayList<>();
      for (String name : entry.replaces()) {
        Path sibling = target.resolveSibling(name);
        siblings.add(new Sibling(sibling, hashOf(files, sibling)));
      }
      out.add(new FileTarget(entry, target, hashOf(files, target), siblings));
    }
    return List.copyOf(out);
  }

  /** Hash of an existing regular file, empty when it is absent. */
  static Optional<String> hashOf(FileOps files, Path path) {
    if (!Files.isRegularFile(path)) {
      return Optional.empty();
    }
    try {
      return Optional.of(files.sha256(path));
    } catch (IOException e) {
      throw new UncheckedIOException("cannot hash " + path, e);
    }
  }
}
