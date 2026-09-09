package com.jaspersoft.jrsctl.ops.hotfix;

import com.jaspersoft.jrsctl.core.config.ConfigException;
import com.jaspersoft.jrsctl.core.engine.CheckResult;
import com.jaspersoft.jrsctl.core.engine.Context;
import com.jaspersoft.jrsctl.core.engine.Step;
import com.jaspersoft.jrsctl.core.engine.StepResult;
import com.jaspersoft.jrsctl.core.event.Event;
import com.jaspersoft.jrsctl.core.event.EventSink;
import com.jaspersoft.jrsctl.core.platform.FileOps;
import com.jaspersoft.jrsctl.core.platform.ServiceController;
import com.jaspersoft.jrsctl.core.secrets.SecretException;
import com.jaspersoft.jrsctl.core.snapshot.Snapshot;
import com.jaspersoft.jrsctl.core.state.HotfixFile;
import com.jaspersoft.jrsctl.core.state.HotfixInstalled;
import com.jaspersoft.jrsctl.core.state.HotfixState;
import com.jaspersoft.jrsctl.core.state.SnapshotRecord;
import com.jaspersoft.jrsctl.core.state.StateStore;
import com.jaspersoft.jrsctl.jrs.api.JrsUnreachableException;
import com.jaspersoft.jrsctl.jrs.rest.RestException;
import com.jaspersoft.jrsctl.ops.db.JdbcConnector;
import com.jaspersoft.jrsctl.ops.db.JdbcException;
import com.jaspersoft.jrsctl.ops.db.JdbcSettings;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The steps of the apply plan (spec §8.2) other than the service steps. Invariants: verify-phase
 * steps do all their work in {@code precheck} and mutate nothing, so a refusal ends the run with
 * exit code 2; every mutating step re-checks the current state before acting, so re-execution
 * converges; {@code AtomicSwap} restores from the snapshot and {@code ApplySql} runs the rollback
 * scripts in reverse when compensated.
 */
final class ApplySteps {

  static final String VERIFY = "verify";
  static final String BACKUP = "backup";
  static final String APPLY = "apply";
  static final String RECORD = "record";

  static final String VERIFY_SIGNATURE = "verify-signature";
  static final String VALIDATE_MANIFEST = "validate-manifest";
  static final String PREFLIGHT = "preflight";
  static final String RUN_PRECHECKS = "run-prechecks";
  static final String SNAPSHOT = "snapshot";
  static final String STAGE_FILES = "stage-files";
  static final String ATOMIC_SWAP = "atomic-swap";
  static final String APPLY_SQL = "apply-sql";
  static final String RUN_POSTCHECKS = "run-postchecks";
  static final String RECORD_INSTALLED = "record-installed";

  static final String AUDIT_ALLOW_UNSIGNED = "hotfix.allow-unsigned";
  static final String AUDIT_APPLIED = "hotfix.applied";
  static final String AUDIT_ROLLED_BACK = "hotfix.rolled-back";

  private ApplySteps() {}

  /** Read-only base for the verify phase. */
  abstract static class ReadOnly implements Step {
    final HotfixRuntime rt;
    final ApplyInput in;

    ReadOnly(HotfixRuntime rt, ApplyInput in) {
      this.rt = rt;
      this.in = in;
    }

    @Override
    public boolean mutating() {
      return false;
    }

    @Override
    public StepResult execute(Context ctx, EventSink out) {
      return StepResult.ok();
    }

    @Override
    public StepResult compensate(Context ctx, EventSink out) {
      return StepResult.ok();
    }

    HotfixBundle bundle(Context ctx) throws IOException {
      return HotfixBundle.open(in.bundleDir(ctx));
    }

    void log(Context ctx, EventSink out, Event.Log.Level level, String message) {
      out.emit(
          new Event.Log(
              rt.clock().instant(), ctx.runId(), Optional.of(id()), phase(), level, message));
    }
  }

  /** Step 1: unpack the bundle under the run directory and check the signature. */
  static final class VerifySignature extends ReadOnly {
    VerifySignature(HotfixRuntime rt, ApplyInput in) {
      super(rt, in);
    }

    @Override
    public String id() {
      return VERIFY_SIGNATURE;
    }

