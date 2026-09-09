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
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import org.apache.commons.compress.archivers.ArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;

/**
 * Directory archives for the upgrade backups (spec §10.2 step 6): tar.gz on Linux, where POSIX
 * permission bits are stored in the tar header and re-applied on extraction, zip on Windows, where
 * ACLs are not archived (the webapp is restored under the same parent directory and inherits its
 * ACL). Invariants: everything streams through fixed-size buffers, never whole-file byte arrays;
 * entry names are relative, forward-slash separated and rejected on extraction if they would escape
 * the target directory; the cancellation token is checked once per entry.
 */
final class Archives {

  private static final int BUFFER = 64 * 1024;

  private Archives() {}

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
                  TarArchiveEntry entry =
                      new TarArchiveEntry(rel + (attrs.isDirectory() ? "/" : ""));
                  entry.setModTime(attrs.lastModifiedTime().toMillis());
                  entry.setMode(mode(file, attrs));
                  if (!attrs.isDirectory()) {
                    entry.setSize(attrs.size());
                  }
                  tar.putArchiveEntry(entry);
                  if (!attrs.isDirectory()) {
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
          try (TarArchiveInputStream tar = new TarArchiveInputStream(new GZIPInputStream(raw))) {
            TarArchiveEntry entry;
            while ((entry = tar.getNextEntry()) != null) {
              cancel.checkpoint();
              Path target = resolve(root, entry);
              if (entry.isDirectory()) {
                Files.createDirectories(target);
              } else {
                Files.createDirectories(target.getParent());
                write(tar, target);
                applyMode(target, entry.getMode());
              }
              count++;
            }
          }
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

          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
              throws IOException {
            cancel.checkpoint();
            if (attrs.isRegularFile()) {
              writer.write(file, relative(root, file), attrs);
            }
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
