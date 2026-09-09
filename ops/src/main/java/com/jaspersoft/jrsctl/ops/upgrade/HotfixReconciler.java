package com.jaspersoft.jrsctl.ops.upgrade;

import com.jaspersoft.jrsctl.core.json.Json;
import com.jaspersoft.jrsctl.core.state.HotfixInstalled;
import com.jaspersoft.jrsctl.jrs.api.ServerIdentity;
import com.jaspersoft.jrsctl.ops.hotfix.Applicability;
import com.jaspersoft.jrsctl.ops.hotfix.HotfixBundle;
import com.jaspersoft.jrsctl.ops.hotfix.HotfixPaths;
import com.jaspersoft.jrsctl.ops.hotfix.Manifest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Classifies installed hotfixes against the upgraded server (spec §10.2 step 12): a hotfix is
 * {@code REAPPLICABLE} when its {@code applies} section matches the new identity and every {@code
 * replaces} target exists in the new webapp, otherwise {@code SUPERSEDED}. Invariants: pure with
 * respect to the state store (reads only); a hotfix whose bundle copy under {@code
 * runs/<installedRunId>/bundle} is gone is still classified but reported as not re-applicable
 * automatically; the same function serves planning (against the target version) and execution
 * (against the identity the server reports after the vendor upgrade).
 */
final class HotfixReconciler {

  static final String BUNDLE_DIR = "bundle";

  enum Status {
    REAPPLICABLE,
    SUPERSEDED
  }

  record Classification(
      HotfixInstalled hotfix,
      Status status,
      List<String> reasons,
      Optional<Path> bundleDir,
      Optional<Manifest> manifest) {
    Classification {
      Objects.requireNonNull(hotfix, "hotfix");
      Objects.requireNonNull(status, "status");
      reasons = List.copyOf(reasons);
      Objects.requireNonNull(bundleDir, "bundleDir");
      Objects.requireNonNull(manifest, "manifest");
    }

    boolean bundleAvailable() {
      return bundleDir.isPresent() && manifest.isPresent();
    }

    String describe() {
      return hotfix.id()
          + " -> "
          + status
          + (reasons.isEmpty() ? "" : " (" + String.join("; ", reasons) + ")")
          + (bundleAvailable() ? "" : "; bundle no longer available, re-apply manually");
    }
  }

  private HotfixReconciler() {}

  static List<Classification> classify(UpgradeRuntime rt, UpgradeInput in, ServerIdentity target) {
    List<Classification> out = new ArrayList<>();
    for (HotfixInstalled h : rt.store().installedHotfixes()) {
      out.add(classify(rt, in.paths(), h, target));
    }
    return List.copyOf(out);
  }

  static Classification classify(
      UpgradeRuntime rt, HotfixPaths paths, HotfixInstalled hotfix, ServerIdentity target) {
    Path bundleDir = rt.home().runDir(hotfix.installedRunId()).resolve(BUNDLE_DIR);
    Optional<Manifest> manifest = readManifest(bundleDir);
    List<String> reasons = new ArrayList<>();
    if (manifest.isEmpty()) {
      reasons.add("no bundle copy under " + bundleDir);
      return new Classification(
          hotfix, Status.SUPERSEDED, reasons, Optional.empty(), Optional.empty());
    }
    Manifest m = manifest.get();
    reasons.addAll(Applicability.check(m, target));
    for (Manifest.FileEntry entry : m.files()) {
      Path targetFile;
      try {
        targetFile = paths.resolve(entry.path());
      } catch (IllegalArgumentException e) {
        reasons.add(entry.path() + ": " + e.getMessage());
        continue;
      }
      for (String name : entry.replaces()) {
        Path sibling = targetFile.resolveSibling(name);
        if (!Files.isRegularFile(sibling)) {
          reasons.add("replaces target " + sibling + " is absent from the new webapp");
        }
      }
    }
    Status status = reasons.isEmpty() ? Status.REAPPLICABLE : Status.SUPERSEDED;
    return new Classification(hotfix, status, reasons, Optional.of(bundleDir), manifest);
  }

  static Optional<Manifest> readManifest(Path bundleDir) {
    Path file = bundleDir.resolve(HotfixBundle.MANIFEST);
    if (!Files.isRegularFile(file)) {
      return Optional.empty();
    }
    try {
      return Optional.of(
          Json.mapper().readValue(Files.readString(file, StandardCharsets.UTF_8), Manifest.class));
    } catch (IOException | RuntimeException e) {
      return Optional.empty();
    }
  }

  /** File-system and step-id safe form of a hotfix id. */
  static String slug(String hotfixId) {
    StringBuilder sb = new StringBuilder();
    for (char c : hotfixId.toLowerCase(Locale.ROOT).toCharArray()) {
      sb.append(Character.isLetterOrDigit(c) || c == '.' ? c : '-');
    }
    return sb.toString();
  }
}
