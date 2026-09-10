package com.jaspersoft.jrsctl.jrs.rest;

import com.jaspersoft.jrsctl.core.config.Config;
import com.jaspersoft.jrsctl.core.json.Json;
import com.jaspersoft.jrsctl.core.redact.Redactor;
import com.jaspersoft.jrsctl.core.secrets.Secret;
import com.jaspersoft.jrsctl.core.secrets.SecretResolver;
import com.jaspersoft.jrsctl.jrs.api.JrsUnreachableException;
import java.io.IOException;
import java.io.InputStream;
import java.net.Authenticator;
import java.net.CookieManager;
import java.net.HttpCookie;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

/**
 * Thin wrapper over {@link HttpClient} for the JasperReports Server REST v2 API (spec §7.5).
 * Invariants: every request is resolved against {@code server.baseUrl} and, in {@code isolated}
 * network mode, refused with {@link IsolatedModeViolation} (after notifying the audit hook) when
 * its host differs from the base URL's host, before any bytes leave the process; every request
 * carries {@code X-Jrsctl-Correlation}; redirects are never followed, so a redirect cannot escape
 * the allowlist; no exception raised here carries an Authorization header, a password, a token or
 * an unredacted body; downloads and uploads stream through {@link HttpResponse.BodyHandlers#ofFile}
 * and {@link HttpRequest.BodyPublishers#ofFile}; the Basic header is computed once and the password
 * chars are zeroed immediately afterwards. The client is safe for use from several threads; the
 * auth mode may be switched at any time and applies to subsequent requests.
 */
public final class RestClient {

  public static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
  public static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(60);
  public static final Duration DEFAULT_DOWNLOAD_TIMEOUT = Duration.ofHours(2);
  public static final String CORRELATION_HEADER = "X-Jrsctl-Correlation";
  public static final String REMOTE_DOMAIN_HEADER = "X-REMOTE-DOMAIN";
  public static final String SESSION_COOKIE = "JSESSIONID";
  public static final String JSON = "application/json";

  private static final String REMEDIATION =
      "check that server.baseUrl is correct, that the JasperReports Server service is running and"
          + " accepting connections, and that network.mode / network.proxy match how this host"
          + " reaches the server";

  /** A completed exchange; {@code body} is empty for responses streamed to a file. */
  public record Response(int status, String body, Map<String, List<String>> headers) {

    public Response {
      Objects.requireNonNull(body, "body");
      headers = Map.copyOf(Objects.requireNonNull(headers, "headers"));
    }

    public boolean ok() {
      return status >= 200 && status < 300;
    }

    /** First value of a header, case-insensitively. */
    public Optional<String> header(String name) {
      for (Map.Entry<String, List<String>> e : headers.entrySet()) {
        if (e.getKey().equalsIgnoreCase(name) && !e.getValue().isEmpty()) {
          return Optional.of(e.getValue().get(0));
        }
      }
      return Optional.empty();
    }
  }

  private final HttpClient http;
  private final CookieManager cookies;
  private final String base;
  private final URI baseUrl;
  private final String baseHost;
  private final Config.NetworkMode mode;
  private final Consumer<String> auditHook;
  private final Redactor redactor;
  private final Duration requestTimeout;
  private final Duration downloadTimeout;
  private volatile String correlationId;
  private volatile Optional<String> basicHeader = Optional.empty();
  private volatile Optional<String> tokenParam = Optional.empty();

