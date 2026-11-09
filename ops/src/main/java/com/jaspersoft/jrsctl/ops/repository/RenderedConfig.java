package com.jaspersoft.jrsctl.ops.repository;

import com.jaspersoft.jrsctl.core.engine.Context;
import com.jaspersoft.jrsctl.core.redact.Redactor;
import com.jaspersoft.jrsctl.jrs.vendor.Buildomatic;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The configuration a buildomatic renders from {@code default_master.properties} into {@code
 * build_conf/default} (installation guide 10.1, "Locating Buildomatic-Generated Property Files"),
 * as {@code render-config} reads and keeps it (issue #279 item 6). Invariants: only {@code
 * *.properties} files are copied, line by line; a line whose key names a password or secret keeps
 * its key and gets {@link Redactor#MASK} as its value, every other line passes through the run's
 * redactor, so the copy under {@code snapshots/<runId>/rendered-config} never carries the database
 * password the rendered JDBC file holds in clear; the rendered JDBC URL is read with {@link
 * Properties}, the vendor's own reader; nothing here writes into the buildomatic.
 */
final class RenderedConfig {

  /** The vendor's rendered JDBC settings, and the key the plan compares. */
  static final String JDBC_PROPERTIES = "js.jdbc.properties";

  static final String JDBC_URL_KEY = "metadata.jdbc.url";

  private static final Pattern SECRET_KEY =
      Pattern.compile("(?i)(password|passwd|secret|keypass|credential)");

  private RenderedConfig() {}

  /** Where {@code js-ant gen-config} renders: {@code <buildomatic>/build_conf/default}. */
  static Path dir(Buildomatic buildomatic) {
    return buildomatic.dir().resolve("build_conf").resolve("default");
  }

  /** The run's masked copy: {@code snapshots/<runId>/rendered-config}. */
  static Path copyDir(Context ctx) {
    return ctx.home().snapshots().resolve(ctx.runId()).resolve(LoadSteps.RENDERED_DIR);
  }

  /** The value of {@code key} in the properties file, stripped; empty when absent or blank. */
  static Optional<String> property(Path file, String key) throws IOException {
    Properties props = new Properties();
    try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.ISO_8859_1)) {
      props.load(reader);
    }
    return Optional.ofNullable(props.getProperty(key)).map(String::strip).filter(v -> !v.isEmpty());
  }

  /** The {@code *.properties} files directly under {@code dir}, by name. */
  static List<Path> propertiesFiles(Path dir) throws IOException {
    if (!Files.isDirectory(dir)) {
      return List.of();
    }
    try (Stream<Path> files = Files.list(dir)) {
      return files
          .filter(Files::isRegularFile)
          .filter(f -> f.getFileName().toString().endsWith(".properties"))
          .sorted(Comparator.comparing(f -> f.getFileName().toString()))
          .toList();
    }
  }

  /**
   * Copies every rendered properties file from {@code from} to {@code to}, masked; returns the
   * names copied. An existing copy of a file is replaced, so a repeated copy converges.
   */
  static List<String> copy(Path from, Path to, Redactor redactor) throws IOException {
    Objects.requireNonNull(redactor, "redactor");
    List<String> names = new ArrayList<>();
    Files.createDirectories(to);
    for (Path file : propertiesFiles(from)) {
      String name = file.getFileName().toString();
      try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.ISO_8859_1);
          BufferedWriter writer =
              Files.newBufferedWriter(to.resolve(name), StandardCharsets.UTF_8)) {
        String line;
        while ((line = reader.readLine()) != null) {
          writer.write(mask(line, redactor));
          writer.newLine();
        }
      }
      names.add(name);
    }
    return names;
  }

  /** The line with a password-like key's value replaced by the mask, else the line redacted. */
  static String mask(String line, Redactor redactor) {
    String stripped = line.stripLeading();
    if (stripped.isEmpty() || stripped.startsWith("#") || stripped.startsWith("!")) {
      return redactor.redact(line);
    }
    int eq = indexOfSeparator(line);
    if (eq > 0) {
      String key = line.substring(0, eq).strip();
      if (SECRET_KEY.matcher(key).find()) {
        return key + "=" + Redactor.MASK;
      }
    }
    return redactor.redact(line);
  }

  /** Removes the copy directory and what it holds; a missing directory is a no-op. */
  static void deleteCopy(Path dir) throws IOException {
    if (!Files.exists(dir)) {
      return;
    }
    try (Stream<Path> walk = Files.walk(dir)) {
      for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
        Files.deleteIfExists(p);
      }
    }
  }

  private static int indexOfSeparator(String line) {
    int eq = line.indexOf('=');
    int colon = line.indexOf(':');
    if (eq < 0) {
      return colon;
    }
    return colon < 0 ? eq : Math.min(eq, colon);
  }
}