    @Override
    public String title() {
      return "verify the bundle signature";
    }

    @Override
    public String phase() {
      return VERIFY;
    }

    @Override
    public String detail() {
      return "Ed25519 over manifest.json; keys in "
          + rt.home().trustedKeys()
          + (in.allowUnsigned() ? "; --allow-unsigned" : "");
    }

    @Override
    public CheckResult precheck(Context ctx) {
      HotfixBundle bundle;
      try {
        Path dir = in.bundleDir(ctx);
        HotfixBundle.deleteRecursively(dir);
        bundle = HotfixBundle.extract(in.bundle(), dir);
      } catch (IOException e) {
        return CheckResult.fail(
            "cannot unpack " + in.bundle() + ": " + e.getMessage(),
            "check that the bundle is a readable jrsctl hotfix ZIP");
      }
      BundleVerifier.Signature signature = rt.verifier().signature(bundle);
      if (signature.valid()) {
        return CheckResult.pass();
      }
      String what =
          signature.present()
              ? "the bundle signature matches no trusted key"
              : "the bundle carries no SIGNATURE";
      if (in.allowUnsigned()) {
        return CheckResult.warn(what + "; accepted because --allow-unsigned was given");
      }
      return CheckResult.fail(
          what,
          "add the signer's public key with `jrsctl keys add <name> <file>` or re-run with"
              + " --allow-unsigned");
    }

    @Override
    public StepResult execute(Context ctx, EventSink out) {
      try {
        BundleVerifier.Signature signature = rt.verifier().signature(bundle(ctx));
        if (signature.valid()) {
          log(
              ctx,
              out,
              Event.Log.Level.INFO,
              "signed by " + signature.signedBy().map(k -> k.name()).orElse("?"));
          return StepResult.ok();
        }
        if (!in.allowUnsigned()) {
          return Failures.recoverable(
              "the bundle is not signed by a trusted key", "re-run with --allow-unsigned");
        }
        rt.store()
            .audit(
                rt.actor(),
                AUDIT_ALLOW_UNSIGNED,
                in.manifest().id() + " from " + in.bundle() + " in run " + ctx.runId());
        log(ctx, out, Event.Log.Level.WARN, "unsigned bundle accepted (--allow-unsigned, audited)");
        return StepResult.ok();
      } catch (IOException e) {
        return Failures.recoverable(
            "cannot read the unpacked bundle: " + e.getMessage(), "re-run the command");
      }
    }
  }

  /** Step 2: schema, hashes, applicability, dependencies, conflicts and file overlap. */
  static final class ValidateManifest extends ReadOnly {
    private final ManifestValidator validator = new ManifestValidator();

    ValidateManifest(HotfixRuntime rt, ApplyInput in) {
      super(rt, in);
    }

    @Override
    public String id() {
      return VALIDATE_MANIFEST;
    }

    @Override
    public String title() {
      return "validate the manifest against schema, server and state store";
    }

    @Override
    public String phase() {
      return VERIFY;
    }

    @Override
    public String detail() {
      return "schema, file hashes, applicability, requires/conflicts, file overlap";
    }

