package com.jaspersoft.jrsctl.ops.hotfix;

import com.fasterxml.jackson.databind.JsonNode;
import com.jaspersoft.jrsctl.core.config.Config;
import com.jaspersoft.jrsctl.core.config.ConfigException;
import com.jaspersoft.jrsctl.core.config.ConfigWriter;
import com.jaspersoft.jrsctl.core.engine.Plan;
import com.jaspersoft.jrsctl.core.engine.PlanFingerprint;
import com.jaspersoft.jrsctl.core.engine.PlanSummary;
import com.jaspersoft.jrsctl.core.engine.RunIds;
import com.jaspersoft.jrsctl.core.engine.Sleeper;
import com.jaspersoft.jrsctl.core.engine.Step;
import com.jaspersoft.jrsctl.core.json.Json;
import com.jaspersoft.jrsctl.core.keys.KeyRing;
import com.jaspersoft.jrsctl.core.platform.Trees;
import com.jaspersoft.jrsctl.core.secrets.SecretRef;
import com.jaspersoft.jrsctl.core.snapshot.SnapshotStore;
import com.jaspersoft.jrsctl.core.state.HotfixFile;
import com.jaspersoft.jrsctl.core.state.HotfixInstalled;
import com.jaspersoft.jrsctl.core.state.HotfixState;
import com.jaspersoft.jrsctl.core.state.StateStore;
import com.jaspersoft.jrsctl.jrs.api.JrsUnreachableException;
import com.jaspersoft.jrsctl.jrs.api.ServerIdentity;
import com.jaspersoft.jrsctl.jrs.rest.RestException;
import com.jaspersoft.jrsctl.ops.Services;
import com.jaspersoft.jrsctl.ops.db.DefaultJdbcConnector;
import com.jaspersoft.jrsctl.ops.service.ServiceSteps;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * The hotfix subsystem entry point (spec §8): builds, verifies, plans the application and the
 * rollback of signed bundles, and lists what is installed. Invariants: nothing here mutates the
 * server; every mutation lives in a {@link Step} of the returned {@link Plan}; a plan is refused
 * with {@link HotfixException} when the bundle is unreadable, its manifest invalid, its hashes
 * wrong or its signature untrusted (unless {@code allowUnsigned}), and a rollback plan is refused
 * for unknown, non-installed or LIFO-blocked ids (unless {@code cascade}); fingerprints cover the
 * server identity, the bundle, every target file and the effective configuration.
 */
public final class DefaultHotfixOperations implements HotfixOperations {

  static final String APPLY_OPERATION = "hotfix.apply";
  static final String ROLLBACK_OPERATION = "hotfix.rollback";
  static final String STRATEGY = "snapshot";

  private final HotfixRuntime rt;
  private final ManifestValidator validator = new ManifestValidator();

  public DefaultHotfixOperations(Services services) {
    this(
        new HotfixRuntime(
            services,
            new SnapshotStore(services.home(), services.platform().files(), services.clock()),
            new DefaultJdbcConnector(),
            new KeyRing(services.home()),
            HttpProbe.rest(services),
            Sleeper.system()));
  }

  DefaultHotfixOperations(HotfixRuntime rt) {
    this.rt = Objects.requireNonNull(rt, "rt");
  }

  @Override
  public Path build(Path bundleDir, SecretRef privateKeyRef, Path out) {
    return new HotfixBuilder(rt.files(), rt.services().secrets())
        .build(bundleDir, privateKeyRef, out);
  }

