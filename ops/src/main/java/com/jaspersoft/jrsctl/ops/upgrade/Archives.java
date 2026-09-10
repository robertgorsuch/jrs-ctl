package com.jaspersoft.jrsctl.ops.upgrade;

import com.jaspersoft.jrsctl.core.engine.CancellationToken;
import com.jaspersoft.jrsctl.core.platform.Platform;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipalLookupService;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import org.apache.commons.compress.archivers.ArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.archivers.tar.TarConstants;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Directory archives for the upgrade backups (spec §10.2 step 6): tar.gz on Linux, where POSIX
 * permissions, owner and group are stored in the tar header and re-applied on extraction, zip on
 * Windows, where ACLs are not archived (the webapp is restored under the same parent directory and
 * inherits its ACL). Invariants: everything streams through fixed-size buffers, never whole-file
 * byte arrays; entry names are relative, forward-slash separated and rejected on extraction if they
 * would escape the target directory; the cancellation token is checked once per entry.
 *
 * <p>An archive is only worth having if what comes back out is what went in, so nothing in the tree
 * may be silently dropped. A symbolic link becomes a link entry on Linux and is recreated as a
 * link, never as a copy of what it pointed at; on Windows, where the zip format has no portable way
 * to say "link", {@link #create} fails and names the link rather than writing an archive that
 * quietly loses it. Links are created only after every file and directory is in place, so an entry
 * can never be written through a link this extraction made. Owner, group and permissions are
 * re-applied last, deepest path first, because a directory restored to mode 0555 first would leave
 * nowhere to put its children; a process that lacks the rights to change owner says so once and
 * carries on, since a restored tree owned by the wrong user is still better than no restore.
 */
final class Archives {

  private static final Logger LOG = LoggerFactory.getLogger(Archives.class);
  private static final int BUFFER = 64 * 1024;

  private Archives() {}

  /** One archived entry's POSIX metadata, re-applied after the whole tree is written. */
  private record Ownership(Path target, int mode, String user, String group, int uid, int gid) {}

  /** File-name suffix of the archive kind used on the given OS. */
  static String extension(Platform.OsFamily os) {
    return switch (os) {
      case LINUX -> ".tar.gz";
      case WINDOWS -> ".zip";
    };
  }

  /** Archives {@code sourceDir} (its contents, not the directory itself) into {@code archive}. */
  static long create(Platform.OsFamily os, Path sourceDir, Path archive, CancellationToken cancel)
      throws IOException {
    Objects.requireNonNull(sourceDir, "sourceDir");
    Objects.requireNonNull(archive, "archive");
    Path root = sourceDir.toAbsolutePath().normalize();
    if (!Files.isDirectory(root)) {
      throw new IOException(root + " is not a directory");
    }
    Files.createDirectories(archive.toAbsolutePath().getParent());
    Path part = archive.resolveSibling(archive.getFileName() + ".part");
    Files.deleteIfExists(part);
    long[] count = {0};
    try (OutputStream raw = new BufferedOutputStream(Files.newOutputStream(part), BUFFER)) {
      switch (os) {
        case LINUX -> {
          try (TarArchiveOutputStream tar =
              new TarArchiveOutputStream(new GZIPOutputStream(raw), BUFFER)) {
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
            tar.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX);
            walk(
                root,
                cancel,
                (file, rel, attrs) -> {
                  TarArchiveEntry entry = tarEntry(file, rel, attrs);
                  tar.putArchiveEntry(entry);
                  // Content follows only for a real file. TarArchiveEntry.isFile() is true for a
                  // symbolic link too, since it only asks whether the name ends in "/", and
                  // copying then writes the link target's bytes into a zero-length header.
                  if (attrs.isRegularFile()) {
                    copy(file, tar);
                  }
                  tar.closeArchiveEntry();
                  count[0]++;
                });
          }
        }
        case WINDOWS -> {
          try (ZipArchiveOutputStream zip = new ZipArchiveOutputStream(raw)) {
            walk(
                root,
                cancel,
                (file, rel, attrs) -> {
                  if (!attrs.isDirectory() && !attrs.isRegularFile()) {
                    throw new IOException(
                        "cannot archive "
                            + file
                            + ": a zip archive cannot carry a link or device node, so restoring"
                            + " this backup would not reproduce the tree");
                  }
                  ZipArchiveEntry entry =
                      new ZipArchiveEntry(rel + (attrs.isDirectory() ? "/" : ""));
                  entry.setTime(attrs.lastModifiedTime().toMillis());
                  zip.putArchiveEntry(entry);
                  if (!attrs.isDirectory()) {
                    copy(file, zip);
                  }
                  zip.closeArchiveEntry();
                  count[0]++;
                });
          }
        }
      }
    }
    Files.move(part, archive, StandardCopyOption.REPLACE_EXISTING);
    return count[0];
  }

  /** Extracts {@code archive} into {@code targetDir}, which is created if missing. */
  static long extract(Platform.OsFamily os, Path archive, Path targetDir, CancellationToken cancel)
      throws IOException {
    Path root = targetDir.toAbsolutePath().normalize();
    Files.createDirectories(root);
    long count = 0;
    try (InputStream raw = new BufferedInputStream(Files.newInputStream(archive), BUFFER)) {
      switch (os) {
        case LINUX -> {
          List<Ownership> metadata = new ArrayList<>();
          List<TarArchiveEntry> links = new ArrayList<>();
          try (TarArchiveInputStream tar = new TarArchiveInputStream(new GZIPInputStream(raw))) {
            TarArchiveEntry entry;
            while ((entry = tar.getNextEntry()) != null) {
              cancel.checkpoint();
              Path target = resolve(root, entry);
              if (entry.isSymbolicLink()) {
                // Deferred: a link created now could be the path a later entry is written through.
                links.add(entry);
              } else if (entry.isDirectory()) {
                Files.createDirectories(target);
                metadata.add(ownership(target, entry));
              } else {
                Files.createDirectories(target.getParent());
                write(tar, target);
                metadata.add(ownership(target, entry));
              }
              count++;
            }
          }
          for (TarArchiveEntry link : links) {
            createSymbolicLink(resolve(root, link), link.getLinkName());
          }
          reapply(metadata);
        }
        case WINDOWS -> {
          try (ZipArchiveInputStream zip = new ZipArchiveInputStream(raw)) {
            ZipArchiveEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
              cancel.checkpoint();
              Path target = resolve(root, entry);
              if (entry.isDirectory()) {
                Files.createDirectories(target);
              } else {
                Files.createDirectories(target.getParent());
                write(zip, target);
              }
              count++;
            }
          }
        }
      }
    }
    return count;
  }

  /** Lists the entry names of an archive, for verification and reporting. */
  static List<String> entries(Platform.OsFamily os, Path archive) throws IOException {
    List<String> names = new ArrayList<>();
    try (InputStream raw = new BufferedInputStream(Files.newInputStream(archive), BUFFER)) {
      switch (os) {
        case LINUX -> {
          try (TarArchiveInputStream tar = new TarArchiveInputStream(new GZIPInputStream(raw))) {
            TarArchiveEntry entry;
            while ((entry = tar.getNextEntry()) != null) {
              names.add(entry.getName());
            }
          }
        }
        case WINDOWS -> {
          try (ZipArchiveInputStream zip = new ZipArchiveInputStream(raw)) {
            ZipArchiveEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
              names.add(entry.getName());
            }
          }
        }
      }
    }
    return List.copyOf(names);
  }

  /** Deletes a directory tree; a missing directory is not an error. */
  static void deleteRecursively(Path dir) throws IOException {
    if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
      return;
    }
    Files.walkFileTree(
        dir,
        new SimpleFileVisitor<>() {
          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
              throws IOException {
            Files.delete(file);
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult postVisitDirectory(Path directory, IOException error)
              throws IOException {
            if (error != null) {
              throw error;
            }
            Files.delete(directory);
            return FileVisitResult.CONTINUE;
          }
        });
  }

  @FunctionalInterface
  private interface EntryWriter {
    void write(Path file, String relative, BasicFileAttributes attrs) throws IOException;
  }

  private static void walk(Path root, CancellationToken cancel, EntryWriter writer)
      throws IOException {
    Files.walkFileTree(
        root,
        new SimpleFileVisitor<>() {
          @Override
          public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
              throws IOException {
            if (!dir.equals(root)) {
              cancel.checkpoint();
              writer.write(dir, relative(root, dir), attrs);
            }
            return FileVisitResult.CONTINUE;
          }

          /**
           * Every non-directory the walk reaches, symbolic links included. The walk does not follow
           * links, so a link to a directory arrives here rather than being descended, and the
           * per-format writer decides whether it can be represented; nothing is dropped in silence.
           */
          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
              throws IOException {
            cancel.checkpoint();
            writer.write(file, relative(root, file), attrs);
            return FileVisitResult.CONTINUE;
          }
        });
  }

  private static String relative(Path root, Path path) {
    return root.relativize(path).toString().replace('\\', '/');
  }

  private static Path resolve(Path root, ArchiveEntry entry) throws IOException {
    String name = entry.getName();
    if (name.isBlank() || name.startsWith("/") || name.contains("..") || name.contains(":")) {
      throw new IOException("refusing archive entry '" + name + "'");
    }
    Path target = root.resolve(name).normalize();
    if (!target.startsWith(root)) {
      throw new IOException("archive entry '" + name + "' escapes " + root);
    }
    return target;
  }

  private static void copy(Path file, OutputStream out) throws IOException {
    byte[] buffer = new byte[BUFFER];
    try (InputStream in = Files.newInputStream(file)) {
      int read;
      while ((read = in.read(buffer)) != -1) {
        out.write(buffer, 0, read);
      }
    }
  }

  private static void write(InputStream in, Path target) throws IOException {
    byte[] buffer = new byte[BUFFER];
    try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(target), BUFFER)) {
      int read;
      while ((read = in.read(buffer)) != -1) {
        out.write(buffer, 0, read);
      }
    }
  }

  /**
   * The tar entry for one path, carrying its mode, owner and group; a symbolic link becomes a link
   * entry holding what it points at, never a copy of the target.
   */
  private static TarArchiveEntry tarEntry(Path file, String rel, BasicFileAttributes attrs)
      throws IOException {
    if (attrs.isSymbolicLink()) {
      TarArchiveEntry entry = new TarArchiveEntry(rel, TarConstants.LF_SYMLINK);
      entry.setLinkName(Files.readSymbolicLink(file).toString().replace('\\', '/'));
      entry.setModTime(attrs.lastModifiedTime().toMillis());
      entry.setSize(0);
      return entry;
    }
    if (!attrs.isDirectory() && !attrs.isRegularFile()) {
      throw new IOException(
          "cannot archive " + file + ": it is neither a file, a directory nor a symbolic link");
    }
    TarArchiveEntry entry = new TarArchiveEntry(rel + (attrs.isDirectory() ? "/" : ""));
    entry.setModTime(attrs.lastModifiedTime().toMillis());
    entry.setMode(mode(file, attrs));
    owner(file).ifPresent(o -> applyOwnerTo(entry, o));
    if (!attrs.isDirectory()) {
      entry.setSize(attrs.size());
    }
    return entry;
  }

  private static void applyOwnerTo(TarArchiveEntry entry, Ownership owner) {
    entry.setUserName(owner.user());
    entry.setGroupName(owner.group());
    entry.setUserId(owner.uid());
    entry.setGroupId(owner.gid());
  }

  /** Owner and group of a path, empty on a file system that has no POSIX view (Windows). */
  private static Optional<Ownership> owner(Path file) {
    try {
      PosixFileAttributes posix =
          Files.readAttributes(file, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      return Optional.of(
          new Ownership(
              file,
              0,
              posix.owner().getName(),
              posix.group().getName(),
              numeric(file, "unix:uid"),
              numeric(file, "unix:gid")));
    } catch (UnsupportedOperationException | IOException e) {
      return Optional.empty();
    }
  }

  private static int numeric(Path file, String attribute) {
    try {
      Object value = Files.getAttribute(file, attribute, LinkOption.NOFOLLOW_LINKS);
      return value instanceof Integer id ? id : 0;
    } catch (UnsupportedOperationException | IOException | IllegalArgumentException e) {
      return 0;
    }
  }

  private static Ownership ownership(Path target, TarArchiveEntry entry) {
    return new Ownership(
        target,
        entry.getMode(),
        entry.getUserName() == null ? "" : entry.getUserName(),
        entry.getGroupName() == null ? "" : entry.getGroupName(),
        (int) entry.getLongUserId(),
        (int) entry.getLongGroupId());
  }

  private static void createSymbolicLink(Path target, String linkName) throws IOException {
    Files.deleteIfExists(target);
    Files.createDirectories(target.getParent());
    try {
      Files.createSymbolicLink(target, target.getFileSystem().getPath(linkName));
    } catch (UnsupportedOperationException e) {
      throw new IOException("cannot recreate the symbolic link " + target + " -> " + linkName, e);
    }
  }

  /**
   * Re-applies mode, owner and group deepest path first, so a directory whose mode forbids writing
   * is only locked down once its children are there. A failure to change owner is reported once and
   * does not fail the restore: only root may give a file away, and a tree restored under the
   * operator's own account is still a restored tree.
   */
  private static void reapply(List<Ownership> metadata) {
    List<Ownership> ordered = new ArrayList<>(metadata);
    ordered.sort(Comparator.comparingInt((Ownership o) -> o.target().getNameCount()).reversed());
    boolean reported = false;
    for (Ownership o : ordered) {
      applyMode(o.target(), o.mode());
      if (!applyOwner(o) && !reported) {
        LOG.warn(
            "cannot restore the owner of {}; the tree keeps this process's own user and group",
            o.target());
        reported = true;
      }
    }
  }

  /** True when owner and group were set, or when there was nothing recorded to set. */
  private static boolean applyOwner(Ownership o) {
    if (o.user().isEmpty() && o.group().isEmpty()) {
      return true;
    }
    PosixFileAttributeView view =
        Files.getFileAttributeView(o.target(), PosixFileAttributeView.class);
    if (view == null) {
      return true;
    }
    try {
      UserPrincipalLookupService lookup =
          o.target().getFileSystem().getUserPrincipalLookupService();
      if (!o.user().isEmpty()) {
        view.setOwner(lookup.lookupPrincipalByName(o.user()));
      }
      if (!o.group().isEmpty()) {
        view.setGroup(lookup.lookupPrincipalByGroupName(o.group()));
      }
      return true;
    } catch (UnsupportedOperationException | IOException | SecurityException e) {
      LOG.debug("cannot set owner {}:{} on {}: {}", o.user(), o.group(), o.target(), e.toString());
      return false;
    }
  }

  private static int mode(Path file, BasicFileAttributes attrs) {
    int base =
        attrs.isDirectory() ? TarArchiveEntry.DEFAULT_DIR_MODE : TarArchiveEntry.DEFAULT_FILE_MODE;
    try {
      Set<PosixFilePermission> perms = Files.getPosixFilePermissions(file);
      return posixMode(perms);
    } catch (UnsupportedOperationException | IOException e) {
      return base;
    }
  }

  private static int posixMode(Set<PosixFilePermission> perms) {
    int mode = 0;
    for (PosixFilePermission p : perms) {
      mode |=
          switch (p) {
            case OWNER_READ -> 0400;
            case OWNER_WRITE -> 0200;
            case OWNER_EXECUTE -> 0100;
            case GROUP_READ -> 040;
            case GROUP_WRITE -> 020;
            case GROUP_EXECUTE -> 010;
            case OTHERS_READ -> 04;
            case OTHERS_WRITE -> 02;
            case OTHERS_EXECUTE -> 01;
          };
    }
    return mode;
  }

  private static void applyMode(Path target, int mode) {
    if (mode == 0) {
      return;
    }
    StringBuilder rwx = new StringBuilder();
    int[] shifts = {6, 3, 0};
    for (int shift : shifts) {
      int bits = (mode >> shift) & 07;
      rwx.append((bits & 4) != 0 ? 'r' : '-');
      rwx.append((bits & 2) != 0 ? 'w' : '-');
      rwx.append((bits & 1) != 0 ? 'x' : '-');
    }
    try {
      Files.setPosixFilePermissions(target, PosixFilePermissions.fromString(rwx.toString()));
    } catch (UnsupportedOperationException | IOException e) {
      // not a POSIX file system: permissions are inherited from the parent directory
    }
  }
}
