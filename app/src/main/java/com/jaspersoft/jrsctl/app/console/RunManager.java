package com.jaspersoft.jrsctl.app.console;

import com.jaspersoft.jrsctl.app.RunService;
import com.jaspersoft.jrsctl.core.engine.Context;
import com.jaspersoft.jrsctl.core.engine.Plan;
import com.jaspersoft.jrsctl.core.engine.RunOptions;
import com.jaspersoft.jrsctl.core.engine.RunOutcome;
import com.jaspersoft.jrsctl.core.engine.Runner;
import com.jaspersoft.jrsctl.core.event.Event;
import com.jaspersoft.jrsctl.core.event.EventBus;
import com.jaspersoft.jrsctl.core.event.EventSink;
import com.jaspersoft.jrsctl.core.json.Json;
import com.jaspersoft.jrsctl.core.redact.Redactor;
import com.jaspersoft.jrsctl.core.state.LockHeldException;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The console's runs: each executes on its own thread through the same {@link RunService} path the
 * CLI uses, with its own {@link EventBus} that SSE sessions subscribe to (spec §6.4, §13.1).
 * Invariants: at most one run is live at a time (the run lock enforces it across processes, this
 * class refuses a second one in-process before touching the lock); a launch answers only after the
 * runner has taken the lock and emitted its first event, so a held lock or a fingerprint refusal is
 * reported synchronously; every event of a run is appended, redacted, to {@code
 * runs/<runId>/events.jsonl} for the support bundle; the terminal event and outcome stay available
 * after the thread ends; {@link #shutdown()} cancels through each run's single {@link
 * com.jaspersoft.jrsctl.core.engine.CancellationToken} and waits up to 30 s. Once shutdown has
 * begun no new run is accepted: a launch is refused with a reason rather than started against a
 * console that is about to stop (review 4.9).
 */
public final class RunManager {

  /** How a launch request ended. */
  public sealed interface Launch permits Launched, LockHeld, Refused {}

  /** The run is executing (or already finished) under {@code runId}. */
  public record Launched(String runId) implements Launch {}

  /** Another jrsctl process holds the run lock. */
  public record LockHeld(String holderRunId, String holderPid) implements Launch {}

  /** The runner refused before touching anything (fingerprint mismatch). */
  public record Refused(String reason) implements Launch {}

  static final Duration START_TIMEOUT = Duration.ofSeconds(10);
  static final Duration SHUTDOWN_GRACE = Duration.ofSeconds(30);
  private static final Logger LOG = LoggerFactory.getLogger(RunManager.class);

  private final RunService runs;
  private final Redactor redactor;
  private final Map<String, LiveRun> known = new ConcurrentHashMap<>();
  private final Object launchLock = new Object();
  private volatile boolean closing;

  public RunManager(RunService runs) {
    this.runs = Objects.requireNonNull(runs, "runs");
    this.redactor = runs.services().redactor();
  }

  /** Executes a claimed plan under {@code runId}. */
  public Launch start(Plan plan, String runId, RunOptions options) {
    return launch(runId, plan, (runner, ctx) -> runs.run(runner, plan, ctx, options));
  }

  /** Continues a pending run (spec §6.6). */
  public Launch resume(Plan plan, String runId) {
    return launch(runId, plan, (runner, ctx) -> runs.resume(runner, plan, runId, ctx));
  }

  /** Compensates a pending run (spec §6.6). */
  public Launch rollback(Plan plan, String runId) {
    return launch(runId, plan, (runner, ctx) -> runs.rollback(runner, plan, runId, ctx));
  }

  /** The run this console started or recovered, live or finished. */
  public Optional<LiveRun> find(String runId) {
    return Optional.ofNullable(known.get(runId));
  }

  /** The run currently executing, if any. */
  public Optional<LiveRun> running() {
    return known.values().stream().filter(LiveRun::running).findFirst();
  }

  /** Requests cancellation; false when the run is not executing here. */
  public boolean cancel(String runId, String reason) {
    LiveRun live = known.get(runId);
    if (live == null || !live.running()) {
      return false;
    }
    live.ctx().cancel().cancel(reason);
    return true;
  }

  /** True once {@link #shutdown()} has begun; no further run is accepted. */
  public boolean closing() {
    return closing;
  }

  /** Refuses new runs, cancels every live run and waits for it to finish or compensate. */
  public void shutdown() {
    closing = true;
    List<LiveRun> live = new ArrayList<>();
    for (LiveRun run : known.values()) {
      if (run.running()) {
        run.ctx().cancel().cancel("console shutting down");
        live.add(run);
      }
    }
    long deadline = System.nanoTime() + SHUTDOWN_GRACE.toNanos();
    for (LiveRun run : live) {
      long remaining = Math.max(1, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()));
      try {
        run.thread().join(remaining);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
    }
  }