  @Override
  public VerifyReport verify(Path bundle) {
    return withBundle(
        bundle,
        b -> {
          BundleVerifier.Signature signature = rt.verifier().signature(b);
          Optional<String> signedBy = signature.signedBy().map(KeyRing.TrustedKey::name);
          List<String> hashProblems = new ArrayList<>();
          List<String> applicability = new ArrayList<>();
          String id = "";
          String title = "";
          switch (validator.validate(b.manifestJson())) {
            case ManifestValidator.Result.Valid v -> {
              id = v.manifest().id();
              title = v.manifest().title();
              hashProblems.addAll(rt.verifier().hashProblems(b, v.manifest()));
              applicability.addAll(applicability(v.manifest()));
            }
            case ManifestValidator.Result.Invalid i -> {
              for (String p : i.problems()) {
                hashProblems.add("manifest: " + p);
              }
              JsonNode tree = lenientTree(b.manifestJson());
              id = tree.path("id").asText("");
              title = tree.path("title").asText("");
            }
          }
          return new VerifyReport(
              signature.valid(),
              signedBy,
              hashProblems.isEmpty(),
              hashProblems,
              applicability.isEmpty(),
              applicability,
              id,
              title);
        });
  }

  @Override
  public Plan planApply(Path bundle, ApplyOptions options) {
    Objects.requireNonNull(options, "options");
    return withBundle(bundle, b -> planApply(bundle, b, options));
  }

  private Plan planApply(Path bundle, HotfixBundle b, ApplyOptions options) {
    List<String> warnings = new ArrayList<>();
    BundleVerifier.Signature signature = rt.verifier().signature(b);
    if (!signature.valid()) {
      if (!options.allowUnsigned()) {
        throw new HotfixException(
            HotfixException.SIGNATURE,
            signature.present()
                ? "the bundle signature matches no trusted key"
                : "the bundle carries no SIGNATURE",
            "add the signer's public key with `jrsctl keys add <name> <file>` or re-run with"
                + " --allow-unsigned");
      }
      warnings.add("the bundle is not signed by a trusted key; accepted with --allow-unsigned");
    }
    Manifest manifest =
        switch (validator.validate(b.manifestJson())) {
          case ManifestValidator.Result.Valid v -> v.manifest();
          case ManifestValidator.Result.Invalid i ->
              throw new HotfixException(
                  HotfixException.PRECHECK,
                  "manifest is not valid:\n  " + String.join("\n  ", i.problems()),
                  "obtain a bundle with a valid manifest");
        };
    List<String> hashProblems = rt.verifier().hashProblems(b, manifest);
    if (!hashProblems.isEmpty()) {
      throw new HotfixException(
          HotfixException.SIGNATURE,
          "bundle content does not match the manifest:\n  " + String.join("\n  ", hashProblems),
          "obtain the bundle again; it is corrupt or tampered with");
    }
    Config config = rt.config();
    HotfixPaths paths = HotfixPaths.from(config, rt.services().platform());
    List<FileTarget> targets = FileTarget.resolve(manifest, paths, rt.files());
    Optional<Config.DatabaseType> dbType = config.database().type();
    List<Manifest.SqlEntry> sqlScripts = dbType.map(manifest::sqlFor).orElse(List.of());
    ApplyInput in =
        new ApplyInput(
            bundle.toAbsolutePath().normalize(),
            hash(bundle),
            manifest,
            paths,
            targets,
            options.allowUnsigned(),
            dbType,
            sqlScripts);

    Optional<ServerIdentity> identity = identity();
    identity.ifPresentOrElse(
        i -> warnings.addAll(Applicability.check(manifest, i)),
        () -> warnings.add("server unreachable at plan time; validate-manifest will refuse"));
    if (!manifest.sql().isEmpty() && dbType.isEmpty()) {
      warnings.add("the manifest carries SQL but the database section is not configured");
    }
    if (manifest.rollback() == Manifest.Rollback.IRREVERSIBLE) {
      warnings.add(
          "SQL changes are irreversible: " + manifest.rollbackNote().orElse("no rollbackNote"));
      warnings.add("database rollback is the operator's responsibility");
    }

    List<Step> steps = new ArrayList<>();
    steps.add(new ApplySteps.VerifySignature(rt, in));
    steps.add(new ApplySteps.ValidateManifest(rt, in));
    steps.add(new ApplySteps.Preflight(rt, in));
    steps.add(new ApplySteps.RunChecks(rt, in, false));
    steps.add(new ApplySteps.TakeSnapshot(rt, in));
    if (in.restartRequired()) {
      steps.add(ServiceSteps.stop(rt, ApplySteps.APPLY, ServiceSteps.STOP));
    }
    steps.add(new ApplySteps.StageFiles(rt, in));
    steps.add(new ApplySteps.AtomicSwap(rt, in));
    if (in.hasSql()) {
      steps.add(new ApplySteps.ApplySql(rt, in));
    }
    if (in.restartRequired()) {
      steps.add(ServiceSteps.start(rt, ApplySteps.APPLY, ServiceSteps.START));
      steps.add(ServiceSteps.waitForServer(rt, ApplySteps.APPLY, ServiceSteps.WAIT));
    }
    steps.add(new ApplySteps.RunChecks(rt, in, true));
    steps.add(new ApplySteps.RecordInstalled(rt, in));

    Path snapshotDir = rt.home().snapshots().resolve("{runId}").resolve(ApplySteps.SNAPSHOT);
    Map<String, String> rollbackPoints = new LinkedHashMap<>();
    rollbackPoints.put(ApplySteps.VERIFY, "nothing mutated");
    rollbackPoints.put(ApplySteps.BACKUP, "snapshot written, server untouched");
    rollbackPoints.put(
        ApplySteps.APPLY,
        "restore " + snapshotDir + (in.restartRequired() ? ", restart service" : ""));
    rollbackPoints.put(ApplySteps.RECORD, "state store row marked ROLLED_BACK");
    PlanSummary summary =
        new PlanSummary(
            APPLY_OPERATION,
            manifest.id() + " " + manifest.title(),
            in.touched(),
            List.of(),
            in.restartRequired(),
            List.of(snapshotDir),
            rollbackPoints,
            STRATEGY,
            warnings);

    Map<String, String> inputs = new LinkedHashMap<>();
    inputs.put("server", identity.map(ServerIdentity::fingerprintInput).orElse("unreachable"));
    inputs.put("bundle", in.bundleSha256());
    inputs.put("config", configHash(config));
    for (FileTarget t : targets) {
      inputs.put("target:" + t.manifestPath(), t.before().orElse("absent"));
      for (FileTarget.Sibling s : t.replaces()) {
        inputs.put(
            "target:" + t.manifestPath() + "/replaces/" + s.path().getFileName(),
            s.before().orElse("absent"));
      }
    }
    return new Plan(
        "hotfix-apply-" + RunIds.next(rt.clock()), steps, summary, PlanFingerprint.of(inputs));
  }

