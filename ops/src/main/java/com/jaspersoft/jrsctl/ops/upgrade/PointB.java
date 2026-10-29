package com.jaspersoft.jrsctl.ops.upgrade;

import com.jaspersoft.jrsctl.core.engine.CancellationToken;
import com.jaspersoft.jrsctl.core.platform.Platform;
import com.jaspersoft.jrsctl.core.platform.Trees;
import com.jaspersoft.jrsctl.core.snapshot.Snapshot;
import com.jaspersoft.jrsctl.core.snapshot.SnapshotStore;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.CopyOption;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileSystemException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFileAttributes;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Restores rollback point B (spec §10.1): the webapp and buildomatic directories from their
 * archives, and the configuration and keystore files from their {@code SnapshotStore} entries. Used
 * both by the compensation of {@code run-vendor-upgrade} and by the rollback plan. Invariants: a
 * directory being replaced is first moved aside (never deleted) so the restore can be undone; every
 * operation converges on re-execution (an aside directory that already exists means an earlier
 * attempt got that far); archives are verified against their recorded SHA-256 before anything is
 * touched.
 */
final class PointB {

  private static final Logger LOG = LoggerFactory.getLogger(PointB.class);
  private static final int MOVE_RETRY_ATTEMPTS = 20;
  private static final long MOVE_RETRY_DELAY_MS = 50;

  static final String ASIDE_DIR = "aside";

  private PointB() {}

  /** Where a rollback run keeps the directory it replaced, so its compensation can put it back. */
  static Path asideFor(Path runDir, Path target) {
    return runDir.resolve(ASIDE_DIR).resolve(target.getFileName().toString());
  }

  /** Fails when the archive's current hash differs from the one recorded next to it. */
  static void verifyArchive(Path archive, String actualSha256) throws IOException {
    Optional<String> recorded = readSha(archive);
    if (recorded.isEmpty()) {
      throw new IOException("no " + SnapshotSet.SHA_SUFFIX + " record next to " + archive);
    }
    if (!recorded.get().equals(actualSha256)) {
      throw new IOException(
          archive + " hashes to " + actualSha256 + " but " + recorded.get() + " was recorded");
    }
  }

  static Optional<String> readSha(Path archive) throws IOException {
    Path shaFile = SnapshotSet.shaFileFor(archive);
    if (!Files.isRegularFile(shaFile)) {
      return Optional.empty();
    }
    String text = Files.readString(shaFile, StandardCharsets.UTF_8).strip();
    return text.isEmpty() ? Optional.empty() : Optional.of(text);
  }

  static void writeSha(Path archive, String sha256) throws IOException {
    Files.writeString(SnapshotSet.shaFileFor(archive), sha256 + "\n", StandardCharsets.UTF_8);
  }

  /** Pre-1.4.0 staging name, removed when a crash left one behind (issue #48). */
  private static final String LEGACY_STAGING_SUFFIX = ".jrsctl-restore";

  private static final String PARTIAL_SUFFIX = ".part";

  /**
   * Where {@link #restoreDir} extracts {@code target}'s archive: beside the target's parent when
   * that parent is Tomcat's {@code webapps} (a directory there is deployable), else beside the
   * target. Either way it is on the target's file store, so moving it into place stays a rename.
   */
  static Path stagingFor(Path target) {
    Path absolute = target.toAbsolutePath().normalize();
    Path parent = absolute.getParent();
    Path home =
        parent.getFileName() != null
                && parent.getFileName().toString().equalsIgnoreCase("webapps")
                && parent.getParent() != null
            ? parent.getParent()
            : parent;
    return home.resolve(".jrsctl-restore-" + absolute.getFileName());
  }

  /**
   * Replaces {@code target} with the contents of {@code archive}: extracts to {@link #stagingFor},
   * moves the current target to {@code aside} (unless a previous attempt already did) and renames
   * the extracted tree into place.
   */
  static long restoreDir(
      Platform.OsFamily os, Path archive, Path target, Path aside, CancellationToken cancel)
      throws IOException {
    Path absolute = target.toAbsolutePath().normalize();
    Trees.deleteRecursively(
        absolute.getParent().resolve("." + absolute.getFileName() + LEGACY_STAGING_SUFFIX));
    Path staging = stagingFor(absolute);
    Trees.deleteRecursively(staging);
    long entries = Archives.extract(os, archive, staging, cancel);
    if (Files.exists(target)) {
      if (Files.exists(aside)) {
        Trees.deleteRecursively(target);
      } else {
        Files.createDirectories(aside.getParent());
        moveTree(target, aside);
      }
    }
    moveTree(staging, target);
    return entries;
  }

  /** Undoes {@link #restoreDir}: removes the restored tree and puts the aside copy back. */
  static boolean undoRestore(Path target, Path aside) throws IOException {
    if (!Files.exists(aside)) {
      return false;
    }
    Trees.deleteRecursively(target);
    moveTree(aside, target);
    return true;
  }

  static void restoreSnapshot(SnapshotStore store, Snapshot snapshot) throws IOException {
    store.restore(snapshot);
  }