  private Launch launch(String runId, Plan plan, Body body) {
    synchronized (launchLock) {
      if (closing) {
        return new Refused("the console is shutting down; no new run is accepted");
      }
      Optional<LiveRun> busy = running();
      if (busy.isPresent()) {
        return new LockHeld(busy.get().runId(), Long.toString(ProcessHandle.current().pid()));
      }
      Context ctx = runs.context(runId);
      EventBus bus = new EventBus();
      LiveRun live = new LiveRun(runId, plan, ctx, bus, redactor, eventsFile(runId));
      bus.subscribe(live);
      CountDownLatch started = new CountDownLatch(1);
      bus.subscribe(e -> started.countDown());
      Runner runner = runs.runner(bus);
      Thread worker =
          new Thread(
              () -> {
                try {
                  live.finished(body.apply(runner, ctx));
                } catch (RuntimeException e) {
                  live.failed(e);
                } finally {
                  started.countDown();
                }
              },
              "jrsctl-console-run-" + runId);
      live.thread(worker);
      known.put(runId, live);
      worker.start();
      try {
        if (!started.await(START_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
          LOG.warn("run {} did not report a start within {}", runId, START_TIMEOUT);
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      Optional<RuntimeException> failure = live.failure();
      if (failure.isPresent() && failure.get() instanceof LockHeldException held) {
        known.remove(runId);
        return new LockHeld(held.holderRunId(), held.holderPid());
      }
      Optional<RunOutcome> outcome = live.outcome();
      if (outcome.isPresent() && outcome.get() instanceof RunOutcome.FingerprintMismatch m) {
        known.remove(runId);
        return new Refused("inputs changed since planning: " + m.changedKeys());
      }
      return new Launched(runId);
    }
  }

  private Path eventsFile(String runId) {
    return runs.services().home().runDir(runId).resolve("events.jsonl");
  }

  @FunctionalInterface
  private interface Body {
    RunOutcome apply(Runner runner, Context ctx);
  }

  /** One run started by this console: its plan, context, bus, thread and final state. */
  public static final class LiveRun implements EventSink {

    private final String runId;
    private final Plan plan;
    private final Context ctx;
    private final EventBus bus;
    private final Redactor redactor;
    private final Path eventsFile;
    private final AtomicReference<Event> terminal = new AtomicReference<>();
    private final AtomicReference<RunOutcome> outcome = new AtomicReference<>();
    private final AtomicReference<RuntimeException> failure = new AtomicReference<>();
    private final Object fileLock = new Object();
    private volatile Thread thread;
    private volatile boolean done;
    private BufferedWriter writer;

    LiveRun(
        String runId, Plan plan, Context ctx, EventBus bus, Redactor redactor, Path eventsFile) {
      this.runId = runId;
      this.plan = plan;
      this.ctx = ctx;
      this.bus = bus;
      this.redactor = redactor;
      this.eventsFile = eventsFile;
    }

    public String runId() {
      return runId;
    }

    public Plan plan() {
      return plan;
    }

    public Context ctx() {
      return ctx;
    }

    public EventBus bus() {
      return bus;
    }

    public boolean running() {
      return !done;
    }

    public Optional<Event> terminal() {
      return Optional.ofNullable(terminal.get());
    }

    public Optional<RunOutcome> outcome() {
      return Optional.ofNullable(outcome.get());
    }

    public Optional<RuntimeException> failure() {
      return Optional.ofNullable(failure.get());
    }

    Thread thread() {
      return thread;
    }

    void thread(Thread worker) {
      this.thread = worker;
    }

    void finished(RunOutcome result) {
      outcome.set(result);
      done = true;
      closeFile();
    }

    void failed(RuntimeException e) {
      failure.set(e);
      done = true;
      closeFile();
    }

    @Override
    public void emit(Event event) {
      if (SseEvents.terminal(event)) {
        terminal.compareAndSet(null, event);
      }
      append(event);
    }

    private void append(Event event) {
      String line = redactor.redact(Json.write(event));
      synchronized (fileLock) {
        try {
          if (writer == null) {
            Files.createDirectories(eventsFile.getParent());
            writer =
                Files.newBufferedWriter(
                    eventsFile,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
          }
          writer.write(line);
          writer.newLine();
          writer.flush();
        } catch (IOException e) {
          LOG.debug("cannot append to {}: {}", eventsFile, e.getMessage());
        }
      }
    }

    private void closeFile() {
      synchronized (fileLock) {
        if (writer != null) {
          try {
            writer.close();
          } catch (IOException e) {
            LOG.debug("cannot close {}: {}", eventsFile, e.getMessage());
          }
          writer = null;
        }
      }
    }
  }
}
