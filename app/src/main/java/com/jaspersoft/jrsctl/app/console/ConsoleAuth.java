package com.jaspersoft.jrsctl.app.console;

import com.jaspersoft.jrsctl.core.config.Config;
import io.javalin.http.Context;
import io.javalin.http.Handler;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The gate in front of every {@code /api/*} request (spec §11.2). Invariants: the {@code Host}
 * header must name {@code localhost}, {@code 127.0.0.1}, {@code [::1]} or the bound address (an
 * address of this machine when bound to a wildcard), with no port or the bound port, else 421, and
 * that check runs before authentication so a rebinding attacker learns nothing; in {@code token}
 * mode the request must carry {@code Authorization: Bearer <token>}; in {@code local} mode it must
 * carry {@code Authorization: Basic base64(<token>:<operator password>)}, so both factors travel in
 * the one header a browser can send; every comparison is constant-time; failures answer 401 with
 * {@code {"error":"token required"}} and set no cookie.
 */
public final class ConsoleAuth implements Handler {

  static final String TOKEN_REQUIRED = "token required";
  static final String HOST_REJECTED = "host header rejected";

  private final ConsoleToken token;
  private final Config.ConsoleAuthMode mode;
  private final Optional<byte[]> password;
  private final Set<String> allowedHosts;
  private final int port;

  ConsoleAuth(
      ConsoleToken token,
      Config.ConsoleAuthMode mode,
      Optional<byte[]> password,
      String bind,
      int port) {
    this.token = Objects.requireNonNull(token, "token");
    this.mode = Objects.requireNonNull(mode, "mode");
    this.password = Objects.requireNonNull(password, "password");
    this.allowedHosts = allowedHosts(bind);
    this.port = port;
    if (mode == Config.ConsoleAuthMode.LOCAL && password.isEmpty()) {
      throw new ConsoleRefusedException(
          "console.auth.mode is local but console.auth.passwordRef is not set");
    }
  }

  @Override
  public void handle(Context ctx) {
    if (!hostAllowed(ctx.header("Host"))) {
      throw new ConsoleHttpException(421, HOST_REJECTED);
    }
    if (!authorised(ctx.header("Authorization"))) {
      throw new ConsoleHttpException(401, TOKEN_REQUIRED);
    }
  }

  boolean hostAllowed(String hostHeader) {
    if (hostHeader == null || hostHeader.isBlank()) {
      return false;
    }
    String value = hostHeader.strip().toLowerCase(Locale.ROOT);
    String host;
    Optional<String> portPart;
    if (value.startsWith("[")) {
      int end = value.indexOf(']');
      if (end < 0) {
        return false;
      }
      host = value.substring(0, end + 1);
      String rest = value.substring(end + 1);
      portPart = rest.startsWith(":") ? Optional.of(rest.substring(1)) : Optional.empty();
      if (!rest.isEmpty() && portPart.isEmpty()) {
        return false;
      }
    } else {
      int colon = value.lastIndexOf(':');
      host = colon < 0 ? value : value.substring(0, colon);
      portPart = colon < 0 ? Optional.empty() : Optional.of(value.substring(colon + 1));
    }
    if (portPart.isPresent()) {
      try {
        if (Integer.parseInt(portPart.get()) != port) {
          return false;
        }
      } catch (NumberFormatException e) {
        return false;
      }
    }
    return allowedHosts.contains(host);
  }

  boolean authorised(String authorization) {
    if (authorization == null) {
      return false;
    }
    String header = authorization.strip();
    return switch (mode) {
      case TOKEN -> bearer(header).map(token::matches).orElse(false);
      case LOCAL -> basic(header);
    };
  }

  private static Optional<String> bearer(String header) {
    if (header.regionMatches(true, 0, "Bearer ", 0, 7)) {
      return Optional.of(header.substring(7).strip());
    }
    return Optional.empty();
  }

  private boolean basic(String header) {
    if (!header.regionMatches(true, 0, "Basic ", 0, 6)) {
      return false;
    }
    byte[] decoded;
    try {
      decoded = Base64.getDecoder().decode(header.substring(6).strip());
    } catch (IllegalArgumentException e) {
      return false;
    }
    String pair = new String(decoded, StandardCharsets.UTF_8);
    int colon = pair.indexOf(':');
    if (colon < 0) {
      return false;
    }
    boolean tokenOk = token.matches(pair.substring(0, colon));
    byte[] presented = pair.substring(colon + 1).getBytes(StandardCharsets.UTF_8);
    boolean passwordOk = password.map(p -> MessageDigest.isEqual(p, presented)).orElse(false);
    return tokenOk && passwordOk;
  }

  private static Set<String> allowedHosts(String bind) {
    Set<String> hosts = new HashSet<>();
    hosts.add("localhost");
    hosts.add("127.0.0.1");
    hosts.add("[::1]");
    String b = bind.strip().toLowerCase(Locale.ROOT);
    boolean wildcard = b.equals("0.0.0.0") || b.equals("::") || b.equals("[::]");
    if (!wildcard) {
      hosts.add(b.contains(":") && !b.startsWith("[") ? "[" + b + "]" : b);
    } else {
      hosts.addAll(localAddresses());
    }
    return Set.copyOf(hosts);
  }

  private static Set<String> localAddresses() {
    Set<String> out = new HashSet<>();
    try {
      for (NetworkInterface nic :
          java.util.Collections.list(NetworkInterface.getNetworkInterfaces())) {
        for (InetAddress address : java.util.Collections.list(nic.getInetAddresses())) {
          String text = address.getHostAddress().toLowerCase(Locale.ROOT);
          int scope = text.indexOf('%');
          if (scope >= 0) {
            text = text.substring(0, scope);
          }
          out.add(text.contains(":") ? "[" + text + "]" : text);
        }
      }
    } catch (SocketException e) {
      // no interfaces enumerable: only the loopback names stay allowed
    }
    try {
      out.add(InetAddress.getLocalHost().getHostName().toLowerCase(Locale.ROOT));
    } catch (java.net.UnknownHostException e) {
      // the machine has no resolvable name; addresses suffice
    }
    return out;
  }
}
