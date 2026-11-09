package com.jaspersoft.jrsctl.ops;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.semver4j.Semver;

/**
 * The two analytics JNDI resources a 9.0 or later webapp's {@code META-INF/context.xml} must
 * declare "even if the feature is disabled": introduced by the 9.0 release notes (p.15) and still
 * mandatory in 10.x (upgrade guide 10.1 p.93). Shared by the upgrade's verify step and the {@code
 * doctor} item (issues #108, #268). Invariants: pure and read-only; a context file that is missing
 * or unreadable counts as declaring nothing, so the caller warns about both resources rather than
 * failing; the version judgement coerces two-component versions ({@code 9.0} is {@code 9.0.0}) and
 * treats an unparseable version as not requiring the resources.
 */
public final class AnalyticsJndi {

  public static final List<String> RESOURCES =
      List.of("jdbc/jasperserverSystemAnalytics", "jdbc/jasperserverAuditAnalytics");

  /** The first release line that requires both resources. */
  public static final Semver REQUIRED_FROM = new Semver("9.0.0");

  /** Where the vendor says they stay mandatory, for messages. */
  public static final String GUIDE = "upgrade guide 10.1 p.93";

  private AnalyticsJndi() {}

  /** Whether a server of this version must declare both resources. */
  public static boolean requiredFor(String version) {
    Semver v = Semver.coerce(version);
    return v != null && v.isGreaterThanOrEqualTo(REQUIRED_FROM);
  }

  /** The resources missing from a {@code META-INF/context.xml}; all when unreadable. */
  public static List<String> missing(Path contextXml) {
    if (!Files.isRegularFile(contextXml)) {
      return RESOURCES;
    }
    String text;
    try {
      text = Files.readString(contextXml, StandardCharsets.ISO_8859_1);
    } catch (IOException e) {
      return RESOURCES;
    }
    return RESOURCES.stream().filter(name -> !text.contains(name)).toList();
  }
}
