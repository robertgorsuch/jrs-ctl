package com.jaspersoft.jrsctl.ops.upgrade;

import com.jaspersoft.jrsctl.core.engine.CancellationToken;
import com.jaspersoft.jrsctl.core.platform.Platform;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.apache.commons.compress.archivers.ArchiveEntry;

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

  static final int BUFFER = 64 * 1024;

  private Archives() {}

  /** File-name suffix of the archive kind used on the given OS. */
  static String extension(Platform.OsFamily os) {
    return ArchiveFormat.of(os).extension();
  }

  /**
   * Writes every file under {@code sourceDir} into {@code archive}, through a {@code .part} file
   * that is renamed only once the last entry is written, so a crash never leaves a half archive
   * where a restore would find it.
   */
  static long create(Platform.OsFamily os, Path sourceDir, Path archive, CancellationToken cancel)
      throws IOException {
    return create(os, sourceDir, archive, cancel, false).entries();
  }

  /**
   * {@link #create(Platform.OsFamily, Path, Path, CancellationToken)} with a choice for a file that
   * opens but cannot be read, which on Windows is one another process holds a byte-range lock on
   * (ADR-0044): with {@code skipLockedFiles} it is left out of the archive and named in the result,
   * else it fails the archive with its path in the message.
   */
  static Created create(
      Platform.OsFamily os,
      Path sourceDir,
      Path archive,
      CancellationToken cancel,
      boolean skipLockedFiles)
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
    LockedFiles locked = new LockedFiles(skipLockedFiles);
    long count;
    try (OutputStream raw = new BufferedOutputStream(Files.newOutputStream(part), BUFFER)) {
      count = ArchiveFormat.of(os).write(root, raw, cancel, locked);
    }
    Files.move(part, archive, StandardCopyOption.REPLACE_EXISTING);
    return new Created(count, locked.skipped());
  }

  /** What {@link #create} wrote: the entry count and the files it left out, in walk order. */
  record Created(long entries, List<Path> skipped) {
    Created {
      skipped = List.copyOf(skipped);
    }
  }

  /**
   * A file the archive opened but could not read. The open proves the path exists and is readable
   * by this account, so a failed read is the file system refusing the bytes: on Windows a
   * byte-range lock another process holds (error 33, "another process has locked a portion of the
   * file"), which no permission or elevation gets past.
   */
  static final class LockedFileException extends IOException {
    private final transient Path file;

    LockedFileException(Path file, IOException cause) {
      super("cannot read " + file + ": " + cause.getMessage(), cause);
      this.file = file;
    }

    Path file() {
      return file;
    }
  }

  /**
   * The archive's policy for a file that opens but cannot be read, and the record of the files it
   * skipped. Invariant: a skipped file leaves no entry at all, because {@link #open} reads the
   * first chunk before the caller writes an entry header; a file whose later chunk fails is still
   * an error naming the path, since an entry already begun cannot be taken back.
   */
  static final class LockedFiles {
    private final boolean skip;
    private final List<Path> skipped = new ArrayList<>();

    LockedFiles(boolean skip) {
      this.skip = skip;
    }

    /** Opens {@code file} with its first chunk read; empty when the file is skipped. */
    Optional<Reader> open(Path file) throws IOException {
      InputStream in = Files.newInputStream(file);
      byte[] buffer = new byte[BUFFER];
      int first;
      try {
        first = in.read(buffer);
      } catch (IOException e) {
        in.close();
        if (skip) {
          skipped.add(file);
          return Optional.empty();
        }
        throw new LockedFileException(file, e);
      }
      return Optional.of(new Reader(file, in, buffer, first));
    }

    List<Path> skipped() {
      return List.copyOf(skipped);
    }
  }

  /** An opened file whose first chunk is already in hand; streams the rest on demand. */
  static final class Reader implements Closeable {
    private final Path file;
    private final InputStream in;
    private final byte[] buffer;
    private final int first;

    private Reader(Path file, InputStream in, byte[] buffer, int first) {
      this.file = file;
      this.in = in;
      this.buffer = buffer;
      this.first = first;
    }

    void copyTo(OutputStream out) throws IOException {
      int read = first;
      while (read != -1) {
        out.write(buffer, 0, read);
        try {
          read = in.read(buffer);
        } catch (IOException e) {
          throw new IOException("cannot read " + file + ": " + e.getMessage(), e);
        }
      }
    }

    @Override
    public void close() throws IOException {
      in.close();
    }
  }

  /** Extracts {@code archive} into {@code targetDir}, which is created if missing. */
  static long extract(Platform.OsFamily os, Path archive, Path targetDir, CancellationToken cancel)
      throws IOException {
    Path root = targetDir.toAbsolutePath().normalize();
    Files.createDirectories(root);
    try (InputStream raw = new BufferedInputStream(Files.newInputStream(archive), BUFFER)) {
      return ArchiveFormat.of(os).read(raw, root, cancel);
    }
  }

  /** Lists the entry names of an archive, for verification and reporting. */
  static List<String> entries(Platform.OsFamily os, Path archive) throws IOException {
    try (InputStream raw = new BufferedInputStream(Files.newInputStream(archive), BUFFER)) {
      return ArchiveFormat.of(os).names(raw);
    }
  }

  @FunctionalInterface
  interface EntryWriter {
    void write(Path file, String relative, BasicFileAttributes attrs) throws IOException;
  }

  static void walk(Path root, CancellationToken cancel, EntryWriter writer) throws IOException {
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

          /*
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

  /**
   * Where an entry lands. Refused: a blank name, an absolute root (a leading slash or backslash, or
   * a drive letter), and a {@code ..} segment. A {@code ..} or a {@code :} inside a segment is a
   * legal Linux file name and is kept, since a backup that cannot be restored is no backup
   * (assessment item O3); the create side refuses what the extract side would.
   */
  static Path resolve(Path root, ArchiveEntry entry) throws IOException {
    String name = entry.getName();
    if (name.isBlank() || isAbsoluteName(name) || hasParentSegment(name)) {
      throw new IOException("refusing archive entry '" + name + "'");
    }
    Path target = root.resolve(name).normalize();
    if (!target.startsWith(root)) {
      throw new IOException("archive entry '" + name + "' escapes " + root);
    }
    return target;
  }

  /**
   * Where a link entry may point: never at an absolute path, never outside the tree once resolved
   * from the link's own directory, and {@code ..} only at the start of the target. Applied when
   * archiving (the tree being backed up must not carry such a link) and when extracting (the
   * archive must not either). The textual check is exact only because of the last rule and {@link
   * #checkLinkPlacement}: a {@code ..} after a segment that is itself a link would climb from
   * wherever that link points (review finding 9).
   */
  static void checkLinkTarget(Path root, Path link, String linkTarget) throws IOException {
    if (linkTarget.isBlank() || isAbsoluteName(linkTarget)) {
      throw new IOException("refusing " + link + ": link target '" + linkTarget + "' is absolute");
    }
    boolean descended = false;
    for (String segment : linkTarget.replace('\\', '/').split("/", -1)) {
      if (segment.equals("..")) {
        if (descended) {
          throw new IOException(
              "refusing "
                  + link
                  + ": link target '"
                  + linkTarget
                  + "' has a '..' after a name, which a link under that name would turn elsewhere");
        }
      } else if (!segment.isEmpty() && !segment.equals(".")) {
        descended = true;
      }
    }
    Path parent = link.toAbsolutePath().normalize().getParent();
    Path resolved = parent.resolve(linkTarget.replace('\\', '/')).normalize();
    if (!resolved.startsWith(root.toAbsolutePath().normalize())) {
      throw new IOException(
          "refusing " + link + ": link target '" + linkTarget + "' leaves " + root);
    }
  }

  /**
   * Review finding 9: a link is never created under another link. Every directory between {@code
   * root} and the link must be a real directory on disk (or not exist yet), so the link lands where
   * its name says and its target is resolved from there; an archive jrsctl wrote never has such an
   * entry, since the walk that archives a tree does not follow links.
   */
  static void checkLinkPlacement(Path root, Path link, String linkTarget) throws IOException {
    Path base = root.toAbsolutePath().normalize();
    // Archives.resolve already keeps the link's own path under root, so its parent is too
    Path parent = link.toAbsolutePath().normalize().getParent();
    Path at = base;
    for (Path segment : base.relativize(parent)) {
      if (segment.toString().isEmpty()) {
        continue;
      }
      at = at.resolve(segment);
      if (Files.isSymbolicLink(at)) {
        throw new IOException(
            "refusing "
                + link
                + ": link target '"
                + linkTarget
                + "' would be created through the link "
                + at);
      }
    }
  }

  static boolean isAbsoluteName(String name) {
    return name.startsWith("/")
        || name.startsWith("\\")
        || (name.length() >= 2 && Character.isLetter(name.charAt(0)) && name.charAt(1) == ':');
  }

  static boolean hasParentSegment(String name) {
    String slashed = name.replace('\\', '/');
    int from = 0;
    while (from <= slashed.length()) {
      int to = slashed.indexOf('/', from);
      if (to < 0) {
        to = slashed.length();
      }
      if (slashed.substring(from, to).equals("..")) {
        return true;
      }
      from = to + 1;
    }
    return false;
  }

  static void write(InputStream in, Path target) throws IOException {
    byte[] buffer = new byte[BUFFER];
    try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(target), BUFFER)) {
      int read;
      while ((read = in.read(buffer)) != -1) {
        out.write(buffer, 0, read);
      }
    }
  }
}