    @Override
    public CheckResult precheck(Context ctx) {
      List<String> problems = new ArrayList<>();
      HotfixBundle bundle;
      try {
        bundle = bundle(ctx);
      } catch (IOException e) {
        return CheckResult.fail(
            "cannot read the unpacked bundle: " + e.getMessage(), "re-run the command");
      }
      Manifest manifest =
          switch (validator.validate(bundle.manifestJson())) {
            case ManifestValidator.Result.Valid v -> v.manifest();
            case ManifestValidator.Result.Invalid i -> {
              problems.addAll(i.problems());
              yield in.manifest();
            }
          };
      if (!manifest.id().equals(in.manifest().id())) {
        problems.add("bundle manifest id changed since the plan was made");
      }
      problems.addAll(rt.verifier().hashProblems(bundle, manifest));
      try {
        problems.addAll(Applicability.check(manifest, rt.identity()));
      } catch (JrsUnreachableException | RestException | ConfigException e) {
        problems.add("server unreachable: " + e.getMessage());
      }
      StateStore store = rt.store();
      Set<String> installed = new HashSet<>();
      for (HotfixInstalled h : store.installedHotfixes()) {
        installed.add(h.id());
      }
      for (String required : manifest.requires()) {
        if (!installed.contains(required)) {
          problems.add("requires " + required + ", which is not installed");
        }
      }
      for (String conflict : manifest.conflicts()) {
        if (installed.contains(conflict)) {
          problems.add("conflicts with installed hotfix " + conflict);
        }
      }
      store
          .hotfix(manifest.id())
          .filter(h -> h.state() == HotfixState.INSTALLED)
          .ifPresent(
              h ->
                  problems.add(
                      manifest.id() + " is already installed (run " + h.installedRunId() + ")"));
      for (HotfixFile owned : store.filesOwnedBy(in.touched())) {
        if (!owned.hotfixId().equals(manifest.id())
            && !manifest.requires().contains(owned.hotfixId())) {
          problems.add(
              owned.path()
                  + " is owned by installed hotfix "
                  + owned.hotfixId()
                  + ", which is not listed in requires");
        }
      }
      if (!manifest.sql().isEmpty() && in.dbType().isEmpty()) {
        problems.add("the manifest carries SQL but the database section is not configured");
      }
      if (problems.isEmpty()) {
        return CheckResult.pass();
      }
      return CheckResult.fail(
          String.join("; ", problems),
          "fix the listed conditions (install required hotfixes, roll back conflicting ones,"
              + " configure database.*, or obtain a bundle for this server)");
    }
  }

  /** Step 3: disk space, write access, locks, service state, restart consistency, database. */
  static final class Preflight extends ReadOnly {
    Preflight(HotfixRuntime rt, ApplyInput in) {
      super(rt, in);
    }

    @Override
    public String id() {
      return PREFLIGHT;
    }

    @Override
    public String title() {
      return "preflight the server file system, service and database";
    }

    @Override
    public String phase() {
      return VERIFY;
    }

    @Override
    public String detail() {
      return "free space >= 2x payload, write access, locks, service state"
          + (in.hasSql() ? ", database connectivity" : "");
    }

    @Override
    public CheckResult precheck(Context ctx) {
      List<String> problems = new ArrayList<>();
      List<String> notes = new ArrayList<>();
      FileOps files = rt.files();
      long payloadBytes = 0;
      for (FileTarget t : in.targets()) {
        if (t.action() != Manifest.Action.DELETE) {
          try {
            payloadBytes += Files.size(in.payload(ctx, t));
          } catch (IOException e) {
            problems.add("payload missing for " + t.manifestPath());
          }
        }
      }
      Path base = in.paths().commonBase();
      try {
        long free = files.freeSpaceBytes(base);
        if (free < 2 * payloadBytes) {
          problems.add(
              "only " + free + " bytes free under " + base + "; need " + (2 * payloadBytes));
        }
      } catch (IOException e) {
        problems.add("cannot determine free space under " + base + ": " + e.getMessage());
      }
      Set<Path> dirs = new HashSet<>();
      for (Path p : in.touched()) {
        dirs.add(nearestExistingDir(p.getParent()));
      }
      for (Path dir : dirs) {
        if (!files.isWritable(dir)) {
          problems.add(dir + " is not writable");
        }
      }
      boolean needsStop = in.manifest().touchesWebInf();
      if (needsStop && !in.restartRequired()) {
        problems.add(
            "files under WEB-INF/lib or WEB-INF/classes require \"restart\": \"required\"");
      }
      Optional<ServiceController.State> state = Optional.empty();
      if (in.restartRequired()) {
        try {
          ServiceController controller = rt.controller();
          state = Optional.of(controller.state());
          if (state.get() == ServiceController.State.UNKNOWN) {
            problems.add("service state cannot be determined (" + controller.describe() + ")");
          }
        } catch (ConfigException e) {
          problems.add(e.getMessage() + " (" + e.remediation() + ")");
        }
      }
      boolean running = state.map(s -> s == ServiceController.State.RUNNING).orElse(true);
      for (Path p : in.touched()) {
        if (Files.isRegularFile(p) && files.isLocked(p)) {
          String holder = files.lockHolder(p).map(h -> " by " + h).orElse("");
          if (in.restartRequired() && running) {
            notes.add(p + " is locked" + holder + "; the service will be stopped first");
          } else {
            problems.add(p + " is locked" + holder);
          }
        }
      }
      if (in.hasSql()) {
        Optional<JdbcSettings> settings = JdbcSettings.from(rt.config());
        if (settings.isEmpty()) {
          problems.add("database.type and database.url are required for SQL hotfixes");
        } else {
          try (JdbcConnector.Session session =
              settings.get().open(rt.jdbc(), rt.services().secrets())) {
            if (session.queryHasRow(settings.get().probeSql())) {
              notes.add("database: " + session.product());
            } else {
              problems.add("database: probe query returned no row from " + session.product());
            }
          } catch (JdbcException | SecretException e) {
            problems.add("database: " + e.getMessage());
          }
        }
      }
      if (!problems.isEmpty()) {
        return CheckResult.fail(
            String.join("; ", problems),
            "free disk space, fix permissions, stop the process holding the files, or fix"
                + " service.* and database.* in config.yaml");
      }
      return notes.isEmpty() ? CheckResult.pass() : CheckResult.warn(String.join("; ", notes));
    }

