package com.jaspersoft.jrsctl.app.console;

import static org.assertj.core.api.Assertions.assertThat;

import com.jaspersoft.jrsctl.core.event.Event;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Review finding 4.7: a browser that stops reading costs a bounded amount of memory and is told its
 * stream ended, rather than growing an unbounded queue for the length of the run.
 */
class SseBacklogTest {

  @Test
  void should_keep_at_most_the_capacity_and_report_lagging_when_the_reader_stops() {
    SseBacklog backlog = new SseBacklog();

    for (int i = 0; i < SseBacklog.CAPACITY; i++) {
      backlog.offer(log("line " + i));
    }

    assertThat(backlog.lagged()).isFalse();
    assertThat(backlog.size()).isEqualTo(SseBacklog.CAPACITY);

    backlog.offer(log("one too many"));

    assertThat(backlog.lagged()).as("the dropped event is the signal to end the stream").isTrue();
    assertThat(backlog.size()).isEqualTo(SseBacklog.CAPACITY);
  }

  @Test
  void should_stay_lagged_once_an_event_has_been_dropped() throws InterruptedException {
    SseBacklog backlog = new SseBacklog();
    for (int i = 0; i <= SseBacklog.CAPACITY; i++) {
      backlog.offer(log("line " + i));
    }

    assertThat(backlog.poll(0, TimeUnit.MILLISECONDS)).isNotNull();

    assertThat(backlog.lagged())
        .as("reading one event does not undo the gap in the stream")
        .isTrue();
  }

  @Test
  void should_return_events_in_order_and_nothing_when_empty() throws InterruptedException {
    SseBacklog backlog = new SseBacklog();
    backlog.offer(log("first"));
    backlog.offer(log("second"));

    assertThat(((Event.Log) backlog.poll(0, TimeUnit.MILLISECONDS)).message()).isEqualTo("first");
    assertThat(((Event.Log) backlog.poll(0, TimeUnit.MILLISECONDS)).message()).isEqualTo("second");
    assertThat(backlog.poll(1, TimeUnit.MILLISECONDS)).isNull();
  }

  private static Event log(String message) {
    return new Event.Log(
        Instant.EPOCH, "r-1", Optional.empty(), "apply", Event.Log.Level.INFO, message);
  }
}
