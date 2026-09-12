package com.jaspersoft.jrsctl.app;

import com.jaspersoft.jrsctl.app.console.ConsoleOptions;
import com.jaspersoft.jrsctl.app.console.ConsoleServer;
import com.jaspersoft.jrsctl.core.state.StateStore;
import com.jaspersoft.jrsctl.ops.RunService;
import com.jaspersoft.jrsctl.ops.Services;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;
import java.util.Optional;

/**
 * A real console listener on a free port over a temporary home, for tests that need the HTTP
 * surface rather than the view objects. Invariants: the caller owns the home directory and the
 * clock; {@link #close()} stops the listener and the bootstrap in that order; every request carries
 * the per-launch bearer token, so a 401 from this fixture is a product bug.
 */
final class ConsoleFixture implements AutoCloseable {

  private final HttpClient http = HttpClient.newHttpClient();
  private final Bootstrap boot;
  private final ConsoleServer server;
  private final String token;

  private ConsoleFixture(Bootstrap boot, ConsoleServer server, String token) {
    this.boot = boot;
    this.server = server;
    this.token = token;
  }

  /** Writes a minimal configuration into {@code home} and starts a console over it. */
  static ConsoleFixture start(Path home, Clock clock) throws IOException {
    Files.createDirectories(home);
    Files.writeString(
        home.resolve("config.yaml"),
        """
        server:
          baseUrl: http://localhost:8089/jasperserver-pro
          webappName: jasperserver-pro
          installDir: %s
          auth:
            mode: basic
            username: jasperadmin
            passwordRef: env:JRS_PASSWORD
        service:
          kind: manual
        """
            .formatted(home.toString().replace("\\", "/")),
        StandardCharsets.UTF_8);
    Env.override(Map.of("JRS_PASSWORD", "jasperadmin"));
    GlobalOptions global = new GlobalOptions();
    global.home = home;
    global.nonInteractive = true;
    Bootstrap boot = Bootstrap.open(global, Env.vars(), clock);
    Services services = boot.services();
    ConsoleServer server =
        new ConsoleServer(
            services,
            new ConsoleOptions(Optional.empty(), Optional.of(0)),
            new RunService(services),
            ConsoleCommand.catalog(services));
    server.start();
    return new ConsoleFixture(boot, server, server.token().orElseThrow().text());
  }

  /** The same state store the running console reads and writes, for a test to seed rows into. */
  StateStore store() {
    return boot.services().stateStore().get();
  }

  HttpResponse<String> get(String path) throws Exception {
    return http.send(request(path).GET().build(), HttpResponse.BodyHandlers.ofString());
  }

  HttpResponse<String> post(String path, String body) throws Exception {
    return http.send(
        request(path)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private HttpRequest.Builder request(String path) {
    return HttpRequest.newBuilder(URI.create(server.baseUrl() + path))
        .header("Authorization", "Bearer " + token);
  }

  @Override
  public void close() {
    server.close();
    boot.close();
    Env.reset();
  }
}
