package com.jaspersoft.jrsctl.app.console;

import com.jaspersoft.jrsctl.app.RunService;
import com.jaspersoft.jrsctl.core.config.Config;
import com.jaspersoft.jrsctl.core.secrets.Secret;
import com.jaspersoft.jrsctl.core.secrets.SecretException;
import com.jaspersoft.jrsctl.ops.Services;
import io.javalin.Javalin;
import io.javalin.compression.CompressionStrategy;
import io.javalin.http.staticfiles.Location;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntSupplier;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.SslConnectionFactory;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The local web console: Javalin bound to {@code console.bind}/{@code console.port} (flags win),
 * static UI from the classpath at {@code /}, the API under {@code /api} (spec §13, §11.2).
 * Invariants: a non-loopback bind is refused before anything is opened unless {@code
 * console.tls.enabled} and {@code console.auth.mode: local} are both set; the per-launch token is
 * issued after the listener is up and deleted on {@link #close()}; every response carries {@code
 * Cache-Control: no-store} and a {@code default-src 'self'} content security policy; no cookie is
 * ever set; closing stops the listener, cancels live runs (waiting up to 30 s) and removes the
 * token file, and is idempotent so a shutdown hook and a normal exit may both call it.
 */
public final class ConsoleServer implements AutoCloseable {

  static final String CSP =
      "default-src 'self'; img-src 'self' data:; style-src 'self' 'unsafe-inline';"
          + " connect-src 'self'";

  /** How long a launch code is worth anything (review 4.1). */
  public static final Duration LAUNCH_CODE_TTL = Duration.ofSeconds(10);

  private static final Logger LOG = LoggerFactory.getLogger(ConsoleServer.class);

  private final Services services;
  private final ConsoleOptions options;
  private final RunService runs;
  private final OperationCatalog catalog;
  private final RunManager manager;
  private final ConcurrentMap<String, Instant> launchCodes = new ConcurrentHashMap<>();

  private Optional<Javalin> app = Optional.empty();
  private Optional<ConsoleToken> token = Optional.empty();
  private String bind = Config.Console.DEFAULT_BIND;
  private int port = Config.Console.DEFAULT_PORT;
  private boolean tls;
  private boolean closed;

  public ConsoleServer(
      Services services, ConsoleOptions options, RunService runs, OperationCatalog catalog) {
    this.services = Objects.requireNonNull(services, "services");
    this.options = Objects.requireNonNull(options, "options");
    this.runs = Objects.requireNonNull(runs, "runs");
    this.catalog = Objects.requireNonNull(catalog, "catalog");
    this.manager = new RunManager(runs);
  }

