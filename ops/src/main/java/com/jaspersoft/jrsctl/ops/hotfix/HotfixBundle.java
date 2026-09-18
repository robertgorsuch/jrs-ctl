package com.jaspersoft.jrsctl.ops.hotfix;

import com.jaspersoft.jrsctl.core.platform.Trees;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * A hotfix bundle unpacked into a directory (spec §8.1): {@code manifest.json}, optional {@code
 * SIGNATURE}, and the {@code payload/}, {@code sql/} and {@code checks/} trees. Invariants: the
 * manifest bytes are kept exactly as they were in the ZIP because the signature covers them; entry
 * names are validated against ZIP-slip (relative, no {@code ..}, no drive letters, no backslashes)
 * before anything is written; entries are streamed, never buffered whole; {@link #files()} lists
 * every regular file except the manifest and the signature, so anything not listed in the manifest
 * is an "unlisted file" for the verifier.
 */
public final class HotfixBundle {

  public static final String MANIFEST = "manifest.json";
  public static final String SIGNATURE = "SIGNATURE";
  public static final String PAYLOAD_DIR = "payload";
  public static final String SQL_DIR = "sql";
  public static final String CHECKS_DIR = "checks";

  /** Manifests larger than this are refused; a manifest is a few kilobytes of JSON. */
  static final long MAX_MANIFEST_BYTES = 4L << 20;

  /** Ceiling on the unpacked payload of one bundle (item H7); a real hotfix is a few jars. */
  static final long MAX_BUNDLE_BYTES = 2L << 30;

  private static final int BUFFER = 64 * 1024;

  private final Path dir;
  private final byte[] manifestBytes;
  private final Optional<String> signature;
  private final List<String> files;

  HotfixBundle(Path dir, byte[] manifestBytes, Optional<String> signature, List<String> files) {
    this.dir = Objects.requireNonNull(dir, "dir");
    this.manifestBytes = manifestBytes.clone();
    this.signature = Objects.requireNonNull(signature, "signature");
    this.files = List.copyOf(files);
  }

  /** The unpacked directory. */
  public Path dir() {
    return dir;
  }

  /** Exact bytes of {@code manifest.json}; the signature covers them. */
  public byte[] manifestBytes() {
    return manifestBytes.clone();
  }

  /** Base64 text of {@code SIGNATURE}, when the bundle carries one. */
  public Optional<String> signature() {
    return signature;
  }

  /** Every regular file except the manifest and the signature, bundle-relative, sorted. */
  public List<String> files() {
    return files;
  }

  /** Unpacks {@code zip} into {@code dir} (created, must be empty or absent) and opens it. */
  public static HotfixBundle extract(Path zip, Path dir) throws IOException {
    return extract(zip, dir, MAX_BUNDLE_BYTES);
  }

  /**
   * As {@link #extract(Path, Path)} with an explicit ceiling on the unpacked payload. The payload
   * is unpacked under the jrsctl home before the disk-space preflight sizes anything, so a bundle
   * far larger than any hotfix is refused rather than allowed to fill the volume (assessment item
   * H7); a genuine hotfix is a few jars, orders of magnitude below the default.
   */
  static HotfixBundle extract(Path zip, Path dir, long maxBytes) throws IOException {
    Files.createDirectories(dir);
    boolean manifestSeen = false;
    long remaining = maxBytes;
    try (InputStream raw = Files.newInputStream(zip);
        ZipInputStream in = new ZipInputStream(raw, StandardCharsets.UTF_8)) {
      ZipEntry entry;
      while ((entry = in.getNextEntry()) != null) {
        String name = entry.getName();
        if (entry.isDirectory()) {
          continue;
        }
        checkEntryName(name);
        Path target = dir.resolve(name).normalize();
        if (!target.startsWith(dir.toAbsolutePath().normalize())
            && !target.startsWith(dir.normalize())) {
          throw new IOException("ZIP entry escapes the bundle directory: " + name);
        }
        Files.createDirectories(target.getParent());
        long limit = name.equals(MANIFEST) ? Math.min(MAX_MANIFEST_BYTES, remaining) : remaining;
        remaining -= copy(in, target, limit, name + " (bundle ceiling " + maxBytes + " bytes)");
        manifestSeen |= name.equals(MANIFEST);
      }
    } catch (IllegalArgumentException e) {
      throw new IOException("not a readable ZIP: " + e.getMessage(), e);
    }
    if (!manifestSeen) {
      throw new IOException("bundle has no " + MANIFEST);
    }
    return open(dir);
  }

  /** Opens an already unpacked bundle directory. */
  public static HotfixBundle open(Path dir) throws IOException {
    Path root = dir.toAbsolutePath().normalize();
    Path manifest = root.resolve(MANIFEST);
    if (!Files.isRegularFile(manifest)) {
      throw new IOException("bundle has no " + MANIFEST);
    }
    if (Files.size(manifest) > MAX_MANIFEST_BYTES) {
      throw new IOException(MANIFEST + " exceeds " + MAX_MANIFEST_BYTES + " bytes");
    }
    byte[] manifestBytes;
    try (InputStream in = Files.newInputStream(manifest)) {
      manifestBytes = in.readNBytes((int) MAX_MANIFEST_BYTES);
    }
    Path signatureFile = root.resolve(SIGNATURE);
    Optional<String> signature =
        Files.isRegularFile(signatureFile)
            ? Optional.of(Files.readString(signatureFile, StandardCharsets.US_ASCII).strip())
            : Optional.empty();
    List<String> files = new ArrayList<>();
    Files.walkFileTree(
        root,
        new SimpleFileVisitor<>() {
          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
            String rel = relative(root, file);
            if (!rel.equals(MANIFEST) && !rel.equals(SIGNATURE)) {
              files.add(rel);
            }
            return FileVisitResult.CONTINUE;
          }
        });
    files.sort(String::compareTo);
    return new HotfixBundle(root, manifestBytes, signature, files);
  }

  /** The manifest as UTF-8 text. */
  public String manifestJson() {
    return new String(manifestBytes, StandardCharsets.UTF_8);
  }

  /** Absolute path of a bundle-relative entry. */
  public Path file(String entry) {
    return dir.resolve(entry.replace('\\', '/'));
  }

  /** Deletes the unpacked directory; safe to call twice. */
  public void delete() throws IOException {
    Trees.deleteRecursively(dir);
  }

  /** Bundle-relative name with forward slashes. */
  static String relative(Path root, Path file) {
    StringBuilder sb = new StringBuilder();
    for (Path element : root.relativize(file)) {
      if (sb.length() > 0) {
        sb.append('/');
      }
      sb.append(element);
    }
    return sb.toString();
  }

  static void checkEntryName(String name) throws IOException {
    Optional<String> problem = entryNameProblem(name);
    if (problem.isPresent()) {
      throw new IOException("ZIP entry name is not acceptable (" + problem.get() + "): " + name);
    }
  }

  /**
   * Why {@code name} may not be a bundle entry, or empty when it may. The rule is a denylist:
   * traversal, absolute and drive-relative names and control characters are refused, and every
   * other character the vendor ships in a path (spaces, {@code +}, parentheses, non-ASCII) is
   * allowed, since a package converted from support (ADR-0024) is read back through here.
   */
  static Optional<String> entryNameProblem(String name) {
    if (name.isEmpty()) {
      return Optional.of("empty");
    }
    if (name.startsWith("/") || name.contains("\\") || name.contains(":")) {
      return Optional.of("must be a relative path with forward slashes and no drive letter");
    }
    for (String segment : name.split("/", -1)) {
      if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
        return Optional.of("must not contain empty, . or .. segments");
      }
    }
    for (int i = 0; i < name.length(); i++) {
      char c = name.charAt(i);
      if (c < 0x20 || c == 0x7f) {
        return Optional.of("must not contain control characters");
      }
    }
    return Optional.empty();
  }

  /**
   * True when {@code zip} can be read as a ZIP and holds no {@code manifest.json} at its top level:
   * a file that is not a bundle. False for a bundle and for a file that cannot be read, which the
   * extraction reports itself.
   */
  public static boolean lacksRootManifest(Path zip) {
    if (!Files.isRegularFile(zip)) {
      return false;
    }
    try (InputStream raw = Files.newInputStream(zip);
        ZipInputStream in = new ZipInputStream(raw, StandardCharsets.UTF_8)) {
      ZipEntry entry;
      while ((entry = in.getNextEntry()) != null) {
        if (entry.getName().equals(MANIFEST)) {
          return false;
        }
      }
      return true;
    } catch (IOException | IllegalArgumentException e) {
      return false;
    }
  }

  /** Streams one entry to {@code target}; returns the bytes written. */
  private static long copy(InputStream in, Path target, long limit, String name)
      throws IOException {
    byte[] buffer = new byte[BUFFER];
    long total = 0;
    try (OutputStream out =
        Files.newOutputStream(
            target, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
      int read;
      while ((read = in.read(buffer)) != -1) {
        total += read;
        if (total > limit) {
          throw new IOException(name + " exceeds " + limit + " bytes");
        }
        out.write(buffer, 0, read);
      }
    }
    return total;
  }
}