  @Override
  public Plan planRollback(String hotfixId, RollbackOptions options) {
    Objects.requireNonNull(hotfixId, "hotfixId");
    Objects.requireNonNull(options, "options");
    StateStore store = rt.store();
    HotfixInstalled target =
        store
            .hotfix(hotfixId)
            .orElseThrow(
                () ->
                    new HotfixException(
                        HotfixException.PRECHECK,
                        "unknown hotfix " + hotfixId,
                        "run jrsctl hotfix list"));
    if (target.state() != HotfixState.INSTALLED) {
      throw new HotfixException(
          HotfixException.PRECHECK,
          hotfixId + " is not installed (state " + target.state() + ")",
          "run jrsctl hotfix list");
    }
    List<HotfixInstalled> installed = store.installedHotfixes();
    Map<String, Integer> order = new LinkedHashMap<>();
    for (int i = 0; i < installed.size(); i++) {
      order.put(installed.get(i).id(), i);
    }
    List<String> chain = chain(store, target, order, options.cascade());

    List<Step> steps = new ArrayList<>();
    List<Path> touched = new ArrayList<>();
    List<Path> backups = new ArrayList<>();
    List<String> warnings = new ArrayList<>();
    Map<String, String> rollbackPoints = new LinkedHashMap<>();
    Map<String, String> inputs = new LinkedHashMap<>();
    boolean restart = false;
    Optional<Config.DatabaseType> dbType = rt.config().database().type();
    for (String id : chain) {
      HotfixInstalled hotfix = store.hotfix(id).orElseThrow();
      List<HotfixFile> files = store.hotfixFiles(id);
      String suffix = chain.size() > 1 ? ":" + id : "";
      String phase = chain.size() > 1 ? RollbackSteps.PHASE + ":" + id : RollbackSteps.PHASE;
      Path bundleDir = rt.home().runDir(hotfix.installedRunId()).resolve(ApplyInput.BUNDLE_DIR);
      Optional<Manifest> manifest = storedManifest(bundleDir);
      List<Manifest.SqlEntry> sql = manifest.flatMap(m -> dbType.map(m::sqlFor)).orElse(List.of());
      RollbackSteps.Input in =
          new RollbackSteps.Input(hotfix, files, manifest, bundleDir, sql, phase, suffix);
      boolean irreversible =
          manifest.map(m -> m.rollback() == Manifest.Rollback.IRREVERSIBLE).orElse(false);
      if (manifest.isEmpty()) {
        warnings.add(
            id + ": no bundle copy under " + bundleDir + "; SQL rollback scripts unavailable");
      }
      if (irreversible) {
        warnings.add(
            id
                + ": SQL changes were declared irreversible: "
                + manifest.flatMap(Manifest::rollbackNote).orElse(""));
        warnings.add("database rollback is the operator's responsibility");
      }
      boolean stop = in.needsServiceStop();
      restart |= stop;
      if (stop) {
        steps.add(ServiceSteps.stop(rt, phase, ServiceSteps.STOP + suffix));
      }
      steps.add(new RollbackSteps.RestoreSnapshot(rt, in));
      if (!irreversible && !in.rollbackScripts().isEmpty()) {
        steps.add(new RollbackSteps.RunSqlRollback(rt, in));
      }
      if (stop) {
        steps.add(ServiceSteps.start(rt, phase, ServiceSteps.START + suffix));
        steps.add(ServiceSteps.waitForServer(rt, phase, ServiceSteps.WAIT + suffix));
      }
      steps.add(new RollbackSteps.RecordRolledBack(rt, in));
      touched.addAll(in.touched());
      backups.add(
          rt.home().snapshots().resolve(hotfix.installedRunId()).resolve(ApplySteps.SNAPSHOT));
      rollbackPoints.put(
          phase,
          "re-apply "
              + id
              + " from snapshots/{runId}/"
              + in.preRollbackStepId()
              + (stop ? ", restart service" : ""));
      inputs.put("hotfix:" + id, hotfix.installedRunId());
      for (HotfixFile f : files) {
        inputs.put("file:" + f.path(), FileTarget.hashOf(rt.files(), f.path()).orElse("absent"));
      }
    }
    Optional<ServerIdentity> identity = identity();
    inputs.put("server", identity.map(ServerIdentity::fingerprintInput).orElse("unreachable"));
    inputs.put("config", configHash(rt.config()));
    PlanSummary summary =
        new PlanSummary(
            ROLLBACK_OPERATION,
            String.join(", ", chain),
            touched,
            List.of(),
            restart,
            backups,
            rollbackPoints,
            STRATEGY,
            warnings);
    return new Plan(
        "hotfix-rollback-" + RunIds.next(rt.clock()), steps, summary, PlanFingerprint.of(inputs));
  }