  /** Validates the bind, opens the listener and issues the token. */
  public synchronized void start() throws IOException {
    if (app.isPresent()) {
      throw new IllegalStateException("console already started");
    }
    Config.Console cfg = services.config().console();
    bind = options.bind().orElse(cfg.bind()).strip();
    int requestedPort = options.port().orElse(cfg.port());
    tls = cfg.tls().enabled();
    boolean local = cfg.auth().mode() == Config.ConsoleAuthMode.LOCAL;
    if (!loopback(bind) && !(tls && local)) {
      throw new ConsoleRefusedException(
          "refusing to bind the console to "
              + bind
              + ": a non-loopback address needs console.tls.enabled: true and"
              + " console.auth.mode: local (spec §11.2)");
    }
    Optional<byte[]> password = local ? Optional.of(operatorPassword(cfg)) : Optional.empty();
    Optional<SslContextFactory.Server> ssl =
        tls ? Optional.of(ConsoleTls.sslContextFactory(cfg.tls())) : Optional.empty();

    ConsoleToken issued =
        ConsoleToken.issue(services.home(), services.platform(), services.redactor());
    token = Optional.of(issued);
    DoctorCache doctor = new DoctorCache(services);
    // Every filter and route is registered inside the config block, before the listener opens
    // (Javalin 7 allows nothing else). Registering after start once left a window in which the
    // static UI answered but /api/* was 404 and unguarded, which a client polling from another
    // process (the phase 6 acceptance test) can hit. The port is read lazily through the holder
    // because an ephemeral port is only known once Jetty has bound it, and the server object
    // itself does not exist until the config block has run.
    AtomicReference<Javalin> holder = new AtomicReference<>();
    IntSupplier boundPort = () -> holder.get().port();
    ConsoleAuth auth = new ConsoleAuth(issued, cfg.auth().mode(), password, bind, boundPort);
    ConsoleViews views = new ConsoleViews(services, manager, doctor, bind, boundPort);
    SupportBundle bundle = new SupportBundle(services, views, doctor);
    ConsoleApi api = new ConsoleApi(this, services, runs, manager, catalog, views, doctor, bundle);
    Javalin javalin =
        Javalin.create(
            c -> {
              c.startup.showJavalinBanner = false;
              c.startup.startupWatcherEnabled = false;
              c.http.compressionStrategy = CompressionStrategy.NONE;
              c.staticFiles.add(
                  s -> {
                    s.hostedPath = "/";
                    s.directory = "/web";
                    s.location = Location.CLASSPATH;
                    s.headers = Map.of("Cache-Control", "no-store");
                  });
              ssl.ifPresent(
                  factory ->
                      c.jetty.addConnector(
                          (server, httpConfig) -> {
                            ServerConnector connector =
                                new ServerConnector(
                                    server,
                                    new SslConnectionFactory(
                                        factory, HttpVersion.HTTP_1_1.asString()),
                                    new HttpConnectionFactory(httpConfig));
                            connector.setHost(bind);
                            connector.setPort(requestedPort);
                            return connector;
                          }));
              c.routes.before(
                  ctx -> {
                    ctx.header("Cache-Control", "no-store");
                    ctx.header("Content-Security-Policy", CSP);
                    ctx.header("X-Content-Type-Options", "nosniff");
                    ctx.header("Referrer-Policy", "no-referrer");
                    ctx.header("X-Frame-Options", "DENY");
                    String path = ctx.path();
                    if (path.equals("/api/auth/launch")) {
                      // no token yet, but never from a foreign Host
                      auth.requireHost(ctx);
                    } else if (path.equals("/api") || path.startsWith("/api/")) {
                      auth.handle(ctx);
                    }
                  });
              api.register(c.routes);
            });
    holder.set(javalin);
    try {
      if (ssl.isPresent()) {
        javalin.start();
      } else {
        javalin.start(bind, requestedPort);
      }
    } catch (RuntimeException e) {
      issued.close();
      token = Optional.empty();
      throw new ConsoleRefusedException(
          "cannot listen on " + bind + ":" + requestedPort + ": " + rootMessage(e), e);
    }
    port = javalin.port();
    app = Optional.of(javalin);
    try {
      services
          .stateStore()
          .get()
          .audit("console", "console.token.issued", issued.file().toString());
    } catch (RuntimeException e) {
      LOG.warn("cannot audit token issuance: {}", e.getMessage());
    }
    LOG.info("console listening on {}:{} (tls={})", bind, port, tls);
  }

  /** The launch URL with the token in the fragment, as the front-end expects it. */
  public String url() {
    ConsoleToken t =
        token.orElseThrow(() -> new IllegalStateException("console has not been started"));
    return baseUrl() + "/#token=" + t.text();
  }

  /**
   * Issues a single-use launch code to open the browser with (review 4.1). The code, not the token,
   * is what the browser command line carries, and it is worth having for {@link #LAUNCH_CODE_TTL}
   * at most: any local account can read another account's command line, so the window in which a
   * stolen code is worth anything has to be short enough that the browser, not a watcher, wins the
   * race. Only one code is ever outstanding: issuing a second invalidates the first, so a second
   * {@code console} launch cannot leave an older code usable.
   */
  public String issueLaunchCode() {
    byte[] random = new byte[16];
    new SecureRandom().nextBytes(random);
    String code = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
    launchCodes.clear();
    launchCodes.put(code, Instant.now().plus(LAUNCH_CODE_TTL));
    return code;
  }

