package com.jaspersoft.jrsctl.ops.repository;

import com.jaspersoft.jrsctl.core.engine.Context;
import com.jaspersoft.jrsctl.core.engine.Step;
import com.jaspersoft.jrsctl.core.engine.StepResult;
import com.jaspersoft.jrsctl.core.event.EventSink;
import com.jaspersoft.jrsctl.core.platform.Durability;
import com.jaspersoft.jrsctl.core.secrets.SecretException;
import com.jaspersoft.jrsctl.jrs.vendor.Buildomatic;
import com.jaspersoft.jrsctl.ops.Steps;
import com.jaspersoft.jrsctl.ops.db.JdbcConnector;
import com.jaspersoft.jrsctl.ops.db.JdbcException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * The one compensation of the mutating load phases (spec §10.5, ADR-0052): drop the schema this run
 * created, and only that. Invariants: {@code create-js-db} records the schema's name in {@code
 * snapshots/<runId>/schema.created} before the vendor target is launched, forced to disk, so a
 * crash during creation still leaves the marker; a compensation drops the schema only when the
 * marker names it <em>and</em> the buildomatic's {@code default_master.properties}, re-read now,
 * still names it as {@code js.dbName}; otherwise it refuses with the DBA's statement in the
 * remediation, which the run reports as exit 4. The drop goes through the configured JDBC
 * connection by name, never through {@code js-ant drop-js-db}, so a stale or edited properties file
 * cannot redirect it; a schema that is already gone is a no-op, which makes the compensation
 * idempotent.
 */
final class SchemaDrop {

  static final String MARKER = "schema.created";

  private SchemaDrop() {}

  static Path marker(Context ctx) {
    return ctx.home().snapshots().resolve(ctx.runId()).resolve(MARKER);
  }

  /** Writes the marker naming {@code schema} and forces it to disk. */
  static void record(Context ctx, String schema) throws IOException {
    Path marker = marker(ctx);
    Files.createDirectories(marker.getParent());
    Files.writeString(marker, schema + System.lineSeparator(), StandardCharsets.UTF_8);
    Durability.sync(marker);
    Durability.syncDirectory(marker.getParent());
  }

  /** The schema this run recorded as created, if any. */
  static Optional<String> recorded(Context ctx) {
    Path marker = marker(ctx);
    if (!Files.isRegularFile(marker)) {
      return Optional.empty();
    }
    try {
      String name = Files.readString(marker, StandardCharsets.UTF_8).strip();
      return name.isEmpty() ? Optional.empty() : Optional.of(name);
    } catch (IOException e) {
      return Optional.empty();
    }
  }

  static StepResult compensate(
      LoadRuntime rt, LoadInput in, Context ctx, EventSink out, Step step) {
    Optional<String> created = recorded(ctx);
    if (created.isEmpty()) {
      Steps.info(
          rt.services(), ctx, out, step, "no schema was created by this run; nothing to drop");
      return StepResult.ok();
    }
    String schema = created.get();
    String dropSql = in.catalog().dropSql(schema);
    Optional<String> named =
        rt.locator()
            .at(in.buildomatic().dir())
            .flatMap(b -> Optional.ofNullable(b.masterProperties().get(LoadInput.JS_DB_NAME)))
            .map(String::strip);
    if (named.isEmpty() || !named.get().equals(schema)) {
      return Steps.recoverable(
          "refusing to drop schema "
              + schema
              + ": "
              + in.buildomatic().dir().resolve(Buildomatic.MASTER_PROPERTIES)
              + " now names "
              + named.map(n -> LoadInput.JS_DB_NAME + "=" + n).orElse("no " + LoadInput.JS_DB_NAME)
              + ", so this run can no longer tell that "
              + schema
              + " is the schema it created and nothing else; if it is, the DBA drops it by name"
              + " with "
              + dropSql,
          "if "
              + schema
              + " is the schema this run created and nothing else uses it, have the DBA drop it"
              + " by name ("
              + dropSql
              + "); then jrsctl runs recover is not needed");
    }
    try (JdbcConnector.Session session =
        in.jdbc().withUrl(in.catalog().adminUrl()).open(rt.jdbc(), rt.services().secrets())) {
      if (!session.queryHasRow(in.catalog().existsSql(schema))) {
        Steps.info(
            rt.services(),
            ctx,
            out,
            step,
            "schema " + schema + " is not on the server; nothing to drop");
        return StepResult.ok();
      }
      session.executeStatement(dropSql);
      Steps.info(
          rt.services(),
          ctx,
          out,
          step,
          "dropped schema "
              + schema
              + " through "
              + in.catalog().adminUrl()
              + " ("
              + dropSql
              + ")");
      return StepResult.ok();
    } catch (JdbcException | SecretException | IllegalArgumentException e) {
      return Steps.recoverable(
          "could not drop schema " + schema + ": " + e.getMessage(),
          "have the DBA drop it by name ("
              + dropSql
              + ") or fix the database connection and run"
              + " jrsctl runs recover <runId> --rollback");
    }
  }
}
