package com.jaspersoft.jrsctl.app.console;

import com.jaspersoft.jrsctl.core.event.Event;
import com.jaspersoft.jrsctl.core.event.EventSink;
import com.jaspersoft.jrsctl.core.redact.Redactor;
import io.javalin.http.sse.SseClient;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One browser connection to {@code GET /api/runs/{id}/events}. Invariants: every write to the
 * socket happens under one lock, so replay, live events and heartbeats never interleave; live
 * events are queued by the emitting (runner) thread and written by the session's own writer, so a
 * slow browser never slows a run; the queue is bounded, so a browser that stops reading costs a
 * fixed amount of memory and is told it {@linkplain #lagged() lagged} and disconnected rather than
 * allowed to grow the queue without limit (review 4.7) - it reconnects and the server replays the
 * journal, so nothing is lost; every payload is redacted before it leaves; once closed, writes are
 * silently dropped and {@link #closed()} stays true.
 */
final class SseSession implements EventSink {

  private static final Logger LOG = LoggerFactory.getLogger(SseSession.class);

  private final SseClient client;
  private final Redactor redactor;
  private final SseBacklog queue = new SseBacklog();
  private final Object lock = new Object();
  private volatile boolean closed;

  SseSession(SseClient client, Redactor redactor) {
    this.client = Objects.requireNonNull(client, "client");
    this.redactor = Objects.requireNonNull(redactor, "redactor");
  }

  /**
   * Live events arrive here from the run's bus. Never blocks and never grows without bound: a full
   * queue means the browser has stopped reading, and the session is marked lagged for the writer to
   * close (review 4.7).
   */
  @Override
  public void emit(Event event) {
    if (!closed) {
      queue.offer(event);
    }
  }

  /** True when an event had to be dropped because the browser was not reading. */
  boolean lagged() {
    return queue.lagged();
  }

  /** Tells the browser why the stream ends, then closes it. */
  void closeLagged() {
    synchronized (lock) {
      if (closed) {
        return;
      }
      if (!client.terminated()) {
        try {
          client.sendComment("lagged");
        } catch (RuntimeException e) {
          LOG.debug("cannot tell a lagging client why the stream ends: {}", e.getMessage());
        }
      }
      closed = true;
      client.close();
    }
  }

  /** Next queued live event, or null after {@code timeout}. */
  Event poll(long timeout, TimeUnit unit) throws InterruptedException {
    return queue.poll(timeout, unit);
  }

  void send(Event event) {
    String data = redactor.redact(SseEvents.json(event));
    synchronized (lock) {
      if (closed || client.terminated()) {
        closed = true;
        return;
      }
      client.sendEvent(SseEvents.name(event), data);
    }
  }

  void heartbeat() {
    synchronized (lock) {
      if (closed || client.terminated()) {
        closed = true;
        return;
      }
      client.sendComment("heartbeat");
    }
  }

  void close() {
    synchronized (lock) {
      if (!closed) {
        closed = true;
        client.close();
      }
    }
  }

  /** Marks the session closed without touching the socket (the peer went away). */
  void markClosed() {
    closed = true;
  }

  boolean closed() {
    return closed || client.terminated();
  }
}