    private static Path nearestExistingDir(Path dir) {
      Path p = dir;
      while (p != null && !Files.isDirectory(p)) {
        p = p.getParent();
      }
      return p == null ? dir : p;
    }
  }

  /** Steps 4 and 11: manifest prechecks (read-only, in precheck) and postchecks (in execute). */
  static final class RunChecks extends ReadOnly {
    private final boolean post;

    RunChecks(HotfixRuntime rt, ApplyInput in, boolean post) {
      super(rt, in);
      this.post = post;
    }

    private List<Manifest.Check> checks() {
      return post ? in.manifest().postchecks() : in.manifest().prechecks();
    }

    @Override
    public String id() {
      return post ? RUN_POSTCHECKS : RUN_PRECHECKS;
    }

    @Override
    public String title() {
      return post ? "run the manifest postchecks" : "run the manifest prechecks";
    }

    @Override
    public String phase() {
      return post ? APPLY : VERIFY;
    }

    @Override
    public String detail() {
      List<Manifest.Check> checks = checks();
      if (checks.isEmpty()) {
        return "none declared";
      }
      List<String> parts = new ArrayList<>();
      for (Manifest.Check c : checks) {
        parts.add(c.describe());
      }
      return String.join(", ", parts);
    }

    @Override
    public CheckResult precheck(Context ctx) {
      if (post) {
        return CheckResult.pass();
      }
      List<String> failures = runner().run(checks());
      return failures.isEmpty()
          ? CheckResult.pass()
          : CheckResult.fail(String.join("; ", failures), "fix the failed prechecks, then re-run");
    }

    @Override
    public StepResult execute(Context ctx, EventSink out) {
      if (!post) {
        return StepResult.ok();
      }
      List<String> failures = runner().run(checks());
      return failures.isEmpty()
          ? StepResult.ok()
          : Failures.recoverable(
              "postchecks failed: " + String.join("; ", failures),
              "the run is rolled back; check the server log");
    }

    private CheckRunner runner() {
      return new CheckRunner(in.paths(), rt.files(), rt.http());
    }
  }

  /** Step 5: snapshot every file that will be replaced or deleted. */
  static final class TakeSnapshot extends ReadOnly {
    TakeSnapshot(HotfixRuntime rt, ApplyInput in) {
      super(rt, in);
    }

    @Override
    public String id() {
      return SNAPSHOT;
    }

    @Override
    public String title() {
      return "snapshot the files this hotfix replaces or deletes";
    }

    @Override
    public String phase() {
      return BACKUP;
    }

    @Override
    public String detail() {
      return in.snapshotPaths().size()
          + " file(s) -> "
          + rt.home().snapshots().resolve("{runId}").resolve(SNAPSHOT);
    }

    @Override
    public CheckResult precheck(Context ctx) {
      return CheckResult.pass();
    }