  /**
   * Exchanges a valid, unexpired launch code for the active console bearer token. The code is
   * consumed, so it is worth one exchange; a wrong guess consumes only itself, since clearing the
   * outstanding code on a bad guess would let any local process stop the browser launching.
   */
  public Optional<String> exchangeLaunchCode(String code) {
    if (code == null || code.isBlank()) {
      return Optional.empty();
    }
    Instant expires = launchCodes.remove(code);
    if (expires == null || Instant.now().isAfter(expires)) {
      return Optional.empty();
    }
    return token.map(ConsoleToken::text);
  }

  /** True when no launch code is outstanding. */
  public boolean launchCodeOutstanding() {
    return !launchCodes.isEmpty();
  }

  /** The launch URL with a single-use, short-TTL launch code in the fragment. */
  public String launchUrl() {
    return baseUrl() + "/#launch=" + issueLaunchCode();
  }

  /** {@code scheme://host:port} without the token. */
  public String baseUrl() {
    return (tls ? "https" : "http") + "://" + hostForUrl(bind) + ":" + port;
  }

  public int port() {
    return port;
  }

  public String bind() {
    return bind;
  }

  public boolean tls() {
    return tls;
  }

  public Optional<ConsoleToken> token() {
    return token;
  }

  public RunManager runs() {
    return manager;
  }

  @Override
  public synchronized void close() {
    if (closed) {
      return;
    }
    closed = true;
    // review 4.9: refuse new runs, cancel the live ones and wait for their compensation before
    // the listener goes away, so a browser watching a run sees the cancellation rather than a
    // dropped connection and an unexplained pending run
    manager.shutdown();
    app.ifPresent(
        a -> {
          try {
            a.stop();
          } catch (RuntimeException e) {
            LOG.warn("console listener did not stop cleanly: {}", e.getMessage());
          }
        });
    token.ifPresent(ConsoleToken::close);
    LOG.info("console stopped");
  }

  static boolean loopback(String bind) {
    String b = bind.toLowerCase(Locale.ROOT);
    if (b.equals("localhost") || b.equals("127.0.0.1") || b.equals("::1") || b.equals("[::1]")) {
      return true;
    }
    try {
      return InetAddress.getByName(b.replace("[", "").replace("]", "")).isLoopbackAddress();
    } catch (UnknownHostException e) {
      return false;
    }
  }

  static String hostForUrl(String bind) {
    String b = bind.toLowerCase(Locale.ROOT);
    if (b.equals("0.0.0.0")) {
      return "127.0.0.1";
    }
    if (b.equals("::") || b.equals("[::]") || b.equals("::1")) {
      return "[::1]";
    }
    return b.contains(":") && !b.startsWith("[") ? "[" + b + "]" : b;
  }

  private byte[] operatorPassword(Config.Console cfg) {
    var ref =
        cfg.auth()
            .passwordRef()
            .orElseThrow(
                () ->
                    new ConsoleRefusedException(
                        "console.auth.mode is local but console.auth.passwordRef is not set"));
    try (Secret secret = services.secrets().resolve(ref)) {
      char[] chars = secret.chars();
      try {
        byte[] bytes = new String(chars).getBytes(StandardCharsets.UTF_8);
        services.redactor().register(new String(chars));
        return bytes;
      } finally {
        Arrays.fill(chars, '\0');
      }
    } catch (SecretException e) {
      throw new ConsoleRefusedException(
          "cannot resolve console.auth.passwordRef: " + e.getMessage(), e);
    }
  }

  private static String rootMessage(Throwable e) {
    Throwable t = e;
    while (t.getCause() != null && t.getCause() != t) {
      t = t.getCause();
    }
    return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
  }
}