  @Override
  public List<HotfixInstalled> list() {
    return rt.store().hotfixes();
  }

  /** Ids to roll back, newest first, ending with {@code target}. */
  private static List<String> chain(
      StateStore store, HotfixInstalled target, Map<String, Integer> order, boolean cascade) {
    Set<String> selected = new LinkedHashSet<>();
    List<String> pending = new ArrayList<>();
    pending.add(target.id());
    List<String> directBlockers = new ArrayList<>();
    while (!pending.isEmpty()) {
      String id = pending.remove(pending.size() - 1);
      if (!selected.add(id)) {
        continue;
      }
      int position = order.getOrDefault(id, -1);
      List<Path> paths = store.hotfixFiles(id).stream().map(HotfixFile::path).toList();
      Set<String> blockers = new LinkedHashSet<>();
      for (HotfixFile owned : store.filesOwnedBy(paths)) {
        String other = owned.hotfixId();
        if (!other.equals(id) && order.getOrDefault(other, -1) > position) {
          blockers.add(other);
        }
      }
      if (id.equals(target.id())) {
        directBlockers.addAll(blockers);
      }
      pending.addAll(blockers);
    }
    if (!directBlockers.isEmpty() && !cascade) {
      throw new HotfixException(
          HotfixException.PRECHECK,
          "rollback of "
              + target.id()
              + " is blocked by later hotfixes owning the same files: "
              + String.join(", ", directBlockers),
          "roll those back first, or re-run with --cascade");
    }
    List<String> ordered = new ArrayList<>(selected);
    ordered.sort((a, b) -> Integer.compare(order.getOrDefault(b, -1), order.getOrDefault(a, -1)));
    return List.copyOf(ordered);
  }