    @Override
    public StepResult execute(Context ctx, EventSink out) {
      List<Path> paths = in.snapshotPaths().stream().filter(Files::isRegularFile).toList();
      try {
        Snapshot snapshot =
            rt.snapshots().create(ctx.runId(), SNAPSHOT, paths, in.paths().commonBase());
        rt.store()
            .recordSnapshot(
                new SnapshotRecord(
                    ctx.runId() + "/" + SNAPSHOT,
                    ctx.runId(),
                    SNAPSHOT,
                    snapshot.dir(),
                    rt.files().sha256(snapshot.manifestFile()),
                    Optional.of(in.manifest().id())));
        log(ctx, out, Event.Log.Level.INFO, paths.size() + " file(s) saved to " + snapshot.dir());
        return StepResult.ok();
      } catch (IOException | RuntimeException e) {
        return Failures.recoverable(
            "cannot snapshot: " + Failures.describe(e),
            "check free space and permissions under " + rt.home().snapshots());
      }
    }
  }

  /** Step 7: copy payload files into the run's staging directory and verify their hashes. */
  static final class StageFiles extends ReadOnly {
    StageFiles(HotfixRuntime rt, ApplyInput in) {
      super(rt, in);
    }

    @Override
    public String id() {
      return STAGE_FILES;
    }

    @Override
    public String title() {
      return "stage payload files";
    }

    @Override
    public String phase() {
      return APPLY;
    }

    @Override
    public String detail() {
      return "runs/{runId}/staging, sha256 verified";
    }

    @Override
    public CheckResult precheck(Context ctx) {
      return CheckResult.pass();
    }

    @Override
    public StepResult execute(Context ctx, EventSink out) {
      FileOps files = rt.files();
      for (FileTarget t : in.targets()) {
        if (t.action() == Manifest.Action.DELETE) {
          continue;
        }
        ctx.cancel().checkpoint();
        Path staged = in.staged(ctx, t);
        String expected = t.after().orElse("");
        try {
          if (FileTarget.hashOf(files, staged).map(expected::equals).orElse(false)) {
            continue;
          }
          Files.createDirectories(staged.getParent());
          Files.copy(in.payload(ctx, t), staged, StandardCopyOption.REPLACE_EXISTING);
          String actual = files.sha256(staged);
          if (!actual.equals(expected)) {
            return Failures.recoverable(
                "staged " + t.manifestPath() + " hashes to " + actual + ", expected " + expected,
                "the bundle is corrupt; obtain it again");
          }
        } catch (IOException | UncheckedIOException e) {
          return Failures.recoverable(
              "cannot stage " + t.manifestPath() + ": " + e.getMessage(),
              "check free space under " + in.stagingDir(ctx));
        }
      }
      return StepResult.ok();
    }

    @Override
    public StepResult compensate(Context ctx, EventSink out) {
      try {
        HotfixBundle.deleteRecursively(in.stagingDir(ctx));
        return StepResult.ok();
      } catch (IOException e) {
        return Failures.recoverable(
            "cannot remove staging: " + e.getMessage(), "delete " + in.stagingDir(ctx));
      }
    }
  }

  /** Step 8: rename staged files into place, delete listed files; compensation restores. */
  static final class AtomicSwap implements Step {
    private final HotfixRuntime rt;
    private final ApplyInput in;

    AtomicSwap(HotfixRuntime rt, ApplyInput in) {
      this.rt = rt;
      this.in = in;
    }

    @Override
    public String id() {
      return ATOMIC_SWAP;
    }

    @Override
    public String title() {
      return "swap " + in.targets().size() + " file(s) into place";
    }

    @Override
    public String phase() {
      return APPLY;
    }

    @Override
    public String detail() {
      return "per-file rename, ACLs preserved; files already at the target hash are skipped";
    }

    @Override
    public CheckResult precheck(Context ctx) {
      FileOps files = rt.files();
      List<String> locked = new ArrayList<>();
      for (Path p : in.touched()) {
        if (Files.isRegularFile(p) && files.isLocked(p)) {
          locked.add(p + files.lockHolder(p).map(h -> " (held by " + h + ")").orElse(""));
        }
      }
      return locked.isEmpty()
          ? CheckResult.pass()
          : CheckResult.fail(
              "still locked after the service stop: " + String.join(", ", locked),
              "end the process holding the file, then re-run");
    }

