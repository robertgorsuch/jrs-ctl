package com.jaspersoft.jrsctl.ops.exim;

import com.jaspersoft.jrsctl.ops.Report;
import com.jaspersoft.jrsctl.ops.ReportItem;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The {@code import verify} result (issue #262, spec §9.4): one item per kind the archive holds,
 * what the archive counted, and what the server lacks. Invariants: items are sorted FAIL, WARN,
 * PASS, SKIP; {@code exitCode} is 0 when nothing failed and 2 otherwise, like {@code doctor}; the
 * report is produced without mutating anything; {@code missing} has a list for every kind, empty
 * when nothing is missing, so a consumer can key on it.
 */
public record ImportVerifyReport(
    List<ReportItem> items,
    Report.Counts counts,
    int exitCode,
    String archive,
    ArchiveCounts archiveCounts,
    Map<String, List<String>> missing) {

  public static final int EXIT_OK = 0;
  public static final int EXIT_FAILED = 2;

  /** What the archive holds, by kind. */
  public record ArchiveCounts(
      int organizations, int users, int roles, int folders, int resources) {}

  public ImportVerifyReport {
    Objects.requireNonNull(items, "items");
    Objects.requireNonNull(counts, "counts");
    Objects.requireNonNull(archive, "archive");
    Objects.requireNonNull(archiveCounts, "archiveCounts");
    items = List.copyOf(items);
    missing = Map.copyOf(missing);
  }

  static ImportVerifyReport of(
      List<ReportItem> unsorted,
      String archive,
      ArchiveCounts archiveCounts,
      Map<String, List<String>> missing) {
    Report report = Report.of(unsorted);
    return new ImportVerifyReport(
        report.items(),
        report.counts(),
        report.ok() ? EXIT_OK : EXIT_FAILED,
        archive,
        archiveCounts,
        missing);
  }

  public boolean ok() {
    return counts.fail() == 0;
  }
}
