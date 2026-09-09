package com.jaspersoft.jrsctl.app.console;

import com.jaspersoft.jrsctl.core.event.Event;
import com.jaspersoft.jrsctl.core.event.EventSink;
import com.jaspersoft.jrsctl.core.redact.Redactor;
import io.javalin.http.sse.SseClient;
import java.util.Objects;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * One browser connection to {@code GET /api/runs/{id}/events}. Invariants: every write to the
 * socket happens under one lock, so replay, live events and heartbeats never interleave; live
 * events are queued by the emitting (runner) thread and written by the session's own writer, so a
 * slow browser never slows a run; every payload is redacted before it leaves; once closed, writes
 * are silently dropped and {@link #closed()} stays true.
 */
final class SseSession implements EventSink {

  private final SseClient client;
  private final Redactor redactor;
  private final LinkedBlockingQueue<Event> queue = new LinkedBlockingQueue<>();
  private final Object lock = new Object();
  private volatile boolean closed;

  SseSession(SseClient client, Redactor redactor) {
    this.client = Objects.requireNonNull(client, "client");
    this.redactor = Objects.requireNonNull(redactor, "redactor");
  }

  /** Live events arrive here from the run's bus. */
  @Override
  public void emit(Event event) {
    if (!closed) {
      queue.add(event);
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