    @Override
    public StepResult execute(Context ctx, EventSink out) {
      FileOps files = rt.files();
      for (FileTarget t : in.targets()) {
        ctx.cancel().checkpoint();
        try {
          switch (t.action()) {
            case ADD, REPLACE -> {
              String expected = t.after().orElse("");
              if (!FileTarget.hashOf(files, t.target()).map(expected::equals).orElse(false)) {
                Path staged = in.staged(ctx, t);
                if (!Files.isRegularFile(staged)) {
                  return Failures.recoverable(
                      "staged copy of " + t.manifestPath() + " is missing",
                      "re-run; stage-files recreates it",
                      List.of(t.target()),
                      List.of());
                }
                Files.createDirectories(t.target().getParent());
                files.atomicReplace(staged, t.target());
                String actual = files.sha256(t.target());
                if (!actual.equals(expected)) {
                  return Failures.recoverable(
                      t.target() + " hashes to " + actual + " after the swap, expected " + expected,
                      "the run is rolled back from the snapshot",
                      List.of(t.target()),
                      backups(ctx));
                }
              }
              for (FileTarget.Sibling s : t.replaces()) {
                Files.deleteIfExists(s.path());
              }
            }
            case DELETE -> Files.deleteIfExists(t.target());
          }
        } catch (IOException | UncheckedIOException e) {
          return Failures.recoverable(
              "cannot swap " + t.target() + ": " + e.getMessage(),
              "the run is rolled back from the snapshot",
              List.of(t.target()),
              backups(ctx));
        }
      }
      return StepResult.ok();
    }

    @Override
    public StepResult compensate(Context ctx, EventSink out) {
      List<Path> affected = new ArrayList<>();
      try {
        for (FileTarget t : in.targets()) {
          if (t.action() != Manifest.Action.DELETE && !t.existedBefore()) {
            affected.add(t.target());
            Files.deleteIfExists(t.target());
          }
        }
        Optional<Snapshot> snapshot = rt.snapshots().find(ctx.runId(), SNAPSHOT);
        if (snapshot.isPresent()) {
          rt.snapshots().restore(snapshot.get());
        }
        return StepResult.ok();
      } catch (IOException | RuntimeException e) {
        return Failures.recoverable(
            "cannot restore the original files: " + Failures.describe(e),
            "restore the snapshot by hand",
            affected,
            backups(ctx));
      }
    }

    private List<Path> backups(Context ctx) {
      return List.of(rt.home().snapshots().resolve(ctx.runId()).resolve(SNAPSHOT));
    }
  }

  /** Step 9: run the manifest's SQL scripts; compensation runs the rollback scripts in reverse. */
  static final class ApplySql implements Step {
    private final HotfixRuntime rt;
    private final ApplyInput in;

    ApplySql(HotfixRuntime rt, ApplyInput in) {
      this.rt = rt;
      this.in = in;
    }

    @Override
    public String id() {
      return APPLY_SQL;
    }

    @Override
    public String title() {
      return "apply " + in.sqlScripts().size() + " SQL script(s)";
    }

    @Override
    public String phase() {
      return APPLY;
    }

    @Override
    public String detail() {
      String db = in.dbType().map(t -> t.yamlValue()).orElse("?");
      return irreversible()
          ? "via JDBC on " + db + "; IRREVERSIBLE: " + in.manifest().rollbackNote().orElse("")
          : "via JDBC on " + db + "; rollback scripts run in reverse on failure";
    }

    /**
     * Irreversible only when the manifest says so: the author has declared there is no rollback
     * script and supplied a rollbackNote that the plan summary shows to the operator.
     */
    @Override
    public boolean irreversible() {
      return in.manifest().rollback() == Manifest.Rollback.IRREVERSIBLE;
    }

    @Override
    public CheckResult precheck(Context ctx) {
      if (in.dbType().isEmpty() || JdbcSettings.from(rt.config()).isEmpty()) {
        return CheckResult.fail(
            "database.type and database.url are not configured",
            "set the database section in config.yaml");
      }
      return CheckResult.pass();
    }

    @Override
    public StepResult execute(Context ctx, EventSink out) {
      List<String> files = new ArrayList<>();
      for (Manifest.SqlEntry s : in.sqlScripts()) {
        files.add(s.file());
      }
      return run(ctx, out, files);
    }

