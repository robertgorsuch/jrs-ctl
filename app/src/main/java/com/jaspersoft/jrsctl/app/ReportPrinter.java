package com.jaspersoft.jrsctl.app;

import com.jaspersoft.jrsctl.core.redact.Redactor;
import com.jaspersoft.jrsctl.ops.Report;
import com.jaspersoft.jrsctl.ops.ReportItem;
import java.io.PrintWriter;
import java.util.List;

/**
 * Text rendering shared by {@code doctor} and {@code smoke}: one aligned row per item ({@code
 * status name detail}), the remediation indented under every non-PASS item, then the {@code N pass
 * M warn K fail J skip} summary. Invariant: every line passes through the redactor before it is
 * written.
 */
final class ReportPrinter {

  private static final String INDENT = "        -> ";

  private ReportPrinter() {}

  static void print(
      PrintWriter out, List<ReportItem> items, Report.Counts counts, Ansi ansi, Redactor redactor) {
    TextTable table = new TextTable();
    for (ReportItem item : items) {
      table.row(ansi.status(item.status()), item.name(), item.detail());
    }
    List<String> lines = table.lines();
    for (int i = 0; i < items.size(); i++) {
      out.println(redactor.redact(lines.get(i)));
      ReportItem item = items.get(i);
      if (item.status() != ReportItem.Status.PASS && !item.remediation().isEmpty()) {
        out.println(redactor.redact(ansi.dim(INDENT + item.remediation())));
      }
    }
    out.println(redactor.redact(counts.summary()));
    out.flush();
  }
}