  private Optional<Manifest> storedManifest(Path bundleDir) {
    Path file = bundleDir.resolve(HotfixBundle.MANIFEST);
    if (!Files.isRegularFile(file)) {
      return Optional.empty();
    }
    try {
      return switch (validator.validate(Files.readString(file, StandardCharsets.UTF_8))) {
        case ManifestValidator.Result.Valid v -> Optional.of(v.manifest());
        case ManifestValidator.Result.Invalid i -> Optional.empty();
      };
    } catch (IOException e) {
      return Optional.empty();
    }
  }

  private List<String> applicability(Manifest manifest) {
    try {
      return Applicability.check(manifest, rt.identity());
    } catch (JrsUnreachableException | RestException | ConfigException e) {
      return List.of("server unreachable: " + e.getMessage());
    }
  }

  private Optional<ServerIdentity> identity() {
    try {
      return Optional.of(rt.identity());
    } catch (JrsUnreachableException | RestException | ConfigException e) {
      return Optional.empty();
    }
  }

  private <T> T withBundle(Path bundle, Function<HotfixBundle, T> body) {
    if (!Files.isRegularFile(bundle)) {
      throw new HotfixException(
          HotfixException.PRECHECK, "bundle not found: " + bundle, "check the path");
    }
    Path dir;
    try {
      Files.createDirectories(rt.home().runs());
      dir = Files.createTempDirectory(rt.home().runs(), "hotfix-verify-");
    } catch (IOException e) {
      throw new HotfixException(
          HotfixException.PRECHECK,
          "cannot create a working directory under " + rt.home().runs() + ": " + e.getMessage(),
          "check permissions on " + rt.home().runs());
    }
    try {
      HotfixBundle b;
      try {
        b = HotfixBundle.extract(bundle, dir);
      } catch (IOException e) {
        throw new HotfixException(
            HotfixException.SIGNATURE,
            "cannot read bundle " + bundle + ": " + e.getMessage(),
            "check that the file is a jrsctl hotfix ZIP",
            e);
      }
      return body.apply(b);
    } finally {
      try {
        Trees.deleteRecursively(dir);
      } catch (IOException e) {
        // a leftover verify directory is harmless; the next run cleans up
      }
    }
  }

  private String hash(Path file) {
    try {
      return rt.files().sha256(file);
    } catch (IOException e) {
      throw new HotfixException(
          HotfixException.PRECHECK,
          "cannot hash " + file + ": " + e.getMessage(),
          "check the file is readable",
          e);
    }
  }

  static String configHash(Config config) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      return HexFormat.of()
          .formatHex(md.digest(ConfigWriter.render(config).getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }

  private static JsonNode lenientTree(String json) {
    try {
      return Json.mapper().readTree(json);
    } catch (IOException e) {
      return Json.mapper().createObjectNode();
    }
  }
}