  private RestClient(Builder b) {
    this.baseUrl = b.baseUrl;
    this.base = stripTrailingSlash(b.baseUrl.toString());
    this.baseHost = hostOf(b.baseUrl);
    this.mode = b.mode;
    this.auditHook = b.auditHook;
    this.redactor = b.redactor;
    this.requestTimeout = b.requestTimeout;
    this.downloadTimeout = b.downloadTimeout;
    this.correlationId = b.correlationId;
    this.cookies = new CookieManager();
    HttpClient.Builder hb =
        HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER)
            .cookieHandler(cookies);
    if (b.proxyHost.isPresent()) {
      hb.proxy(
          ProxySelector.of(InetSocketAddress.createUnresolved(b.proxyHost.get(), b.proxyPort)));
      if (b.proxyUser.isPresent()) {
        hb.authenticator(proxyAuthenticator(b.proxyUser.get(), b.proxyPassword));
      }
    }
    b.trustStore.ifPresent(path -> hb.sslContext(sslContext(path, b.trustStorePassword)));
    this.http = hb.build();
  }

  // ---------------------------------------------------------------- construction

  public static Builder builder(URI baseUrl) {
    return new Builder(baseUrl);
  }

  /**
   * A builder pre-populated from {@code server.baseUrl} and the {@code network:} block; proxy and
   * trust-store passwords are resolved through {@code secrets} and registered with {@code
   * redactor}. Auth is not configured here (see {@link #useBasic}, {@link #useToken}, {@link
   * #formLogin}).
   *
   * @throws com.jaspersoft.jrsctl.core.config.ConfigException when {@code server.baseUrl} is unset
   */
  public static Builder builder(Config config, SecretResolver secrets, Redactor redactor) {
    URI baseUrl =
        config
            .server()
            .baseUrl()
            .orElseThrow(
                () ->
                    new com.jaspersoft.jrsctl.core.config.ConfigException(
                        "server.baseUrl is not set",
                        "run jrsctl init or set server.baseUrl in config.yaml"));
    Builder b = new Builder(baseUrl).redactor(redactor).networkMode(config.network().mode());
    Config.Proxy proxy = config.network().proxy();
    if (proxy.host().isPresent()) {
      Optional<Secret> pw = proxy.passwordRef().map(secrets::resolve);
      pw.ifPresent(redactor::register);
      b.proxy(proxy.host().get(), proxy.port().orElse(8080), proxy.username(), pw);
    }
    Config.TrustStore ts = config.network().trustStore();
    if (ts.path().isPresent()) {
      Optional<Secret> pw = ts.passwordRef().map(secrets::resolve);
      pw.ifPresent(redactor::register);
      b.trustStore(ts.path().get(), pw);
    }
    return b;
  }

  /** Fluent configuration; every setter has a safe default. */
  public static final class Builder {
    private final URI baseUrl;
    private Config.NetworkMode mode = Config.NetworkMode.DEFAULT;
    private Consumer<String> auditHook = s -> {};
    private Redactor redactor = Redactor.global();
    private Duration requestTimeout = DEFAULT_REQUEST_TIMEOUT;
    private Duration downloadTimeout = DEFAULT_DOWNLOAD_TIMEOUT;
    private String correlationId = "";
    private Optional<String> proxyHost = Optional.empty();
    private int proxyPort;
    private Optional<String> proxyUser = Optional.empty();
    private Optional<Secret> proxyPassword = Optional.empty();
    private Optional<Path> trustStore = Optional.empty();
    private Optional<Secret> trustStorePassword = Optional.empty();

    private Builder(URI baseUrl) {
      this.baseUrl = Objects.requireNonNull(baseUrl, "baseUrl");
      if (baseUrl.getHost() == null) {
        throw new IllegalArgumentException("server.baseUrl has no host: " + baseUrl);
      }
    }

    public Builder networkMode(Config.NetworkMode mode) {
      this.mode = Objects.requireNonNull(mode, "mode");
      return this;
    }

    /** Receives one line per refused request in isolated mode. */
    public Builder auditHook(Consumer<String> hook) {
      this.auditHook = Objects.requireNonNull(hook, "hook");
      return this;
    }

    public Builder redactor(Redactor redactor) {
      this.redactor = Objects.requireNonNull(redactor, "redactor");
      return this;
    }

    public Builder requestTimeout(Duration timeout) {
      this.requestTimeout = Objects.requireNonNull(timeout, "timeout");
      return this;
    }

    public Builder downloadTimeout(Duration timeout) {
      this.downloadTimeout = Objects.requireNonNull(timeout, "timeout");
      return this;
    }

    public Builder correlationId(String id) {
      this.correlationId = Objects.requireNonNull(id, "id");
      return this;
    }

    /** The secret, when present, must stay open for the life of the client. */
    public Builder proxy(
        String host, int port, Optional<String> username, Optional<Secret> password) {
      this.proxyHost = Optional.of(Objects.requireNonNull(host, "host"));
      this.proxyPort = port;
      this.proxyUser = Objects.requireNonNull(username, "username");
      this.proxyPassword = Objects.requireNonNull(password, "password");
      return this;
    }

    /** A JKS or PKCS#12 file holding the CA certificates to trust; read once at build time. */
    public Builder trustStore(Path path, Optional<Secret> password) {
      this.trustStore = Optional.of(Objects.requireNonNull(path, "path"));
      this.trustStorePassword = Objects.requireNonNull(password, "password");
      return this;
    }

    public RestClient build() {
      return new RestClient(this);
    }
  }

  // ---------------------------------------------------------------- auth

  /** Sends {@code Authorization: Basic} on every subsequent request. */
  public void useBasic(String username, Secret password) {
    Objects.requireNonNull(username, "username");
    char[] pw = password.chars();
    byte[] raw;
    try {
      char[] joined = new char[username.length() + 1 + pw.length];
      username.getChars(0, username.length(), joined, 0);
      joined[username.length()] = ':';
      System.arraycopy(pw, 0, joined, username.length() + 1, pw.length);
      ByteBuffer encoded = StandardCharsets.UTF_8.encode(CharBuffer.wrap(joined));
      raw = new byte[encoded.remaining()];
      encoded.get(raw);
      encoded.rewind();
      while (encoded.hasRemaining()) {
        encoded.put((byte) 0);
      }
      Arrays.fill(joined, '\0');
    } finally {
      Arrays.fill(pw, '\0');
    }
    String header = "Basic " + Base64.getEncoder().encodeToString(raw);
    Arrays.fill(raw, (byte) 0);
    redactor.register(header);
    redactor.register(password);
    this.tokenParam = Optional.empty();
    this.basicHeader = Optional.of(header);
  }

  /** Appends {@code pp=<token>} to every subsequent request (token / pre-authentication). */
  public void useToken(Secret token) {
    char[] chars = token.chars();
    String encoded;
    try {
      encoded = URLEncoder.encode(new String(chars), StandardCharsets.UTF_8);
    } finally {
      Arrays.fill(chars, '\0');
    }
    redactor.register(token);
    redactor.register(encoded);
    this.basicHeader = Optional.empty();
    this.tokenParam = Optional.of(encoded);
  }

  /** Drops the Basic header and token; cookies are kept. */
  public void clearAuth() {
    this.basicHeader = Optional.empty();
    this.tokenParam = Optional.empty();
  }

  /**
   * Posts {@code j_username}/{@code j_password} form fields to {@code path} ({@code
   * /j_spring_security_check} or {@code /rest_v2/login}); a successful reply sets the session
   * cookie, which the cookie manager then sends on every request.
   */
  public Response formLogin(String path, String username, Secret password) {
    char[] pw = password.chars();
    String body;
    try {
      body =
          "j_username="
              + URLEncoder.encode(username, StandardCharsets.UTF_8)
              + "&j_password="
              + URLEncoder.encode(new String(pw), StandardCharsets.UTF_8);
    } finally {
      Arrays.fill(pw, '\0');
    }
    redactor.register(password);
    Response r =
        send(
            "POST",
            path,
            JSON,
            Optional.of("application/x-www-form-urlencoded"),
            HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8),
            requestTimeout,
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    sessionCookie().ifPresent(redactor::register);
    return r;
  }

  /** The current {@code JSESSIONID} value held by the cookie manager, if any. */
  public Optional<String> sessionCookie() {
    for (HttpCookie c : cookies.getCookieStore().getCookies()) {
      if (SESSION_COOKIE.equalsIgnoreCase(c.getName())) {
        return Optional.of(c.getValue());
      }
    }
    return Optional.empty();
  }

  public void clearSession() {
    cookies.getCookieStore().removeAll();
  }

  public void correlationId(String runId) {
    this.correlationId = Objects.requireNonNull(runId, "runId");
  }

  public String correlationId() {
    return correlationId;
  }

  public URI baseUrl() {
    return baseUrl;
  }

  public Config.NetworkMode networkMode() {
    return mode;
  }

  // ---------------------------------------------------------------- requests

  public Response get(String path) {
    return get(path, JSON);
  }

  public Response get(String path, String accept) {
    return send(
        "GET",
        path,
        accept,
        Optional.empty(),
        HttpRequest.BodyPublishers.noBody(),
        requestTimeout,
        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  /**
   * Streams a 2xx body into {@code target} (replacing it); a non-2xx body is returned as text and
   * nothing is written.
   */
  public Response getToFile(String path, String accept, Path target) {
    Objects.requireNonNull(target, "target");
    return send(
        "GET",
        path,
        accept,
        Optional.empty(),
        HttpRequest.BodyPublishers.noBody(),
        downloadTimeout,
        fileOrText(target));
  }

  /** Serialises {@code body} with the shared Jackson mapper. */
  public Response postJson(String path, Object body) {
    return send(
        "POST",
        path,
        JSON,
        Optional.of(JSON),
        HttpRequest.BodyPublishers.ofString(Json.write(body), StandardCharsets.UTF_8),
        requestTimeout,
        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  /** Streams {@code file} as the request body. */
  public Response postBytesFromFile(String path, Path file, String contentType) {
    Objects.requireNonNull(file, "file");
    HttpRequest.BodyPublisher publisher;
    try {
      publisher = HttpRequest.BodyPublishers.ofFile(file);
    } catch (IOException e) {
      throw new RestException(
          0, "POST", withoutQuery(path), "cannot read upload " + file + ": " + e.getMessage(), e);
    }
    return send(
        "POST",
        path,
        JSON,
        Optional.of(contentType),
        publisher,
        downloadTimeout,
        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  /** Posts a pre-serialised body with an explicit media type (JRS resource descriptors). */
  public Response post(String path, String contentType, String body) {
    return send(
        "POST",
        path,
        JSON,
        Optional.of(contentType),
        HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8),
        requestTimeout,
        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  public Response put(String path, String contentType, String body) {
    return send(
        "PUT",
        path,
        JSON,
        Optional.of(contentType),
        HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8),
        requestTimeout,
        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  /** Streams {@code file} as the body of a PUT (used for large resource descriptors). */
  public Response putFromFile(String path, String contentType, Path file) {
    HttpRequest.BodyPublisher publisher;
    try {
      publisher = HttpRequest.BodyPublishers.ofFile(file);
    } catch (IOException e) {
      throw new RestException(
          0, "PUT", withoutQuery(path), "cannot read upload " + file + ": " + e.getMessage(), e);
    }
    return send(
        "PUT",
        path,
        JSON,
        Optional.of(contentType),
        publisher,
        downloadTimeout,
        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  public Response delete(String path) {
    return send(
        "DELETE",
        path,
        JSON,
        Optional.empty(),
        HttpRequest.BodyPublishers.noBody(),
        requestTimeout,
        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  /**
   * Raises {@link RestException} for a non-2xx response; the message carries a redacted, capped
   * body excerpt.
   */
  public Response require2xx(Response r, String method, String path) {
    if (r.ok()) {
      return r;
    }
    throw new RestException(
        r.status(),
        method,
        withoutQuery(path),
        "HTTP " + r.status() + " from " + method + " " + withoutQuery(path) + excerpt(r.body()));
  }

  // ---------------------------------------------------------------- helpers

  /** Percent-encodes each segment of a repository path such as {@code /public/My Report}. */
  public static String encodePath(String path) {
    StringBuilder sb = new StringBuilder();
    for (String seg : path.split("/", -1)) {
      if (sb.length() > 0 || path.startsWith("/")) {
        sb.append('/');
      }
      sb.append(URLEncoder.encode(seg, StandardCharsets.UTF_8).replace("+", "%20"));
    }
    String out = sb.toString();
    return path.startsWith("/") && out.startsWith("//") ? out.substring(1) : out;
  }

  public static String encodeQuery(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  static URI withoutQuery(URI uri) {
    try {
      return new URI(uri.getScheme(), uri.getAuthority(), uri.getPath(), null, null);
    } catch (URISyntaxException e) {
      return URI.create(uri.getScheme() + "://" + uri.getAuthority());
    }
  }

  static String withoutQuery(String path) {
    int q = path.indexOf('?');
    return q < 0 ? path : path.substring(0, q);
  }

  private Response send(
      String method,
      String path,
      String accept,
      Optional<String> contentType,
      HttpRequest.BodyPublisher body,
      Duration timeout,
      HttpResponse.BodyHandler<String> handler) {
    URI uri = resolve(path);
    enforceAllowlist(uri);
    HttpRequest.Builder rb =
        HttpRequest.newBuilder(uri)
            .timeout(timeout)
            .header("Accept", accept)
            .header(REMOTE_DOMAIN_HEADER, "1")
            .header(CORRELATION_HEADER, correlationId.isEmpty() ? "none" : correlationId)
            .method(method, body);
    contentType.ifPresent(ct -> rb.header("Content-Type", ct));
    basicHeader.ifPresent(h -> rb.header("Authorization", h));
    HttpResponse<String> response;
    try {
      response = http.send(rb.build(), handler);
    } catch (IOException e) {
      throw unreachable(method, uri, e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new JrsUnreachableException(
          withoutQuery(uri), "interrupted while waiting for " + method + " " + safe(uri), "", e);
    }
    return new Response(response.statusCode(), response.body(), response.headers().map());
  }

  private URI resolve(String path) {
    Objects.requireNonNull(path, "path");
    String target = isAbsolute(path) ? path : base + (path.startsWith("/") ? path : "/" + path);
    Optional<String> token = tokenParam;
    if (token.isPresent()) {
      target = target + (target.contains("?") ? "&" : "?") + "pp=" + token.get();
    }
    try {
      return new URI(target);
    } catch (URISyntaxException e) {
      throw new RestException(
          0, "?", withoutQuery(path), "malformed request path " + withoutQuery(path), e);
    }
  }

  private void enforceAllowlist(URI uri) {
    if (mode == Config.NetworkMode.ISOLATED && !baseHost.equalsIgnoreCase(hostOf(uri))) {
      IsolatedModeViolation violation = new IsolatedModeViolation(uri, baseHost);
      auditHook.accept("FAIL isolated-mode refused request to " + safe(uri));
      throw violation;
    }
  }

  private JrsUnreachableException unreachable(String method, URI uri, IOException e) {
    String detail = e.getClass().getSimpleName();
    if (e.getMessage() != null && !e.getMessage().isBlank()) {
      detail += ": " + redactor.redact(e.getMessage());
    }
    return new JrsUnreachableException(
        withoutQuery(uri),
        "cannot reach " + method + " " + safe(uri) + " (" + detail + ")",
        REMEDIATION,
        e);
  }

  private String excerpt(String body) {
    if (body == null || body.isBlank()) {
      return "";
    }
    String one = body.strip().replaceAll("\\s+", " ");
    if (one.length() > RestException.EXCERPT_LIMIT) {
      one = one.substring(0, RestException.EXCERPT_LIMIT) + "...";
    }
    return ": " + redactor.redact(one);
  }

  private static String safe(URI uri) {
    return withoutQuery(uri).toString();
  }

  private static boolean isAbsolute(String path) {
    String lower = path.toLowerCase(Locale.ROOT);
    return lower.startsWith("http://") || lower.startsWith("https://");
  }

  private static String hostOf(URI uri) {
    return uri.getHost() == null ? "" : uri.getHost();
  }

  private static String stripTrailingSlash(String s) {
    return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
  }

  private static HttpResponse.BodyHandler<String> fileOrText(Path target) {
    return info ->
        info.statusCode() >= 200 && info.statusCode() < 300
            ? HttpResponse.BodySubscribers.mapping(
                HttpResponse.BodySubscribers.ofFile(target), p -> "")
            : HttpResponse.BodySubscribers.ofString(StandardCharsets.UTF_8);
  }

  private static Authenticator proxyAuthenticator(String user, Optional<Secret> password) {
    return new Authenticator() {
      @Override
      protected PasswordAuthentication getPasswordAuthentication() {
        if (getRequestorType() != RequestorType.PROXY) {
          return null;
        }
        char[] chars = password.map(Secret::chars).orElseGet(() -> new char[0]);
        try {
          return new PasswordAuthentication(user, chars);
        } finally {
          Arrays.fill(chars, '\0');
        }
      }
    };
  }

  private static SSLContext sslContext(Path trustStore, Optional<Secret> password) {
    char[] pw = password.map(Secret::chars).orElse(null);
    try (InputStream in = Files.newInputStream(trustStore)) {
      KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
      ks.load(in, pw);
      TrustManagerFactory tmf =
          TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
      tmf.init(ks);
      SSLContext ctx = SSLContext.getInstance("TLS");
      ctx.init(null, tmf.getTrustManagers(), null);
      return ctx;
    } catch (IOException | GeneralSecurityException e) {
      throw new IllegalStateException(
          "cannot load network.trustStore " + trustStore + ": " + e.getClass().getSimpleName(), e);
    } finally {
      if (pw != null) {
        Arrays.fill(pw, '\0');
      }
    }
  }
}
