package com.jaspersoft.jrsctl.dist;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;

/**
 * Build-time helper (never shipped): writes the integrity manifest of the portable image and the
 * checksum sidecar of an archive. Invariants (documented in {@code dist/README.md}, verified by
 * {@code selfcheck} from Phase 8 on): {@code MANIFEST.sha256} holds one line per regular file of
 * the image except itself, {@code <sha256-hex> <path>} with two spaces and forward slashes,
 * relative to the image root, sorted by path, LF terminated; hashes are streamed, never whole-file
 * byte arrays. {@code <archive>.sha256} holds one line in the same {@code sha256sum -c} format with
 * the archive's bare file name.
 *
 * <p>Usage: {@code ImageManifest manifest <image-dir>} or {@code ImageManifest checksum <file>}.
 */
public final class ImageManifest {

  public static final String MANIFEST = "MANIFEST.sha256";

  private ImageManifest() {}

  public static void main(String[] args) throws IOException, NoSuchAlgorithmException {
    if (args.length != 2) {
      System.err.println("usage: ImageManifest manifest <image-dir> | checksum <file>");
      System.exit(1);
    }
    switch (args[0]) {
      case "manifest" -> writeManifest(Path.of(args[1]).toAbsolutePath().normalize());
      case "checksum" -> writeChecksum(Path.of(args[1]).toAbsolutePath().normalize());
      default -> {
        System.err.println("unknown mode " + args[0]);
        System.exit(1);
      }
    }
  }

  private static void writeManifest(Path image) throws IOException, NoSuchAlgorithmException {
    Path manifest = image.resolve(MANIFEST);
    Files.deleteIfExists(manifest);
    List<Path> files;
    try (Stream<Path> walk = Files.walk(image)) {
      files =
          walk.filter(Files::isRegularFile)
              .map(image::relativize)
              .sorted((a, b) -> slashed(a).compareTo(slashed(b)))
              .toList();
    }
    StringBuilder sb = new StringBuilder();
    for (Path rel : files) {
      sb.append(sha256(image.resolve(rel))).append("  ").append(slashed(rel)).append('\n');
    }
    Files.writeString(manifest, sb.toString(), StandardCharsets.UTF_8);
    System.out.println(MANIFEST + ": " + files.size() + " files");
  }

  private static void writeChecksum(Path file) throws IOException, NoSuchAlgorithmException {
    if (!Files.isRegularFile(file)) {
      System.err.println("archive not found: " + file);
      System.exit(1);
    }
    Path sidecar = file.resolveSibling(file.getFileName() + ".sha256");
    String line = sha256(file) + "  " + file.getFileName() + "\n";
    Files.writeString(sidecar, line, StandardCharsets.UTF_8);
    System.out.print(sidecar.getFileName() + ": " + line);
  }

  static String sha256(Path file) throws IOException, NoSuchAlgorithmException {
    MessageDigest md = MessageDigest.getInstance("SHA-256");
    byte[] buf = new byte[65536];
    try (InputStream in = Files.newInputStream(file)) {
      for (int n = in.read(buf); n > 0; n = in.read(buf)) {
        md.update(buf, 0, n);
      }
    }
    return HexFormat.of().formatHex(md.digest());
  }

  private static String slashed(Path rel) {
    return rel.toString().replace('\\', '/');
  }
}
