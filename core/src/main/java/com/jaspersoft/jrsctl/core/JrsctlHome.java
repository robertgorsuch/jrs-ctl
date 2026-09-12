package com.jaspersoft.jrsctl.core;

import java.nio.file.Path;

/**
 * Layout of {@code $JRSCTL_HOME} (spec §5.1). Invariant: every path is derived from {@link #root()}
 * so relocating the home moves everything; nothing in the tool writes outside this tree except the
 * server installation it is asked to change and run-scoped temp files under {@link #runs()}.
 */
public record JrsctlHome(Path root) {

  public Path configFile() {
    return root.resolve("config.yaml");
  }

  public Path stateDb() {
    return root.resolve("state.db");
  }

  public Path runLock() {
    return root.resolve("runs.lock");
  }

  public Path snapshots() {
    return root.resolve("snapshots");
  }

  public Path runs() {
    return root.resolve("runs");
  }

  /**
   * Where native libraries are unpacked and run. Its own directory rather than {@link #runs()} so
   * nothing that walks run directories meets a stray shared library (review 3.4).
   */
  public Path nativeTemp() {
    return root.resolve("tmp");
  }

  public Path runDir(String runId) {
    return runs().resolve(runId);
  }

  public Path stagingDir(String runId) {
    return runDir(runId).resolve("staging");
  }

  public Path keys() {
    return root.resolve("keys");
  }

  public Path trustedKeys() {
    return keys().resolve("trusted");
  }

  public Path secretsFile() {
    return root.resolve("secrets.enc");
  }

  public Path consoleToken() {
    return root.resolve("console.token");
  }
}
