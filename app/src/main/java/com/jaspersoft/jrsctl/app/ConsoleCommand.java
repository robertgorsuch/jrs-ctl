package com.jaspersoft.jrsctl.app;

import com.jaspersoft.jrsctl.app.console.ConsoleOptions;
import com.jaspersoft.jrsctl.app.console.ConsoleRefusedException;
import com.jaspersoft.jrsctl.app.console.ConsoleServer;
import com.jaspersoft.jrsctl.app.console.OperationCatalog;
import com.jaspersoft.jrsctl.app.console.PlanBuilder;
import com.jaspersoft.jrsctl.core.platform.Platform;
import com.jaspersoft.jrsctl.ops.PlanRegistry;
import com.jaspersoft.jrsctl.ops.RunService;
import com.jaspersoft.jrsctl.ops.Services;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.URI;
import java.nio.charset.Charset;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/**
 * {@code jrsctl console [--bind <addr>] [--port <n>] [--open|--no-open]} (spec §13, §11.2): starts
 * the local web console and serves it until Ctrl-C or {@code stop} on standard input. Invariants:
 * the per-launch token URL is printed exactly once, unredacted, to the command's own output and
 * nowhere else (as {@code {"url": ...}} with {@code --json}); the browser is opened only when a
 * terminal is present and {@code --no-open} was not given, through the platform's process runner
 * (no shell); a refused bind exits 2 before anything listens; the shutdown hook stops the listener,
 * cancels live runs and deletes the token file; console-started runs are non-interactive, so no
 * step ever waits for a terminal prompt.
 */
@Command(
    name = "console",
    mixinStandardHelpOptions = true,
    exitCodeOnInvalidInput = ExitCodes.USAGE,
    description = "Serve the local web console until Ctrl-C (or 'stop' on standard input).")
public final class ConsoleCommand implements Callable<Integer> {

  private static final Logger LOG = LoggerFactory.getLogger(ConsoleCommand.class);

  @Spec CommandSpec spec;
  @Mixin GlobalOptions global;

  @Option(
      names = "--bind",
      paramLabel = "<addr>",
      description = "Address to listen on (default: console.bind, 127.0.0.1).")
  String bind;

  @Option(
      names = "--port",
      paramLabel = "<n>",
      description = "Port to listen on; 0 picks a free port (default: console.port, 7420).")
  Integer port;

  @Option(
      names = "--open",
      negatable = true,
      description =
          "Open the console in the default browser (default: when a terminal is present).")
  Boolean open;

  @Override
  public Integer call() {
    PrintWriter out = spec.commandLine().getOut();
    PrintWriter err = spec.commandLine().getErr();
    global.nonInteractive = true;
    try (Bootstrap boot = Bootstrap.open(global, Env.vars(), Clock.systemUTC())) {
      Services services = boot.services();
      RunService runs = new RunService(services);
      ConsoleOptions options =
          new ConsoleOptions(Optional.ofNullable(bind), Optional.ofNullable(port));
      try (ConsoleServer server = new ConsoleServer(services, options, runs, catalog(services))) {
        try {
          server.start();
        } catch (ConsoleRefusedException e) {
          return ExitCodes.fail(
              out,
              err,
              global.json(),
              ExitCodes.PRECHECK_FAILED,
              e.getClass().getSimpleName(),
              e.getMessage(),
              Optional.empty(),
              Map.of());
        } catch (IOException e) {
          return ExitCodes.fail(
              out,
              err,
              global.json(),
              ExitCodes.PRECHECK_FAILED,
              "cannot start the console: " + e.getMessage());
        }
        if (global.json()) {
          // the token is registered with the redactor, so this one legible copy must bypass
          // JsonOut.print (exactly as the text line below prints server.url() directly)
          out.println(JsonOut.write(Map.of("url", server.url())));
          out.flush();
        } else {
          out.println("Console: " + server.url());
          out.println("Press Ctrl-C to stop, or type 'stop' and Enter.");
          out.flush();
        }
        if (shouldOpenBrowser(services, server, out)) {
          openBrowser(services.platform(), server.launchUrl());
        }
        // review 4.9: the hook also closes the bootstrap, so Ctrl-C closes the state store and
        // releases the run lock instead of leaving them to the exiting JVM
        // #57: after closing, halt with 0 rather than let the JVM exit with the signal's 130
        Thread hook =
            new Thread(
                new ConsoleShutdown(
                    List.of(server::close, boot::close),
                    code -> {
                      out.println("stopping the console");
                      out.flush();
                      Runtime.getRuntime().halt(code);
                    }),
                "jrsctl-console-shutdown");
        Runtime.getRuntime().addShutdownHook(hook);
        try {
          waitForStop();
        } finally {
          try {
            Runtime.getRuntime().removeShutdownHook(hook);
          } catch (IllegalStateException shuttingDown) {
            // the JVM is already going down; the hook is doing the closing
          }
        }
        if (!global.json()) {
          out.println("stopping the console");
          out.flush();
        }
      }
      return ExitCodes.SUCCESS;
    }
  }

