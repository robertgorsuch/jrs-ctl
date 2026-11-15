package com.jaspersoft.jrsctl.app;

import com.jaspersoft.jrsctl.core.redact.Redactor;
import com.jaspersoft.jrsctl.ops.ReportItem;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * Writes a report as JUnit XML for a CI pipeline's test view (issue #308, ADR-0058). Invariants:
 * one {@code testcase} per report item, in report order, named by the item's stable name; FAIL is a
 * {@code failure}, SKIP is {@code skipped}, WARN is a {@code failure} only when {@code --fail-on
 * warn} is set and otherwise passes with its text in {@code system-out}; every text passes the
 * redactor and is XML-escaped, with characters XML 1.0 cannot carry dropped; the file is replaced
 * through a sibling temporary file and one move, so a reader never sees half a document. No XML
 * library is used: the format needs only elements, attributes and escaping.
 */
final class JunitReport {

  private JunitReport() {}

  /** Writes {@code items} as the test suite {@code jrsctl <command>} to {@code file}. */
  static void write(
      Path file, String command, List<ReportItem> items, boolean warnFails, Redactor redactor)
      throws IOException {
    Path target = file.toAbsolutePath().normalize();
    Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
    Files.writeString(tmp, render(command, items, warnFails, redactor), StandardCharsets.UTF_8);
    try {
      Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    } catch (AtomicMoveNotSupportedException e) {
      Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
    }
  }

  static String render(
      String command, List<ReportItem> items, boolean warnFails, Redactor redactor) {
    int failures = 0;
    int skipped = 0;
    for (ReportItem item : items) {
      switch (item.status()) {
        case FAIL -> failures++;
        case WARN -> failures += warnFails ? 1 : 0;
        case SKIP -> skipped++;
        case PASS -> {}
      }
    }
    String suite = "jrsctl " + command;
    String classname = "jrsctl." + command.replace(' ', '.');
    StringBuilder xml = new StringBuilder();
    xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
    xml.append("<testsuites name=\"jrsctl\" tests=\"")
        .append(items.size())
        .append("\" failures=\"")
        .append(failures)
        .append("\" errors=\"0\" skipped=\"")
        .append(skipped)
        .append("\">\n");
    xml.append("  <testsuite name=\"")
        .append(escape(suite))
        .append("\" tests=\"")
        .append(items.size())
        .append("\" failures=\"")
        .append(failures)
        .append("\" errors=\"0\" skipped=\"")
        .append(skipped)
        .append("\">\n");
    for (ReportItem item : items) {
      String detail = redactor.redact(item.detail());
      String remediation = redactor.redact(item.remediation());
      String text = remediation.isEmpty() ? detail : detail + "\n" + remediation;
      xml.append("    <testcase classname=\"")
          .append(escape(classname))
          .append("\" name=\"")
          .append(escape(item.name()))
          .append("\" time=\"0\"");
      switch (item.status()) {
        case PASS -> xml.append(">\n").append(systemOut(text));
        case FAIL -> xml.append(">\n").append(failure("FAIL", detail, text));
        case WARN ->
            xml.append(">\n")
                .append(warnFails ? failure("WARN", detail, text) : systemOut("WARN: " + text));
        case SKIP ->
            xml.append(">\n")
                .append("      <skipped message=\"")
                .append(escape(detail))
                .append("\"/>\n");
      }
      xml.append("    </testcase>\n");
    }
    xml.append("  </testsuite>\n");
    xml.append("</testsuites>\n");
    return xml.toString();
  }

  private static String failure(String type, String message, String text) {
    return "      <failure type=\""
        + type
        + "\" message=\""
        + escape(message)
        + "\">"
        + escape(text)
        + "</failure>\n";
  }

  private static String systemOut(String text) {
    return "      <system-out>" + escape(text) + "</system-out>\n";
  }

  /** Escapes the five XML specials and drops characters XML 1.0 does not allow. */
  static String escape(String s) {
    StringBuilder b = new StringBuilder(s.length());
    for (int i = 0; i < s.length(); ) {
      int c = s.codePointAt(i);
      i += Character.charCount(c);
      switch (c) {
        case '&' -> b.append("&amp;");
        case '<' -> b.append("&lt;");
        case '>' -> b.append("&gt;");
        case '"' -> b.append("&quot;");
        case '\'' -> b.append("&apos;");
        default -> {
          boolean allowed =
              c == 0x9
                  || c == 0xA
                  || c == 0xD
                  || (c >= 0x20 && c <= 0xD7FF)
                  || (c >= 0xE000 && c <= 0xFFFD)
                  || (c >= 0x10000 && c <= 0x10FFFF);
          if (allowed) {
            b.appendCodePoint(c);
          }
        }
      }
    }
    return b.toString();
  }
}
