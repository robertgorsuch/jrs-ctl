package com.jaspersoft.jrsctl.ops.customizations;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * What {@code customizations readiness} looks for (issue #306, ADR-0061). Invariants: the Jakarta
 * list is the set of Java EE packages Jakarta EE 9 renamed from {@code javax.*} to {@code
 * jakarta.*}; the {@code javax.*} packages the JDK itself provides ({@code javax.sql}, {@code
 * javax.naming}, {@code javax.crypto}, {@code javax.xml.parsers}, {@code javax.transaction.xa},
 * {@code javax.annotation.processing} and the rest) are not in it, so a reference to them is never
 * reported; library roots are the vendor libraries and JasperReports Server's own packages whose
 * classes are looked up in the target's webapp when one is given.
 */
final class ReadinessRules {

  private ReadinessRules() {}

  /** Package prefixes (internal names) Jakarta EE 9 moved to {@code jakarta/...}. */
  static final List<String> JAKARTA_PACKAGES =
      List.of(
          "javax/servlet/",
          "javax/el/",
          "javax/websocket/",
          "javax/persistence/",
          "javax/validation/",
          "javax/inject/",
          "javax/ws/rs/",
          "javax/xml/bind/",
          "javax/xml/ws/",
          "javax/xml/soap/",
          "javax/jws/",
          "javax/activation/",
          "javax/mail/",
          "javax/jms/",
          "javax/faces/",
          "javax/ejb/",
          "javax/enterprise/",
          "javax/json/",
          "javax/interceptor/",
          "javax/decorator/",
          "javax/batch/",
          "javax/resource/",
          "javax/security/enterprise/",
          "javax/security/auth/message/",
          "javax/security/jacc/",
          "javax/annotation/security/",
          "javax/annotation/sql/",
          "javax/transaction/");

  /** The Common Annotations classes that moved; the rest of {@code javax.annotation} did not. */
  static final List<String> JAKARTA_CLASSES =
      List.of(
          "javax/annotation/PostConstruct",
          "javax/annotation/PreDestroy",
          "javax/annotation/Resource",
          "javax/annotation/Resources",
          "javax/annotation/Priority",
          "javax/annotation/ManagedBean",
          "javax/annotation/Generated");

  /** Inside a moved package, but the JDK's own: {@code javax.transaction.xa}. */
  private static final String JDK_TRANSACTION_XA = "javax/transaction/xa/";

  /** Libraries and server packages whose classes are looked up in the target's webapp. */
  static final List<String> LIBRARY_ROOTS =
      List.of(
          "org/springframework/",
          "org/hibernate/",
          "org/quartz/",
          "org/ehcache/",
          "net/sf/ehcache/",
          "org/glassfish/jersey/",
          "com/sun/jersey/",
          "com/jaspersoft/",
          "net/sf/jasperreports/");

  /** The class files of Java 17, the runtime the 10.0.0 distribution bundles. */
  static final int JAVA_17_CLASS_VERSION = 61;

  /** Whether the class named by {@code internal} moved to {@code jakarta}. */
  static boolean jakarta(String internal) {
    if (internal.startsWith(JDK_TRANSACTION_XA)) {
      return false;
    }
    for (String p : JAKARTA_PACKAGES) {
      if (internal.startsWith(p)) {
        return true;
      }
    }
    for (String c : JAKARTA_CLASSES) {
      if (internal.equals(c) || internal.startsWith(c + "$")) {
        return true;
      }
    }
    return false;
  }

  /** Whether {@code internal} lies in a library looked up in the target. */
  static boolean library(String internal) {
    for (String root : LIBRARY_ROOTS) {
      if (internal.startsWith(root)) {
        return true;
      }
    }
    return false;
  }

  /** The {@code javax.*} names of moved packages and classes in a line of text. */
  static final Pattern JAKARTA_TEXT =
      Pattern.compile(
          "\\bjavax\\.(?:(?:servlet|el|websocket|persistence|validation|inject|ws\\.rs|xml\\.bind"
              + "|xml\\.ws|xml\\.soap|jws|activation|mail|jms|faces|ejb|enterprise|json"
              + "|interceptor|decorator|batch|resource|security\\.enterprise"
              + "|security\\.auth\\.message|security\\.jacc|annotation\\.security"
              + "|annotation\\.sql|transaction(?!\\.xa\\b))\\.[A-Za-z_$][\\w$.]*"
              + "|annotation\\.(?:PostConstruct|PreDestroy|Resources?|Priority|ManagedBean"
              + "|Generated)\\b)");

  /** Java EE deployment-descriptor and schema namespaces (Jakarta EE uses jakarta.ee). */
  static final Pattern JAVA_EE_NAMESPACE =
      Pattern.compile(
          "https?://(?:java\\.sun\\.com/xml/ns/(?:j2ee|javaee)|xmlns\\.jcp\\.org/xml/ns/javaee)");

  /** Text files whose content is searched: Spring XML and other XML, JSP, tags and TLDs. */
  static boolean text(String path) {
    String p = path.toLowerCase(java.util.Locale.ROOT);
    return p.endsWith(".xml")
        || p.endsWith(".jsp")
        || p.endsWith(".jspf")
        || p.endsWith(".tag")
        || p.endsWith(".tagx")
        || p.endsWith(".tld");
  }

  /** The Java release a class-file major version belongs to (52 is Java 8, 61 is Java 17). */
  static Optional<Integer> javaRelease(int major) {
    return major >= 45 ? Optional.of(major - 44) : Optional.empty();
  }
}
