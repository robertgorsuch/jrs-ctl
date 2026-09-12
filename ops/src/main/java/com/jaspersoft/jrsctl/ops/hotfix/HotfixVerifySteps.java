package com.jaspersoft.jrsctl.ops.hotfix;

import com.jaspersoft.jrsctl.core.config.ConfigException;
import com.jaspersoft.jrsctl.core.engine.CheckResult;
import com.jaspersoft.jrsctl.core.engine.Context;
import com.jaspersoft.jrsctl.core.engine.StepResult;
import com.jaspersoft.jrsctl.core.event.Event;
import com.jaspersoft.jrsctl.core.event.EventSink;
import com.jaspersoft.jrsctl.core.platform.DiskSpace;
import com.jaspersoft.jrsctl.core.platform.FileOps;
import com.jaspersoft.jrsctl.core.platform.ServiceController;
import com.jaspersoft.jrsctl.core.platform.Trees;
import com.jaspersoft.jrsctl.core.secrets.SecretException;
import com.jaspersoft.jrsctl.core.state.HotfixFile;
import com.jaspersoft.jrsctl.core.state.HotfixInstalled;
import com.jaspersoft.jrsctl.core.state.HotfixState;
import com.jaspersoft.jrsctl.core.state.StateStore;
import com.jaspersoft.jrsctl.jrs.api.JrsUnreachableException;
import com.jaspersoft.jrsctl.jrs.rest.RestException;
import com.jaspersoft.jrsctl.ops.db.JdbcConnector;
import com.jaspersoft.jrsctl.ops.db.JdbcException;
import com.jaspersoft.jrsctl.ops.db.JdbcSettings;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The verify phase of the apply plan (spec §8.2): signature, manifest, preflight and the bundle's
 * own checks. Invariant: every step here does its work in {@code precheck} and mutates nothing, so
 * a refusal ends the run with exit code 2 and the server untouched.
 *
 * <p>One phase of the plan per file, as the upgrade package does it (roadmap item 17). The ids, the
 * phase names and the helpers every phase shares stay in {@link ApplySteps}.
 */
final class HotfixVerifySteps {

  private HotfixVerifySteps() {}

  /** Step 1: unpack the bundle under the run directory and check the signature. */
  static final class VerifySignature extends ApplySteps.ReadOnly {
    VerifySignature(HotfixRuntime rt, ApplyInput in) {
      super(rt, in);
    }

    @Override
    public String id() {
      return ApplySteps.VERIFY_SIGNATURE;
    }

    @Override
    public String title() {
      return "verify the bundle signature";
    }

    @Override
    public String phase() {
      return ApplySteps.VERIFY;
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
        Trees.deleteRecursively(dir);
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
                ApplySteps.AUDIT_ALLOW_UNSIGNED,
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
  static final class ValidateManifest extends ApplySteps.ReadOnly {
    private final ManifestValidator validator = new ManifestValidator();

    ValidateManifest(HotfixRuntime rt, ApplyInput in) {
      super(rt, in);
    }

    @Override
    public String id() {
      return ApplySteps.VALIDATE_MANIFEST;
    }

    @Override
    public String title() {
      return "validate the manifest against schema, server and state store";
    }

    @Override
    public String phase() {
      return ApplySteps.VERIFY;
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
  static final class Preflight extends ApplySteps.ReadOnly {
    Preflight(HotfixRuntime rt, ApplyInput in) {
      super(rt, in);
    }

    @Override
    public String id() {
      return ApplySteps.PREFLIGHT;
    }

    @Override
    public String title() {
      return "preflight the server file system, service and database";
    }

    @Override
    public String phase() {
      return ApplySteps.VERIFY;
    }

    @Override
    public String detail() {
      return "free space per volume for staging, snapshot and landing, write access, locks,"
          + " service state"
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
      // Review finding 1.16: staging, the snapshot and the landing tree may each sit on a volume
      // of their own; every volume must hold what will land on it, plus a margin.
      long snapshotBytes = 0;
      for (Path existing : in.snapshotPaths()) {
        try {
          snapshotBytes += Files.size(existing);
        } catch (IOException e) {
          problems.add("cannot size " + existing + " for the snapshot: " + e.getMessage());
        }
      }
      problems.addAll(
          DiskSpace.problems(
              files,
              List.of(
                  new DiskSpace.Need("staging", in.stagingDir(ctx), payloadBytes),
                  new DiskSpace.Need("snapshot", ctx.home().snapshots(), snapshotBytes),
                  new DiskSpace.Need("landing", base, payloadBytes))));
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
      // review 3.3: say so when the scan cannot see other accounts' handles, rather than let a
      // silent "not locked" read as proof that nothing holds the jars
      files.lockInspectionLimit().ifPresent(limit -> notes.add("lock detection limited: " + limit));
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
  static final class RunChecks extends ApplySteps.ReadOnly {
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
      return post ? ApplySteps.RUN_POSTCHECKS : ApplySteps.RUN_PRECHECKS;
    }

    @Override
    public String title() {
      return post ? "run the manifest postchecks" : "run the manifest prechecks";
    }

    @Override
    public String phase() {
      return post ? ApplySteps.APPLY : ApplySteps.VERIFY;
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
}
