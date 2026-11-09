package com.jaspersoft.jrsctl.ops.passwords;

import com.jaspersoft.jrsctl.ops.Report;
import com.jaspersoft.jrsctl.ops.ReportItem;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The {@code passwords status} result (spec §10.6): the vendor tools, the deployed webapp's
 * password-storage settings and the distribution the vendor's {@code detect-password-strategy}
 * reports, in the {@code doctor} layout. Invariants: items are sorted FAIL, WARN, PASS, SKIP;
 * {@code exitCode} is 0 when nothing failed and 2 otherwise; {@code counts} of passwords is present
 * only when the utility printed them; produced without mutating anything.
 */
public record PasswordStatusReport(
    List<ReportItem> items,
    Report.Counts counts,
    int exitCode,
    Optional<PasswordCounts> passwords,
    Optional<String> strategy) {

  public static final int EXIT_OK = 0;
  public static final int EXIT_FAILED = 2;

  public PasswordStatusReport {
    Objects.requireNonNull(items, "items");
    Objects.requireNonNull(counts, "counts");
    Objects.requireNonNull(passwords, "passwords");
    Objects.requireNonNull(strategy, "strategy");
    items = List.copyOf(items);
  }

  static PasswordStatusReport of(
      List<ReportItem> unsorted, Optional<PasswordCounts> passwords, Optional<String> strategy) {
    Report report = Report.of(unsorted);
    return new PasswordStatusReport(
        report.items(), report.counts(), report.ok() ? EXIT_OK : EXIT_FAILED, passwords, strategy);
  }

  public boolean ok() {
    return counts.fail() == 0;
  }
}
