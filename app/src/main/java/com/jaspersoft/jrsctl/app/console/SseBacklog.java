package com.jaspersoft.jrsctl.app.console;

import com.jaspersoft.jrsctl.core.event.Event;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * The events one browser has not read yet. Invariants: the backlog is bounded, so a browser that
 * stops reading costs a fixed amount of memory rather than growing a queue for as long as the run
 * lasts (review 4.7); {@link #offer} never blocks, because the thread calling it is the one running
 * the operation; once an event has been dropped the backlog stays {@link #lagged()} for ever, which
 * is the writer's signal to tell that browser its stream ended and let it reconnect to a full
 * replay from the journal.
 */
final class SseBacklog {

  /** Roughly a long run's worth of events; a reader this far behind is not catching up. */
  static final int CAPACITY = 1024;

  private final LinkedBlockingQueue<Event> queue = new LinkedBlockingQueue<>(CAPACITY);
  private volatile boolean lagged;

  /** Queues an event, or records that one was dropped. Never blocks. */
  void offer(Event event) {
    if (!queue.offer(event)) {
      lagged = true;
    }
  }

  /** Next queued event, or null after {@code timeout}. */
  Event poll(long timeout, TimeUnit unit) throws InterruptedException {
    return queue.poll(timeout, unit);
  }

  /** True once any event has been dropped. */
  boolean lagged() {
    return lagged;
  }

  int size() {
    return queue.size();
  }
}
