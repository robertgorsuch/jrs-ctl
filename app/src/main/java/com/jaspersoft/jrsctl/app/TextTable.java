package com.jaspersoft.jrsctl.app;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Aligned text columns for terminal output. Invariants: every column but the last is padded to the
 * widest cell (ANSI escape sequences do not count towards the width); the last column is never
 * padded so lines carry no trailing spaces; rows are rendered in insertion order.
 */
final class TextTable {

  private static final Pattern ANSI = Pattern.compile(Ansi.ESC + "\\[[0-9;]*m");
  private static final String GAP = "  ";

  private final List<String[]> rows = new ArrayList<>();

  TextTable row(String... cells) {
    rows.add(cells.clone());
    return this;
  }

  /** One rendered line per row, in order. */
  List<String> lines() {
    int columns = rows.stream().mapToInt(r -> r.length).max().orElse(0);
    int[] widths = new int[columns];
    for (String[] row : rows) {
      for (int i = 0; i < row.length; i++) {
        widths[i] = Math.max(widths[i], visibleLength(row[i]));
      }
    }
    List<String> lines = new ArrayList<>();
    for (String[] row : rows) {
      StringBuilder sb = new StringBuilder();
      for (int i = 0; i < row.length; i++) {
        if (i > 0) {
          sb.append(GAP);
        }
        sb.append(row[i]);
        if (i < row.length - 1) {
          sb.append(" ".repeat(widths[i] - visibleLength(row[i])));
        }
      }
      lines.add(sb.toString());
    }
    return lines;
  }

  static int visibleLength(String cell) {
    return ANSI.matcher(cell).replaceAll("").length();
  }
}