  /** The console's plan builders: the CLI registry plus the hotfix operations for verify. */
  static OperationCatalog catalog(Services services) {
    PlanRegistry registry =
        new PlanRegistry(
            () -> HotfixOps.open(services),
            () -> EximOps.open(services),
            () -> new com.jaspersoft.jrsctl.ops.upgrade.DefaultUpgradeOperations(services));
    PlanBuilder builder =
        (operation, argsJson) -> {
          try {
            return registry.rebuild(operation, argsJson);
          } catch (IllegalArgumentException e) {
            String message = e.getMessage() == null ? "" : e.getMessage();
            if (message.startsWith("unknown operation")) {
              throw new UnsupportedOperationException(message, e);
            }
            throw e;
          }
        };
    return new OperationCatalog(builder, () -> HotfixOps.open(services));
  }

  /**
   * Whether to hand a launch code to a browser. Opening the browser puts the code on a command line
   * that any local account can read, so it is only done where the home the token lives in is
   * private to this account; on a shared home the operator is asked to open the printed URL instead
   * (review 4.1). An explicit {@code --open} overrides the refusal, since the operator may know the
   * machine better than its permission bits do.
   */
  private boolean shouldOpenBrowser(Services services, ConsoleServer server, PrintWriter out) {
    if (open != null) {
      return open;
    }
    if (!Terminal.present()) {
      return false;
    }
    Optional<String> hint =
        headlessHint(services.platform().os(), Env.vars(), URI.create(server.url()));
    if (hint.isPresent()) {
      out.println(hint.get());
      out.flush();
      return false;
    }
    java.nio.file.Path home = services.home().root();
    try {
      if (!services.platform().files().isOwnerOnly(home)) {
        out.println(
            "not opening a browser: "
                + home
                + " is reachable by other accounts on this machine, and opening one would put the"
                + " launch code on a command line they can read. Open the URL above instead, or"
                + " pass --open.");
        out.flush();
        return false;
      }
    } catch (IOException e) {
      LOG.debug("cannot tell whether {} is private: {}", home, e.getMessage());
      return false;
    }
    return true;
  }

  /**
   * The SSH tunnel instructions for a Linux host without a desktop (neither {@code DISPLAY} nor
   * {@code WAYLAND_DISPLAY} set), where no browser can open; empty on Windows, with a display, or
   * when the console is bound to a network address, which needs no tunnel (#64).
   */
  static Optional<String> headlessHint(Platform.OsFamily os, Map<String, String> env, URI url) {
    boolean display =
        !env.getOrDefault("DISPLAY", "").isBlank()
            || !env.getOrDefault("WAYLAND_DISPLAY", "").isBlank();
    String host = url.getHost() == null ? "" : url.getHost();
    boolean loopback = host.equals("127.0.0.1") || host.equals("localhost") || host.equals("[::1]");
    if (os != Platform.OsFamily.LINUX || display || !loopback) {
      return Optional.empty();
    }
    int port = url.getPort();
    String user = env.getOrDefault("USER", "").isBlank() ? "<user>" : env.get("USER");
    String machine =
        env.getOrDefault("HOSTNAME", "").isBlank() ? "<this-server>" : env.get("HOSTNAME");
    String nl = System.lineSeparator();
    return Optional.of(
        "No desktop on this machine, so no browser is opened. On your own computer run:"
            + nl
            + "  ssh -L "
            + port
            + ":127.0.0.1:"
            + port
            + " "
            + user
            + "@"
            + machine
            + nl
            + "and open the Console URL above in a browser there. Keep this command running.");
  }

  /** Blocks until "stop" is read from standard input; on end of input, blocks until Ctrl-C. */
  private static void waitForStop() {
    try {
      BufferedReader in =
          new BufferedReader(new InputStreamReader(System.in, Charset.defaultCharset()));
      String line;
      while ((line = in.readLine()) != null) {
        String word = line.strip().toLowerCase(Locale.ROOT);
        if (word.equals("stop") || word.equals("quit") || word.equals("exit")) {
          return;
        }
      }
    } catch (IOException e) {
      LOG.debug("standard input closed: {}", e.getMessage());
    }
    try {
      new CountDownLatch(1).await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private static void openBrowser(Platform platform, String url) {
    List<String> command =
        switch (platform.os()) {
          case WINDOWS -> List.of("rundll32", "url.dll,FileProtocolHandler", url);
          case LINUX -> List.of("xdg-open", url);
        };
    // Started, not run: xdg-open's generic fallback runs the browser in the foreground and does
    // not return until it exits, and a kill-on-timeout runner would then kill the browser and
    // every tab fifteen seconds after opening the console (assessment item P1).
    try {
      platform
          .processes()
          .launch(command)
          .ifPresent(
              reason ->
                  LOG.info("could not open a browser ({}); open the printed URL manually", reason));
    } catch (RuntimeException e) {
      LOG.info("could not open a browser: {}; open the printed URL manually", e.getMessage());
    }
  }
}
