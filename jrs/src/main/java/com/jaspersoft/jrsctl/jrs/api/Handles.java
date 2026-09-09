package com.jaspersoft.jrsctl.jrs.api;

import java.util.Optional;

/** Opaque handles and statuses for asynchronous export and import (spec §7.2). */
public final class Handles {

  private Handles() {}

  public record ExportHandle(String id) {}

  public record ImportHandle(String id) {}

  public enum Phase {
    INPROGRESS,
    READY,
    FAILED
  }

  public record ExportStatus(
      Phase phase,
      Optional<String> message,
      Optional<String> fileName,
      Optional<String> errorCode) {
    public boolean done() {
      return phase != Phase.INPROGRESS;
    }
  }

  public record ImportStatus(Phase phase, Optional<String> message, Optional<String> errorCode) {
    public boolean done() {
      return phase != Phase.INPROGRESS;
    }
  }
}
