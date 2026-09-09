package com.jaspersoft.jrsctl.core.platform;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts the HTTP connector port from Tomcat's {@code conf/server.xml} without a full XML parse.
 * Invariants: the file is read line by line; XML comments are skipped so the commented-out sample
 * connectors Tomcat ships never win; the first plain HTTP/1.1 (or NIO/APR HTTP) connector is
 * preferred, an SSL-enabled one is the fallback, AJP connectors are ignored; a malformed or missing
 * file yields empty rather than an exception because detection must never abort {@code init}.
 */
final class ServerXml {

  private static final Pattern ATTRIBUTE = Pattern.compile("(\\w+)\\s*=\\s*\"([^\"]*)\"");
  private static final String CONNECTOR_OPEN = "<Connector";
  private static final String COMMENT_OPEN = "<!--";
  private static final String COMMENT_CLOSE = "-->";

  private boolean inComment;
  private StringBuilder element;
  private Optional<Integer> plainHttp = Optional.empty();
  private Optional<Integer> sslHttp = Optional.empty();

  private ServerXml() {}

  static Optional<Integer> httpPort(Path serverXml) {
    if (!Files.isRegularFile(serverXml)) {
      return Optional.empty();
    }
    ServerXml parser = new ServerXml();
    try (BufferedReader reader = Files.newBufferedReader(serverXml, StandardCharsets.UTF_8)) {
      String line;
      while ((line = reader.readLine()) != null && parser.plainHttp.isEmpty()) {
        parser.feed(parser.stripComments(line));
      }
    } catch (IOException | RuntimeException e) {
      return Optional.empty();
    }
    return parser.plainHttp.or(() -> parser.sslHttp);
  }

  private String stripComments(String line) {
    StringBuilder kept = new StringBuilder(line.length());
    int idx = 0;
    while (idx < line.length()) {
      if (inComment) {
        int close = line.indexOf(COMMENT_CLOSE, idx);
        if (close < 0) {
          return kept.toString();
        }
        inComment = false;
        idx = close + COMMENT_CLOSE.length();
      } else {
        int open = line.indexOf(COMMENT_OPEN, idx);
        if (open < 0) {
          kept.append(line, idx, line.length());
          return kept.toString();
        }
        kept.append(line, idx, open);
        inComment = true;
        idx = open + COMMENT_OPEN.length();
      }
    }
    return kept.toString();
  }

  private void feed(String text) {
    int idx = 0;
    while (idx < text.length()) {
      if (element == null) {
        int start = text.indexOf(CONNECTOR_OPEN, idx);
        if (start < 0) {
          return;
        }
        element = new StringBuilder();
        idx = start;
      }
      int end = text.indexOf('>', idx);
      if (end < 0) {
        element.append(text, idx, text.length()).append(' ');
        return;
      }
      element.append(text, idx, end + 1);
      evaluate(element.toString());
      element = null;
      idx = end + 1;
    }
  }

  private void evaluate(String connector) {
    Map<String, String> attributes = new HashMap<>();
    Matcher m = ATTRIBUTE.matcher(connector);
    while (m.find()) {
      attributes.put(m.group(1).toLowerCase(Locale.ROOT), m.group(2));
    }
    String protocol = attributes.getOrDefault("protocol", "HTTP/1.1").toLowerCase(Locale.ROOT);
    boolean http = protocol.contains("http/1.1") || protocol.contains("http11");
    if (!http) {
      return;
    }
    Optional<Integer> port = parsePort(attributes.get("port"));
    if (port.isEmpty()) {
      return;
    }
    boolean ssl = "true".equalsIgnoreCase(attributes.getOrDefault("sslenabled", "false"));
    if (ssl) {
      if (sslHttp.isEmpty()) {
        sslHttp = port;
      }
    } else if (plainHttp.isEmpty()) {
      plainHttp = port;
    }
  }

  private static Optional<Integer> parsePort(String value) {
    if (value == null) {
      return Optional.empty();
    }
    try {
      int port = Integer.parseInt(value.trim());
      return port > 0 && port <= 65535 ? Optional.of(port) : Optional.empty();
    } catch (NumberFormatException e) {
      return Optional.empty();
    }
  }
}
