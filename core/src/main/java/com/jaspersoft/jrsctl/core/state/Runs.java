package com.jaspersoft.jrsctl.core.state;

import com.jaspersoft.jrsctl.core.engine.RunRecord;
import com.jaspersoft.jrsctl.core.engine.TerminalState;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Runs: the run rows, including which of them are still pending. Invariant: one aggregate of the
 * state store, reached only through {@link StateStore}, which owns the {@link Db} session every
 * method here runs on, so the transaction, the lock and the failure wrapping are the same for every
 * aggregate (roadmap item 16).
 */
final class Runs {

  private final Db db;

  Runs(Db db) {
    this.db = db;
  }

  void recordRunStart(String runId, String operation, Optional<String> planId, Instant startedAt) {
    db.write(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement(
                  "INSERT INTO runs(run_id, operation, plan_id, started_at) VALUES (?,?,?,?)")) {
            ps.setString(1, runId);
            ps.setString(2, operation);
            ps.setString(3, planId.orElse(null));
            ps.setString(4, Timestamps.encode(startedAt));
            ps.executeUpdate();
          }
          return null;
        });
  }

  /**
   * Issue #350, ADR-0066: ends a pending run as FAILED with {@code exitCode} and appends the {@code
   * runs.abandon} audit row naming it, in one transaction, so a run is never shown abandoned
   * without having ended or ended by an abandon without the row. False, with nothing written, when
   * the run is unknown or already ended.
   */
  boolean abandon(String runId, Instant endedAt, int exitCode, String actor, Instant at) {
    return db.write(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement(
                  "UPDATE runs SET ended_at=?, terminal_state=?, exit_code=?"
                      + " WHERE run_id=? AND terminal_state IS NULL")) {
            ps.setString(1, Timestamps.encode(endedAt));
            ps.setString(2, TerminalState.FAILED.name());
            ps.setInt(3, exitCode);
            ps.setString(4, runId);
            if (ps.executeUpdate() == 0) {
              return false;
            }
          }
          try (PreparedStatement ps =
              c.prepareStatement("INSERT INTO audit(ts, actor, action, detail) VALUES (?,?,?,?)")) {
            ps.setString(1, Timestamps.encode(at));
            ps.setString(2, actor);
            ps.setString(3, ABANDON_ACTION);
            ps.setString(4, runId);
            ps.executeUpdate();
          }
          return true;
        });
  }

  /** The audit action that marks a run abandoned; its detail is exactly the run id. */
  static final String ABANDON_ACTION = "runs.abandon";

  /** Ids of every run ended by {@link #abandon}. */
  Set<String> abandonedRunIds() {
    return db.read(
        c -> {
          Set<String> ids = new HashSet<>();
          try (PreparedStatement ps =
              c.prepareStatement("SELECT detail FROM audit WHERE action=?")) {
            ps.setString(1, ABANDON_ACTION);
            try (ResultSet rs = ps.executeQuery()) {
              while (rs.next()) {
                String id = rs.getString(1);
                if (id != null) {
                  ids.add(id);
                }
              }
            }
          }
          return Set.copyOf(ids);
        });
  }

  void recordRunEnd(String runId, Instant endedAt, TerminalState state, int exitCode) {
    int rows =
        db.write(
            c -> {
              try (PreparedStatement ps =
                  c.prepareStatement(
                      "UPDATE runs SET ended_at=?, terminal_state=?, exit_code=?"
                          + " WHERE run_id=? AND terminal_state IS NULL")) {
                ps.setString(1, Timestamps.encode(endedAt));
                ps.setString(2, state.name());
                ps.setInt(3, exitCode);
                ps.setString(4, runId);
                return ps.executeUpdate();
              }
            });
    if (rows == 0) {
      // A run has one terminal state (the Journal contract); a second write is a bug upstream,
      // not something to record silently (assessment item E7).
      throw run(runId)
          .map(
              r ->
                  new StateStoreException(
                      "run "
                          + runId
                          + " already ended as "
                          + r.terminalState().map(Enum::name).orElse("?")
                          + "; a run has exactly one terminal state"))
          .orElseGet(() -> new StateStoreException("unknown run " + runId));
    }
  }

  Optional<RunRecord> run(String runId) {
    return db.read(
        c -> {
          try (PreparedStatement ps = c.prepareStatement("SELECT * FROM runs WHERE run_id=?")) {
            ps.setString(1, runId);
            try (ResultSet rs = ps.executeQuery()) {
              return rs.next() ? Optional.of(readRun(rs)) : Optional.empty();
            }
          }
        });
  }

  /** Most recent runs first. */
  List<RunRecord> runs(int limit) {
    return db.read(
        c -> {
          List<RunRecord> out = new ArrayList<>();
          try (PreparedStatement ps =
              c.prepareStatement(
                  "SELECT * FROM runs ORDER BY started_at DESC, run_id DESC LIMIT ?")) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
              while (rs.next()) {
                out.add(readRun(rs));
              }
            }
          }
          return List.copyOf(out);
        });
  }

  /** Runs without a terminal state, oldest first (spec §5.5). */
  List<RunRecord> pendingRuns() {
    return db.read(
        c -> {
          List<RunRecord> out = new ArrayList<>();
          try (Statement s = c.createStatement();
              ResultSet rs =
                  s.executeQuery(
                      "SELECT * FROM runs WHERE terminal_state IS NULL"
                          + " ORDER BY started_at, run_id")) {
            while (rs.next()) {
              out.add(readRun(rs));
            }
          }
          return List.copyOf(out);
        });
  }

  private static RunRecord readRun(ResultSet rs) throws SQLException {
    int exit = rs.getInt("exit_code");
    Optional<Integer> exitCode = rs.wasNull() ? Optional.empty() : Optional.of(exit);
    return new RunRecord(
        rs.getString("run_id"),
        rs.getString("operation"),
        Db.optString(rs, "plan_id"),
        Db.instant(rs, "started_at"),
        Db.optString(rs, "ended_at").map(Instant::parse),
        Db.optString(rs, "terminal_state").map(TerminalState::valueOf),
        exitCode);
  }
}
