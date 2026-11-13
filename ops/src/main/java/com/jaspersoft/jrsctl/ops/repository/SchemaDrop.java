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
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The one compensation of the mutating load phases (spec §10.5, ADR-0052, ADR-0055): drop the
 * schema this run created, and only that. Invariants: {@code create-js-db} asks the catalogue again
 * immediately before it writes {@code snapshots/<runId>/schema.created}, and writes nothing when
 * the schema is already there; the marker names the schema, its {@link State} and the instant the
 * schema was seen absent, and is forced to disk before the vendor target is launched, so a crash
 * during creation still leaves it. It is {@code creating} while the target runs, {@code created}
 * once the target returned 0, and {@code uncertain} when the target failed and the schema is on the
 * server anyway (it may have appeared from elsewhere in that window). A compensation drops the
 * schema only when the marker names it in state {@code creating} or {@code created} <em>and</em>
 * the buildomatic's {@code default_master.properties}, re-read now, still names it as {@code
 * js.dbName}; otherwise it refuses with the DBA's statement in the remediation, which the run
 * reports as exit 4. The drop goes through the configured JDBC connection by name, never through
 * {@code js-ant drop-js-db}, so a stale or edited properties file cannot redirect it; a schema that
 * is already gone is a no-op, which makes the compensation idempotent. A one-line marker written
 * before these states existed reads as {@code created}.
 */
final class SchemaDrop {

  static final String MARKER = "schema.created";

  private SchemaDrop() {}

  static Path marker(Context ctx) {
    return ctx.home().snapshots().resolve(ctx.runId()).resolve(MARKER);
  }

  /** Where the schema stands, as far as this run knows. */
  enum State {
    /** Seen absent, the vendor target launched and not yet returned. */
    CREATING,
    /** The vendor target returned 0. */
    CREATED,
    /** The vendor target failed and the schema is there anyway: not provably this run's. */
    UNCERTAIN;

    String token() {
      return name().toLowerCase(Locale.ROOT);
    }
  }

  private static final String STATE = "state=";
  private static final String ABSENT_AT = "absentAt=";

  /**
   * Writes the marker naming {@code schema} in {@code state}, seen absent at {@code absentAt}, and
   * forces it to disk.
   */
  static void record(Context ctx, String schema, State state, Instant absentAt) throws IOException {
    Path marker = marker(ctx);
    Files.createDirectories(marker.getParent());
    String nl = System.lineSeparator();
    Files.writeString(
        marker,
        schema + nl + STATE + state.token() + nl + ABSENT_AT + absentAt + nl,
        StandardCharsets.UTF_8);
    Durability.sync(marker);
    Durability.syncDirectory(marker.getParent());
  }

  /** Rewrites the marker's state, keeping its schema and instant; a missing marker is left so. */
  static void promote(Context ctx, State state) throws IOException {
    Optional<String> schema = recorded(ctx);
    if (schema.isEmpty()) {
      return;
    }
    Instant absentAt =
        lines(ctx).stream()
            .filter(l -> l.startsWith(ABSENT_AT))
            .findFirst()
            .map(l -> Instant.parse(l.substring(ABSENT_AT.length())))
            .orElse(Instant.EPOCH);
    record(ctx, schema.get(), state, absentAt);
  }

  /** Removes the marker: the vendor target failed and the schema is not on the server. */
  static void forget(Context ctx) throws IOException {
    Files.deleteIfExists(marker(ctx));
    Durability.syncDirectory(marker(ctx).getParent());
  }

  /** The schema this run recorded, in any state. */
  static Optional<String> recorded(Context ctx) {
    List<String> lines = lines(ctx);
    if (lines.isEmpty() || lines.get(0).isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(lines.get(0));
  }

  /** The recorded state; a marker without one predates the states and reads as created. */
  static Optional<State> state(Context ctx) {
    if (recorded(ctx).isEmpty()) {
      return Optional.empty();
    }
    for (String line : lines(ctx)) {
      if (line.startsWith(STATE)) {
        String token = line.substring(STATE.length());
        for (State s : State.values()) {
          if (s.token().equals(token)) {
            return Optional.of(s);
          }
        }
        // an unknown token is treated as the least trusting state
        return Optional.of(State.UNCERTAIN);
      }
    }
    return Optional.of(State.CREATED);
  }

  private static List<String> lines(Context ctx) {
    Path marker = marker(ctx);
    if (!Files.isRegularFile(marker)) {
      return List.of();
    }
    try {
      return Files.readString(marker, StandardCharsets.UTF_8)
          .strip()
          .lines()
          .map(String::strip)
          .toList();
    } catch (IOException e) {
      return List.of();
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
    if (state(ctx).orElse(State.UNCERTAIN) == State.UNCERTAIN) {
      return Steps.recoverable(
          "refusing to drop schema "
              + schema
              + ": create-js-db failed and the schema is on the server anyway, so it may have been"
              + " created by something else in the same moment; if this run created it, the DBA"
              + " drops it by name with "
              + dropSql,
          "ask who else creates schemas on "
              + in.catalog().adminUrl()
              + "; if "
              + schema
              + " is this run's and nothing uses it, have the DBA drop it by name ("
              + dropSql
              + ")");
    }
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
