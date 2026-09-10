package com.jaspersoft.jrsctl.ops.hotfix;

import com.jaspersoft.jrsctl.core.engine.Context;
import com.jaspersoft.jrsctl.core.engine.StepResult;
import com.jaspersoft.jrsctl.core.event.Event;
import com.jaspersoft.jrsctl.core.event.EventSink;
import com.jaspersoft.jrsctl.core.secrets.SecretException;
import com.jaspersoft.jrsctl.ops.db.JdbcConnector;
import com.jaspersoft.jrsctl.ops.db.JdbcException;
import com.jaspersoft.jrsctl.ops.db.JdbcSettings;
import com.jaspersoft.jrsctl.ops.db.SqlScript;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Runs bundle SQL scripts through the configured database in one session (spec §8.2 step 9).
 * Invariants: scripts run in the given order, statement by statement; each is written to {@code
 * progress} before it is sent, so a compensation can tell a script that ran from one that never
 * started; the cancellation token is checked between scripts; a failure names the script and the
 * driver's message, never the connection password.
 */
final class SqlRunner {

  private SqlRunner() {}

  static StepResult run(
      HotfixRuntime rt,
      Context ctx,
      EventSink out,
      String stepId,
      String phase,
      Path bundleDir,
      List<String> scripts,
      SqlProgress progress) {
    if (scripts.isEmpty()) {
      return StepResult.ok();
    }
    Optional<JdbcSettings> settings = JdbcSettings.from(rt.config());
    if (settings.isEmpty()) {
      return Failures.recoverable(
          "database.type and database.url are not configured",
          "set the database section in config.yaml");
    }
    String current = "";
    try (JdbcConnector.Session session = settings.get().open(rt.jdbc(), rt.services().secrets())) {
      for (String script : scripts) {
        ctx.cancel().checkpoint();
        current = script;
        progress.start(script);
        int count = 0;
        for (String statement : SqlScript.read(bundleDir.resolve(script))) {
          count += session.execute(statement);
        }
        out.emit(
            new Event.Log(
                rt.clock().instant(),
                ctx.runId(),
                Optional.of(stepId),
                phase,
                Event.Log.Level.INFO,
                script + ": " + count + " statement(s)"));
      }
      return StepResult.ok();
    } catch (JdbcException | SecretException | IOException e) {
      return Failures.recoverable(
          (current.isEmpty() ? "database session" : current) + ": " + e.getMessage(),
          "check the database log; scripts are idempotent and may be re-run");
    }
  }
}
