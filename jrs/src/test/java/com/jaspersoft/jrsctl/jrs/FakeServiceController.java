package com.jaspersoft.jrsctl.jrs;

import com.jaspersoft.jrsctl.core.platform.ServiceController;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** In-memory {@link ServiceController} that records every stop/start call in order. */
public final class FakeServiceController implements ServiceController {

  private State state;
  private final List<String> calls = new ArrayList<>();

  public FakeServiceController(State initial) {
    this.state = initial;
  }

  public List<String> calls() {
    return List.copyOf(calls);
  }

  @Override
  public State state() {
    return state;
  }

  @Override
  public State stop(Duration timeout) {
    calls.add("stop");
    state = State.STOPPED;
    return state;
  }

  @Override
  public State start(Duration timeout) {
    calls.add("start");
    state = State.RUNNING;
    return state;
  }

  @Override
  public String describe() {
    return "fake service";
  }
}
