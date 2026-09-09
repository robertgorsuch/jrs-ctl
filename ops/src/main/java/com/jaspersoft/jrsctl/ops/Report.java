package com.jaspersoft.jrsctl.ops;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * An ordered list of {@link ReportItem}s with counts (spec §12). Invariants: items are sorted FAIL,
 * WARN, PASS, SKIP with the original order preserved inside each group (a stable sort), so the
 * operator reads the problems first; the list is immutable; {@link #ok()} is true exactly when no
 * item is a FAIL.
 */
public record Report(List<ReportItem> items, Counts counts) {

  /** How many items carry each status. */
  public record Counts(int pass, int warn, int fail, int skip) {
    static Counts of(List<ReportItem> items) {
      int pass = 0;
      int warn = 0;
      int fail = 0;
      int skip = 0;
      for (ReportItem item : items) {
        switch (item.status()) {
          case PASS -> pass++;
          case WARN -> warn++;
          case FAIL -> fail++;
          case SKIP -> skip++;
        }
      }
      return new Counts(pass, warn, fail, skip);
    }

    /** {@code "N pass M warn K fail J skip"}. */
    public String summary() {
      return pass + " pass " + warn + " warn " + fail + " fail " + skip + " skip";
    }
  }

  public Report {
    Objects.requireNonNull(items, "items");
    Objects.requireNonNull(counts, "counts");
    items = List.copyOf(items);
  }

  /** Sorts {@code items} by status rank and computes the counts. */
  public static Report of(List<ReportItem> items) {
    List<ReportItem> sorted = new ArrayList<>(items);
    sorted.sort(Comparator.comparingInt(i -> i.status().rank()));
    return new Report(sorted, Counts.of(sorted));
  }

  public boolean ok() {
    return counts.fail() == 0;
  }
}
