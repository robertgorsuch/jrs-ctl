package com.jaspersoft.jrsctl.ops.hotfix;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/** Test helper that rewrites a built bundle: drop, replace or add entries without re-signing. */
final class Zips {

  private Zips() {}

  /** Entry names in ZIP order. */
  static List<String> entries(Path zip) throws IOException {
    List<String> names = new ArrayList<>();
    try (InputStream raw = Files.newInputStream(zip);
        ZipInputStream in = new ZipInputStream(raw, StandardCharsets.UTF_8)) {
      ZipEntry e;
      while ((e = in.getNextEntry()) != null) {
        names.add(e.getName());
      }
    }
    return names;
  }

  /**
   * Copies {@code zip} to {@code out}: entries in {@code replacements} get the new bytes (an empty
   * value drops the entry); {@code additions} are appended.
   */
  static Path rewrite(
      Path zip, Path out, Map<String, Optional<byte[]>> replacements, Map<String, byte[]> additions)
      throws IOException {
    try (InputStream raw = Files.newInputStream(zip);
        ZipInputStream in = new ZipInputStream(raw, StandardCharsets.UTF_8);
        OutputStream rawOut = Files.newOutputStream(out);
        ZipOutputStream zos = new ZipOutputStream(rawOut, StandardCharsets.UTF_8)) {
      ZipEntry e;
      while ((e = in.getNextEntry()) != null) {
        Optional<byte[]> replacement = replacements.get(e.getName());
        if (replacement != null && replacement.isEmpty()) {
          continue;
        }
        zos.putNextEntry(new ZipEntry(e.getName()));
        if (replacement != null) {
          zos.write(replacement.get());
        } else {
          in.transferTo(zos);
        }
        zos.closeEntry();
      }
      for (Map.Entry<String, byte[]> add : additions.entrySet()) {
        zos.putNextEntry(new ZipEntry(add.getKey()));
        zos.write(add.getValue());
        zos.closeEntry();
      }
    }
    return out;
  }
}