    @Override
    public StepResult compensate(Context ctx, EventSink out) {
      if (irreversible()) {
        return StepResult.ok();
      }
      List<String> files = new ArrayList<>();
      for (int i = in.sqlScripts().size() - 1; i >= 0; i--) {
        in.sqlScripts().get(i).rollbackFile().ifPresent(files::add);
      }
      return run(ctx, out, files);
    }

    private StepResult run(Context ctx, EventSink out, List<String> scripts) {
      return SqlRunner.run(rt, ctx, out, id(), phase(), in.bundleDir(ctx), scripts);
    }
  }

  /** Step 12: record the installation in the state store. */
  static final class RecordInstalled implements Step {
    private final HotfixRuntime rt;
    private final ApplyInput in;

    RecordInstalled(HotfixRuntime rt, ApplyInput in) {
      this.rt = rt;
      this.in = in;
    }

    @Override
    public String id() {
      return RECORD_INSTALLED;
    }

    @Override
    public String title() {
      return "record " + in.manifest().id() + " as installed";
    }

    @Override
    public String phase() {
      return RECORD;
    }

    @Override
    public String detail() {
      return "hotfixes_installed, hotfix_files (" + rows(Optional.empty()).size() + " rows), audit";
    }

    @Override
    public CheckResult precheck(Context ctx) {
      return CheckResult.pass();
    }

    @Override
    public StepResult execute(Context ctx, EventSink out) {
      StateStore store = rt.store();
      String id = in.manifest().id();
      Optional<HotfixInstalled> existing = store.hotfix(id);
      if (existing.isPresent()) {
        HotfixInstalled h = existing.get();
        if (h.state() == HotfixState.INSTALLED) {
          if (h.installedRunId().equals(ctx.runId())) {
            return StepResult.ok();
          }
          return Failures.recoverable(
              id + " is already recorded as installed by run " + h.installedRunId(),
              "roll back the earlier installation first");
        }
        // The id is the primary key; a rolled-back row is superseded by this installation.
        store.executeUpdate("DELETE FROM hotfix_files WHERE hotfix_id='" + id + "'");
        store.executeUpdate("DELETE FROM hotfixes_installed WHERE id='" + id + "'");
      }
      store.recordHotfixInstalled(
          new HotfixInstalled(
              id,
              in.manifest().version(),
              in.manifest().title(),
              ctx.runId(),
              Optional.of(ctx.runId() + "/" + SNAPSHOT),
              HotfixState.INSTALLED,
              rt.clock().instant()),
          rows(Optional.of(id)));
      store.audit(
          rt.actor(),
          AUDIT_APPLIED,
          id + " version " + in.manifest().version() + " in run " + ctx.runId());
      return StepResult.ok();
    }

    @Override
    public StepResult compensate(Context ctx, EventSink out) {
      StateStore store = rt.store();
      String id = in.manifest().id();
      Optional<HotfixInstalled> existing = store.hotfix(id);
      if (existing.isPresent()
          && existing.get().state() == HotfixState.INSTALLED
          && existing.get().installedRunId().equals(ctx.runId())) {
        store.updateHotfixState(id, HotfixState.ROLLED_BACK);
        store.audit(
            rt.actor(), AUDIT_ROLLED_BACK, id + " (compensation of run " + ctx.runId() + ")");
      }
      return StepResult.ok();
    }

    private List<HotfixFile> rows(Optional<String> id) {
      String hotfixId = id.orElse(in.manifest().id());
      List<HotfixFile> rows = new ArrayList<>();
      for (FileTarget t : in.targets()) {
        String action =
            switch (t.action()) {
              case ADD -> "add";
              case REPLACE -> "replace";
              case DELETE -> "delete";
            };
        rows.add(new HotfixFile(hotfixId, t.target(), action, t.before(), t.after()));
        for (FileTarget.Sibling s : t.replaces()) {
          if (s.before().isPresent()) {
            rows.add(new HotfixFile(hotfixId, s.path(), "delete", s.before(), Optional.empty()));
          }
        }
      }
      return List.copyOf(rows);
    }
  }
}
