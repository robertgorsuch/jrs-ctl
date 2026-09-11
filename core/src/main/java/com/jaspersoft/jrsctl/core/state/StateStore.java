package com.jaspersoft.jrsctl.core.state;

import com.jaspersoft.jrsctl.core.JrsctlHome;
import com.jaspersoft.jrsctl.core.redact.Redactor;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The SQLite state store at {@code $JRSCTL_HOME/state.db}: the single source of truth for run
 * state, installed hotfixes, plans, snapshots and audit (spec §5.4). Invariants: opened with WAL,
 * {@code synchronous=FULL}, foreign keys on and a 5 s busy timeout; migrations run on open and are
 * idempotent; every write happens inside a {@code BEGIN IMMEDIATE ... COMMIT} transaction; {@code
 * step_transitions} and {@code audit} are append-only (enforced by triggers); every public method
 * is thread-safe and never returns {@code null}. {@code SQLException} never escapes; failures are
 * reported as {@link StateStoreException}. A damaged file is refused on open: {@code PRAGMA
 * quick_check} runs before the migrations and a result other than {@code ok} raises {@link
 * StateStoreException} naming the file and the way out ({@link #corruptionRemediation}), so
 * corruption surfaces at start rather than in whatever query first touches it. {@link #close()}
 * never throws: it runs after a run's outcome is journaled, and a failure to close is logged.
 */
public final class StateStore implements AutoCloseable {

  private static final Logger LOG = LoggerFactory.getLogger(StateStore.class);

  private final Connection conn;
  private final Path file;
  private final Clock clock;
  private final Object mutex = new Object();

  StateStore(Connection conn, Path file, Clock clock) {
    this.conn = conn;
    this.file = file;
    this.clock = clock;
  }

  public static StateStore open(JrsctlHome home) {
    return open(home.stateDb(), Clock.systemUTC());
  }

  public static StateStore open(JrsctlHome home, Clock clock) {
    return open(home.stateDb(), clock);
  }

  public static StateStore open(Path dbFile, Clock clock) {
    Path abs = dbFile.toAbsolutePath().normalize();
    try {
      Path parent = abs.getParent();
      if (parent != null) {
        Files.createDirectories(parent);
      }
    } catch (IOException e) {
      throw new StateStoreException("cannot create directory for " + abs, e);
    }
    Connection c;
    try {
      c = DriverManager.getConnection("jdbc:sqlite:" + abs);
    } catch (SQLException e) {
      throw new StateStoreException("cannot open state store " + abs, e);
    }
    try {
      try (Statement s = c.createStatement()) {
        s.execute("PRAGMA busy_timeout=5000");
        s.execute("PRAGMA journal_mode=WAL");
        s.execute("PRAGMA synchronous=FULL");
        s.execute("PRAGMA foreign_keys=ON");
      }
      String check = quickCheck(c);
      if (!"ok".equals(check)) {
        throw new StateStoreException(
            "state store "
                + abs
                + " failed PRAGMA quick_check: "
                + check
                + "; "
                + corruptionRemediation(abs));
      }
      Migrations.apply(c, clock);
    } catch (SQLException | RuntimeException e) {
      try {
        c.close();
      } catch (SQLException suppressed) {
        e.addSuppressed(suppressed);
      }
      if (e instanceof RuntimeException re) {
        throw re;
      }
      throw new StateStoreException("cannot initialise state store " + abs, e);
    }
    return new StateStore(c, abs, clock);
  }

  public Path file() {
    return file;
  }

  /**
   * {@code PRAGMA quick_check}: {@code "ok"} for a sound database, otherwise the first problems
   * SQLite reports, joined by {@code "; "}. Read-only; {@code doctor} shows it.
   */
  public String integrity() {
    return read(StateStore::quickCheck);
  }

  /** What an operator does with a state store that fails its integrity check. */
  public static String corruptionRemediation(Path file) {
    return "stop every jrsctl process, move "
        + file
        + " aside (for example to state.db.corrupt-<date>), then restore state.db from the most"
        + " recent support bundle or let jrsctl create a fresh one; runs and installed hotfixes"
        + " recorded only in the damaged file are not recoverable from it";
  }

  private static String quickCheck(Connection c) throws SQLException {
    List<String> problems = new ArrayList<>();
    try (Statement s = c.createStatement();
        ResultSet rs = s.executeQuery("PRAGMA quick_check(3)")) {
      while (rs.next()) {
        problems.add(rs.getString(1));
      }
    }
    if (problems.size() == 1 && "ok".equals(problems.get(0))) {
      return "ok";
    }
    return String.join("; ", problems);
  }

  public int schemaVersion() {
    return read(Migrations::currentVersion);
  }

  // ---- servers -------------------------------------------------------------------------------

  public void upsertServer(ServerRecord server) {
    write(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement(
                  "INSERT INTO servers(id, base_url, version, edition, tenancy, first_seen,"
                      + " last_seen) VALUES (?,?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET"
                      + " base_url=excluded.base_url, version=excluded.version,"
                      + " edition=excluded.edition, tenancy=excluded.tenancy,"
                      + " last_seen=excluded.last_seen")) {
            ps.setString(1, server.id());
            ps.setString(2, server.baseUrl());
            ps.setString(3, server.version());
            ps.setString(4, server.edition());
            ps.setString(5, server.tenancy());
            ps.setString(6, server.firstSeen().toString());
            ps.setString(7, server.lastSeen().toString());
            ps.executeUpdate();
          }
          return null;
        });
  }

  public List<ServerRecord> servers() {
    return read(
        c -> {
          List<ServerRecord> out = new ArrayList<>();
          try (Statement s = c.createStatement();
              ResultSet rs = s.executeQuery("SELECT * FROM servers ORDER BY last_seen DESC")) {
            while (rs.next()) {
              out.add(
                  new ServerRecord(
                      rs.getString("id"),
                      rs.getString("base_url"),
                      rs.getString("version"),
                      rs.getString("edition"),
                      rs.getString("tenancy"),
                      instant(rs, "first_seen"),
                      instant(rs, "last_seen")));
            }
          }
          return List.copyOf(out);
        });
  }

  // ---- hotfixes ------------------------------------------------------------------------------

  /** Records an installed hotfix and its files in one transaction. */
  public void recordHotfixInstalled(HotfixInstalled hotfix, List<HotfixFile> files) {
    write(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement(
                  "INSERT INTO hotfixes_installed(id, version, title, installed_run_id,"
                      + " snapshot_ref, state, installed_at) VALUES (?,?,?,?,?,?,?)")) {
            ps.setString(1, hotfix.id());
            ps.setString(2, hotfix.version());
            ps.setString(3, hotfix.title());
            ps.setString(4, hotfix.installedRunId());
            ps.setString(5, hotfix.snapshotRef().orElse(null));
            ps.setString(6, hotfix.state().name());
            ps.setString(7, hotfix.installedAt().toString());
            ps.executeUpdate();
          }
          try (PreparedStatement ps =
              c.prepareStatement(
                  "INSERT INTO hotfix_files(hotfix_id, path, action, before_sha256, after_sha256)"
                      + " VALUES (?,?,?,?,?)")) {
            for (HotfixFile f : files) {
              ps.setString(1, hotfix.id());
              ps.setString(2, f.path().toString());
              ps.setString(3, f.action());
              ps.setString(4, f.beforeSha256().orElse(null));
              ps.setString(5, f.afterSha256().orElse(null));
              ps.addBatch();
            }
            ps.executeBatch();
          }
          return null;
        });
  }

  public void updateHotfixState(String hotfixId, HotfixState state) {
    int rows =
        write(
            c -> {
              try (PreparedStatement ps =
                  c.prepareStatement("UPDATE hotfixes_installed SET state=? WHERE id=?")) {
                ps.setString(1, state.name());
                ps.setString(2, hotfixId);
                return ps.executeUpdate();
              }
            });
    if (rows == 0) {
      throw new StateStoreException("unknown hotfix " + hotfixId);
    }
  }

  /** Hotfixes currently in state {@code INSTALLED}, oldest first (the LIFO order for rollback). */
  public List<HotfixInstalled> installedHotfixes() {
    return read(
        c ->
            queryHotfixes(
                c,
                "SELECT * FROM hotfixes_installed WHERE state='INSTALLED'"
                    + " ORDER BY installed_at, id"));
  }

  /** Every hotfix ever recorded, in any state, oldest first. */
  public List<HotfixInstalled> hotfixes() {
    return read(
        c -> queryHotfixes(c, "SELECT * FROM hotfixes_installed ORDER BY installed_at, id"));
  }

  public Optional<HotfixInstalled> hotfix(String id) {
    return read(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement("SELECT * FROM hotfixes_installed WHERE id=?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
              return rs.next() ? Optional.of(readHotfix(rs)) : Optional.empty();
            }
          }
        });
  }

  private static List<HotfixInstalled> queryHotfixes(Connection c, String sql) throws SQLException {
    List<HotfixInstalled> out = new ArrayList<>();
    try (Statement s = c.createStatement();
        ResultSet rs = s.executeQuery(sql)) {
      while (rs.next()) {
        out.add(readHotfix(rs));
      }
    }
    return List.copyOf(out);
  }

  private static HotfixInstalled readHotfix(ResultSet rs) throws SQLException {
    return new HotfixInstalled(
        rs.getString("id"),
        rs.getString("version"),
        rs.getString("title"),
        rs.getString("installed_run_id"),
        optString(rs, "snapshot_ref"),
        HotfixState.valueOf(rs.getString("state")),
        instant(rs, "installed_at"));
  }

  public List<HotfixFile> hotfixFiles(String hotfixId) {
    return read(
        c -> {
          List<HotfixFile> out = new ArrayList<>();
          try (PreparedStatement ps =
              c.prepareStatement("SELECT * FROM hotfix_files WHERE hotfix_id=? ORDER BY path")) {
            ps.setString(1, hotfixId);
            try (ResultSet rs = ps.executeQuery()) {
              while (rs.next()) {
                out.add(readHotfixFile(rs));
              }
            }
          }
          return List.copyOf(out);
        });
  }

  /**
   * Files among {@code paths} that belong to a hotfix in state {@code INSTALLED}; used for the
   * overlap check (spec §8.4). Paths are compared by their string form.
   */
  public List<HotfixFile> filesOwnedBy(Collection<Path> paths) {
    List<String> wanted =
        new ArrayList<>(new LinkedHashSet<>(paths.stream().map(Path::toString).toList()));
    if (wanted.isEmpty()) {
      return List.of();
    }
    return read(
        c -> {
          List<HotfixFile> out = new ArrayList<>();
          int chunk = 500;
          for (int from = 0; from < wanted.size(); from += chunk) {
            List<String> slice = wanted.subList(from, Math.min(wanted.size(), from + chunk));
            String placeholders =
                String.join(",", java.util.Collections.nCopies(slice.size(), "?"));
            try (PreparedStatement ps =
                c.prepareStatement(
                    "SELECT f.* FROM hotfix_files f JOIN hotfixes_installed h ON h.id=f.hotfix_id"
                        + " WHERE h.state='INSTALLED' AND f.path IN ("
                        + placeholders
                        + ") ORDER BY h.installed_at, f.path")) {
              for (int i = 0; i < slice.size(); i++) {
                ps.setString(i + 1, slice.get(i));
              }
              try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                  out.add(readHotfixFile(rs));
                }
              }
            }
          }
          return List.copyOf(out);
        });
  }

  private static HotfixFile readHotfixFile(ResultSet rs) throws SQLException {
    return new HotfixFile(
        rs.getString("hotfix_id"),
        Path.of(rs.getString("path")),
        rs.getString("action"),
        optString(rs, "before_sha256"),
        optString(rs, "after_sha256"));
  }

  // ---- customizations ------------------------------------------------------------------------

  public void registerCustomization(Customization customization) {
    write(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement(
                  "INSERT INTO customizations(path, original_sha256, snapshot_ref, registered_at)"
                      + " VALUES (?,?,?,?) ON CONFLICT(path) DO UPDATE SET"
                      + " original_sha256=excluded.original_sha256,"
                      + " snapshot_ref=excluded.snapshot_ref,"
                      + " registered_at=excluded.registered_at")) {
            ps.setString(1, customization.path().toString());
            ps.setString(2, customization.originalSha256());
            ps.setString(3, customization.snapshotRef().orElse(null));
            ps.setString(4, customization.registeredAt().toString());
            ps.executeUpdate();
          }
          return null;
        });
  }

  public boolean unregisterCustomization(Path path) {
    return write(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement("DELETE FROM customizations WHERE path=?")) {
            ps.setString(1, path.toString());
            return ps.executeUpdate() > 0;
          }
        });
  }

  public List<Customization> customizations() {
    return read(
        c -> {
          List<Customization> out = new ArrayList<>();
          try (Statement s = c.createStatement();
              ResultSet rs = s.executeQuery("SELECT * FROM customizations ORDER BY path")) {
            while (rs.next()) {
              out.add(
                  new Customization(
                      Path.of(rs.getString("path")),
                      rs.getString("original_sha256"),
                      optString(rs, "snapshot_ref"),
                      instant(rs, "registered_at")));
            }
          }
          return List.copyOf(out);
        });
  }

  // ---- plans ---------------------------------------------------------------------------------

  public void savePlan(StoredPlan plan) {
    write(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement(
                  "INSERT INTO plans(plan_id, operation, args_json, plan_json, fingerprint,"
                      + " created_at, expires_at, consumed_by_run_id) VALUES (?,?,?,?,?,?,?,?)")) {
            ps.setString(1, plan.planId());
            ps.setString(2, plan.operation());
            ps.setString(3, plan.argsJson());
            ps.setString(4, plan.planJson());
            ps.setString(5, plan.fingerprint());
            ps.setString(6, plan.createdAt().toString());
            ps.setString(7, plan.expiresAt().toString());
            ps.setString(8, plan.consumedByRunId().orElse(null));
            ps.executeUpdate();
          }
          return null;
        });
  }

  public Optional<StoredPlan> loadPlan(String planId) {
    return read(
        c -> {
          try (PreparedStatement ps = c.prepareStatement("SELECT * FROM plans WHERE plan_id=?")) {
            ps.setString(1, planId);
            try (ResultSet rs = ps.executeQuery()) {
              return rs.next() ? Optional.of(readPlan(rs)) : Optional.empty();
            }
          }
        });
  }

  /**
   * Claims an unexpired, unconsumed plan for {@code runId}; false when it is unknown, expired or
   * already consumed, so a plan can never execute twice.
   */
  public boolean consumePlan(String planId, String runId, Instant now) {
    return write(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement(
                  "UPDATE plans SET consumed_by_run_id=? WHERE plan_id=?"
                      + " AND consumed_by_run_id IS NULL AND expires_at > ?")) {
            ps.setString(1, runId);
            ps.setString(2, planId);
            ps.setString(3, now.toString());
            return ps.executeUpdate() == 1;
          }
        });
  }

  /** Deletes unconsumed plans whose TTL elapsed; returns how many were removed. */
  public int expirePlans(Instant now) {
    return write(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement(
                  "DELETE FROM plans WHERE consumed_by_run_id IS NULL AND expires_at <= ?")) {
            ps.setString(1, now.toString());
            return ps.executeUpdate();
          }
        });
  }

  private static StoredPlan readPlan(ResultSet rs) throws SQLException {
    return new StoredPlan(
        rs.getString("plan_id"),
        rs.getString("operation"),
        rs.getString("args_json"),
        rs.getString("plan_json"),
        rs.getString("fingerprint"),
        instant(rs, "created_at"),
        instant(rs, "expires_at"),
        optString(rs, "consumed_by_run_id"));
  }

  // ---- runs ----------------------------------------------------------------------------------

  public void recordRunStart(
      String runId, String operation, Optional<String> planId, Instant startedAt) {
    write(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement(
                  "INSERT INTO runs(run_id, operation, plan_id, started_at) VALUES (?,?,?,?)")) {
            ps.setString(1, runId);
            ps.setString(2, operation);
            ps.setString(3, planId.orElse(null));
            ps.setString(4, startedAt.toString());
            ps.executeUpdate();
          }
          return null;
        });
  }

  public void recordRunEnd(String runId, Instant endedAt, TerminalState state, int exitCode) {
    int rows =
        write(
            c -> {
              try (PreparedStatement ps =
                  c.prepareStatement(
                      "UPDATE runs SET ended_at=?, terminal_state=?, exit_code=? WHERE run_id=?")) {
                ps.setString(1, endedAt.toString());
                ps.setString(2, state.name());
                ps.setInt(3, exitCode);
                ps.setString(4, runId);
                return ps.executeUpdate();
              }
            });
    if (rows == 0) {
      throw new StateStoreException("unknown run " + runId);
    }
  }

  public Optional<RunRecord> run(String runId) {
    return read(
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
  public List<RunRecord> runs(int limit) {
    return read(
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
  public List<RunRecord> pendingRuns() {
    return read(
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
        optString(rs, "plan_id"),
        instant(rs, "started_at"),
        optString(rs, "ended_at").map(Instant::parse),
        optString(rs, "terminal_state").map(TerminalState::valueOf),
        exitCode);
  }

  // ---- step transitions (journal) ------------------------------------------------------------

  /** Appends one journal row in its own transaction and returns it with its sequence number. */
  public Transition appendTransition(
      String runId,
      String stepId,
      String phase,
      Optional<String> fromState,
      String toState,
      Optional<String> detail) {
    Optional<String> sanitizedDetail = detail.map(Redactor.global()::redact);
    Instant ts = clock.instant();
    long seq =
        write(
            c -> {
              try (PreparedStatement ps =
                  c.prepareStatement(
                      "INSERT INTO step_transitions(ts, run_id, step_id, phase, from_state,"
                          + " to_state, detail) VALUES (?,?,?,?,?,?,?)")) {
                ps.setString(1, ts.toString());
                ps.setString(2, runId);
                ps.setString(3, stepId);
                ps.setString(4, phase);
                ps.setString(5, fromState.orElse(null));
                ps.setString(6, toState);
                ps.setString(7, sanitizedDetail.orElse(null));
                ps.executeUpdate();
              }
              try (Statement s = c.createStatement();
                  ResultSet rs = s.executeQuery("SELECT last_insert_rowid()")) {
                rs.next();
                return rs.getLong(1);
              }
            });
    return new Transition(seq, ts, runId, stepId, phase, fromState, toState, sanitizedDetail);
  }

  /** The journal of one run in write order. */
  public List<Transition> transitions(String runId) {
    return read(
        c -> {
          List<Transition> out = new ArrayList<>();
          try (PreparedStatement ps =
              c.prepareStatement("SELECT * FROM step_transitions WHERE run_id=? ORDER BY seq")) {
            ps.setString(1, runId);
            try (ResultSet rs = ps.executeQuery()) {
              while (rs.next()) {
                out.add(
                    new Transition(
                        rs.getLong("seq"),
                        instant(rs, "ts"),
                        rs.getString("run_id"),
                        rs.getString("step_id"),
                        rs.getString("phase"),
                        optString(rs, "from_state"),
                        rs.getString("to_state"),
                        optString(rs, "detail")));
              }
            }
          }
          return List.copyOf(out);
        });
  }

  // ---- snapshots -----------------------------------------------------------------------------

  public void recordSnapshot(SnapshotRecord snapshot) {
    write(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement(
                  "INSERT INTO snapshots(id, run_id, step_id, path, manifest_sha256, referenced_by)"
                      + " VALUES (?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET"
                      + " referenced_by=excluded.referenced_by")) {
            ps.setString(1, snapshot.id());
            ps.setString(2, snapshot.runId());
            ps.setString(3, snapshot.stepId());
            ps.setString(4, snapshot.path().toString());
            ps.setString(5, snapshot.manifestSha256());
            ps.setString(6, snapshot.referencedBy().orElse(null));
            ps.executeUpdate();
          }
          return null;
        });
  }

  public Optional<SnapshotRecord> snapshot(String id) {
    return read(
        c -> {
          try (PreparedStatement ps = c.prepareStatement("SELECT * FROM snapshots WHERE id=?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
              return rs.next() ? Optional.of(readSnapshot(rs)) : Optional.empty();
            }
          }
        });
  }

  public List<SnapshotRecord> snapshots(String runId) {
    return read(
        c -> {
          List<SnapshotRecord> out = new ArrayList<>();
          try (PreparedStatement ps =
              c.prepareStatement("SELECT * FROM snapshots WHERE run_id=? ORDER BY id")) {
            ps.setString(1, runId);
            try (ResultSet rs = ps.executeQuery()) {
              while (rs.next()) {
                out.add(readSnapshot(rs));
              }
            }
          }
          return List.copyOf(out);
        });
  }

  /** Every recorded snapshot row, ordered by run id then id. */
  public List<SnapshotRecord> snapshots() {
    return read(
        c -> {
          List<SnapshotRecord> out = new ArrayList<>();
          try (Statement s = c.createStatement();
              ResultSet rs = s.executeQuery("SELECT * FROM snapshots ORDER BY run_id, id")) {
            while (rs.next()) {
              out.add(readSnapshot(rs));
            }
          }
          return List.copyOf(out);
        });
  }

  /**
   * Removes the {@code snapshots} rows of {@code runId/stepId} after retention pruning deleted the
   * directory (spec §5.6); returns how many rows went. The journal ({@code step_transitions}) and
   * the audit trail are untouched, so the run's history still names the snapshot it once had.
   */
  public int deleteSnapshot(String runId, String stepId) {
    return write(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement("DELETE FROM snapshots WHERE run_id=? AND step_id=?")) {
            ps.setString(1, runId);
            ps.setString(2, stepId);
            return ps.executeUpdate();
          }
        });
  }

  private static SnapshotRecord readSnapshot(ResultSet rs) throws SQLException {
    return new SnapshotRecord(
        rs.getString("id"),
        rs.getString("run_id"),
        rs.getString("step_id"),
        Path.of(rs.getString("path")),
        rs.getString("manifest_sha256"),
        optString(rs, "referenced_by"));
  }

  // ---- audit ---------------------------------------------------------------------------------

  public AuditEntry audit(String actor, String action, String detail) {
    String sanitizedDetail = detail == null ? null : Redactor.global().redact(detail);
    Instant ts = clock.instant();
    long seq =
        write(
            c -> {
              try (PreparedStatement ps =
                  c.prepareStatement(
                      "INSERT INTO audit(ts, actor, action, detail) VALUES (?,?,?,?)")) {
                ps.setString(1, ts.toString());
                ps.setString(2, actor);
                ps.setString(3, action);
                ps.setString(4, sanitizedDetail);
                ps.executeUpdate();
              }
              try (Statement s = c.createStatement();
                  ResultSet rs = s.executeQuery("SELECT last_insert_rowid()")) {
                rs.next();
                return rs.getLong(1);
              }
            });
    return new AuditEntry(seq, ts, actor, action, Optional.ofNullable(sanitizedDetail));
  }

  /** Most recent audit rows first. */
  public List<AuditEntry> auditRows(int limit) {
    return read(
        c -> {
          List<AuditEntry> out = new ArrayList<>();
          try (PreparedStatement ps =
              c.prepareStatement("SELECT * FROM audit ORDER BY seq DESC LIMIT ?")) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
              while (rs.next()) {
                out.add(
                    new AuditEntry(
                        rs.getLong("seq"),
                        instant(rs, "ts"),
                        rs.getString("actor"),
                        rs.getString("action"),
                        optString(rs, "detail")));
              }
            }
          }
          return List.copyOf(out);
        });
  }

  // ---- raw access for tests and support bundles ----------------------------------------------

  /** Executes an arbitrary statement inside a transaction; intended for tests and maintenance. */
  public int executeUpdate(String sql) {
    return write(
        c -> {
          try (Statement s = c.createStatement()) {
            return s.executeUpdate(sql);
          }
        });
  }

  /** Never throws (review finding 1.18): a close failure is logged, not turned into an error. */
  @Override
  public void close() {
    synchronized (mutex) {
      try {
        conn.close();
      } catch (SQLException | RuntimeException e) {
        LOG.warn("state store {} did not close cleanly: {}", file, e.getMessage());
      }
    }
  }

  // ---- plumbing ------------------------------------------------------------------------------

  @FunctionalInterface
  private interface SqlWork<T> {
    T apply(Connection c) throws SQLException;
  }

  private <T> T read(SqlWork<T> work) {
    synchronized (mutex) {
      try {
        return work.apply(conn);
      } catch (SQLException e) {
        throw new StateStoreException("state store read failed: " + e.getMessage(), e);
      }
    }
  }

  private <T> T write(SqlWork<T> work) {
    synchronized (mutex) {
      try {
        try (Statement s = conn.createStatement()) {
          s.execute("BEGIN IMMEDIATE");
        }
        T result;
        try {
          result = work.apply(conn);
          try (Statement s = conn.createStatement()) {
            s.execute("COMMIT");
          }
        } catch (SQLException | RuntimeException e) {
          try (Statement s = conn.createStatement()) {
            s.execute("ROLLBACK");
          } catch (SQLException rollbackFailure) {
            e.addSuppressed(rollbackFailure);
          }
          throw e;
        }
        return result;
      } catch (SQLException e) {
        throw new StateStoreException("state store write failed: " + e.getMessage(), e);
      }
    }
  }

  private static Optional<String> optString(ResultSet rs, String column) throws SQLException {
    return Optional.ofNullable(rs.getString(column));
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    return Instant.parse(rs.getString(column));
  }
}
