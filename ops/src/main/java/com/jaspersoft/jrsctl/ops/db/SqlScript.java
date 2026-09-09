package com.jaspersoft.jrsctl.ops.db;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Splits a SQL script into statements the way the hotfix contract defines it (spec §8.1): a
 * statement ends where a line ends with {@code ;}; lines whose first non-blank characters are
 * {@code --} are comments and dropped; blank statements are dropped. Invariant: the concatenation
 * of the returned statements, plus the removed comments and terminators, is the input; nothing is
 * reordered.
 */
public final class SqlScript {

  private SqlScript() {}

  /** Statements of {@code sqlText}, in order, without their trailing {@code ;}. */
  public static List<String> statements(String sqlText) {
    try (BufferedReader reader = new BufferedReader(new StringReader(sqlText))) {
      return statements(reader);
    } catch (IOException e) {
      throw new IllegalStateException("in-memory read failed", e);
    }
  }

  /** Streams {@code file} (UTF-8) and splits it into statements. */
  public static List<String> read(Path file) throws IOException {
    try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
      return statements(reader);
    }
  }

  /** Splits everything {@code reader} yields; the reader is not closed. */
  public static List<String> statements(BufferedReader reader) throws IOException {
    List<String> out = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    String line;
    while ((line = reader.readLine()) != null) {
      String trimmed = line.strip();
      if (trimmed.startsWith("--")) {
        continue;
      }
      if (trimmed.endsWith(";")) {
        current.append(line, 0, line.lastIndexOf(';'));
        flush(current, out);
      } else {
        current.append(line).append('\n');
      }
    }
    flush(current, out);
    return List.copyOf(out);
  }

  private static void flush(StringBuilder current, List<String> out) {
    String statement = current.toString().strip();
    current.setLength(0);
    if (!statement.isEmpty()) {
      out.add(statement);
    }
  }
}