  /**
   * Renames a tree; falls back to a streaming copy plus delete across file stores, which keeps
   * links and POSIX attributes ({@link #copyTree}). The copy goes to a {@code .part} sibling that
   * is renamed into place only once complete, so a copy that fails half-way leaves nothing at
   * {@code target}: an {@code aside} tree that exists is always whole.
   */
  static void moveTree(Path source, Path target) throws IOException {
    try {
      moveRetrying(source, target, StandardCopyOption.ATOMIC_MOVE);
      return;
    } catch (AtomicMoveNotSupportedException | DirectoryNotEmptyException e) {
      // cross-volume: copy then delete
    }
    try {
      moveRetrying(source, target);
      return;
    } catch (DirectoryNotEmptyException | UnsupportedOperationException e) {
      // cross-volume: copy then delete
    }
    // a provider that refuses a move across stores may have created the target directory first;
    // an empty one is that leftover (a non-empty one makes the delete, and so the move, fail)
    if (Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
      Files.delete(target);
    }
    Path partial = target.resolveSibling(target.getFileName() + PARTIAL_SUFFIX);
    Trees.deleteRecursively(partial);
    copyTree(source, partial);
    moveRetrying(partial, target);
    Trees.deleteRecursively(source);
  }

  /**
   * Renames {@code source} to {@code target}, retrying a rename that fails with a transient {@link
   * IOException} (typically Windows denying access to a directory for a moment after it was last
   * written). {@link AtomicMoveNotSupportedException} and {@link DirectoryNotEmptyException} are
   * structural, not transient, and are rethrown on their first occurrence so the caller can fall
   * back to a cross-volume copy.
   */
  private static void moveRetrying(Path source, Path target, CopyOption... options)
      throws IOException {
    for (int attempt = 1; ; attempt++) {
      try {
        Files.move(source, target, options);
        return;
      } catch (AtomicMoveNotSupportedException | DirectoryNotEmptyException structural) {
        throw structural;
      } catch (IOException e) {
        if (attempt >= MOVE_RETRY_ATTEMPTS || !sleepBeforeRetry()) {
          throw e;
        }
        LOG.debug(
            "rename {} -> {} failed (attempt {}); retrying: {}",
            source,
            target,
            attempt,
            e.toString());
      }
    }
  }

  /** Returns false (having restored the interrupt flag) if the wait was interrupted. */
  private static boolean sleepBeforeRetry() {
    try {
      Thread.sleep(MOVE_RETRY_DELAY_MS);
      return true;
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  /**
   * Copies a tree as it is: a symbolic link becomes a link with the same target (never a copy of
   * what it points at), and files and directories keep their POSIX permissions, owner and group
   * where both file systems have them. An owner or group the process may not set (anyone but root)
   * is left as the process's own.
   */
  static void copyTree(Path source, Path target) throws IOException {
    Path from = source.toAbsolutePath().normalize();
    Path to = target.toAbsolutePath().normalize();
    // links are made once the walk is done: on Windows a link to a directory is created as a
    // directory link only when its target already exists; directory attributes come last, deepest
    // first, so a directory without write permission can still receive its files and links
    Map<Path, Path> links = new LinkedHashMap<>();
    Map<Path, Path> dirs = new LinkedHashMap<>();
    Files.walkFileTree(
        from,
        new SimpleFileVisitor<>() {
          @Override
          public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
              throws IOException {
            Files.createDirectories(to.resolve(from.relativize(dir).toString()));
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
              throws IOException {
            Path copy = to.resolve(from.relativize(file).toString());
            if (attrs.isSymbolicLink()) {
              links.put(copy, Files.readSymbolicLink(file));
              return FileVisitResult.CONTINUE;
            }
            byte[] buffer = new byte[64 * 1024];
            try (InputStream in = Files.newInputStream(file);
                OutputStream out = Files.newOutputStream(copy)) {
              int read;
              while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
              }
            }
            copyPosixAttributes(file, copy);
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult postVisitDirectory(Path dir, IOException failure)
              throws IOException {
            if (failure != null) {
              throw failure;
            }
            dirs.put(dir, to.resolve(from.relativize(dir).toString()));
            return FileVisitResult.CONTINUE;
          }
        });
    for (Map.Entry<Path, Path> link : links.entrySet()) {
      Files.createSymbolicLink(link.getKey(), link.getValue());
    }
    for (Map.Entry<Path, Path> dir : dirs.entrySet()) {
      copyPosixAttributes(dir.getKey(), dir.getValue());
    }
  }

  private static void copyPosixAttributes(Path from, Path to) throws IOException {
    PosixFileAttributeView source =
        Files.getFileAttributeView(from, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
    PosixFileAttributeView target =
        Files.getFileAttributeView(to, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
    if (source == null || target == null) {
      return;
    }
    PosixFileAttributes attrs = source.readAttributes();
    try {
      target.setOwner(attrs.owner());
    } catch (FileSystemException e) {
      LOG.debug("cannot keep the owner of {}: {}", from, e.getMessage());
    }
    try {
      // a user who is not root may still hand a file to a group it belongs to
      target.setGroup(attrs.group());
    } catch (FileSystemException e) {
      LOG.debug("cannot keep the group of {}: {}", from, e.getMessage());
    }
    target.setPermissions(attrs.permissions());
  }
}
