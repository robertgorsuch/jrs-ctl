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
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
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

  /**
   * Where the {@code replaces} targets of a manifest entry are looked for: the webapp the hotfix
   * would be re-applied to. At plan time that is the target package's webapp, because an installed
   * hotfix has already deleted the files it replaced from the running one (assessment item U4); at
   * execution time, after the vendor upgrade, it is the installation itself.
   */
  @FunctionalInterface
  interface NewWebapp {
    /** Whether the file at this manifest-relative path exists in the new webapp. */
    boolean has(String manifestPath);
  }

  /** The installation as it is now: for classification after the vendor upgrade has run. */
  static NewWebapp installed(HotfixPaths paths) {
    return manifestPath -> {
      try {
        return Files.isRegularFile(paths.resolve(manifestPath));
      } catch (IllegalArgumentException e) {
        return false;
      }
    };
  }

  /**
   * The target package's webapp, unpacked or as the war the distribution ships, for classification
   * at plan time. A path outside {@code webapps/<webappName>/} is looked up in the installation,
   * which the vendor upgrade does not replace.
   */
  static NewWebapp inPackage(TargetPackage target, String webappName, HotfixPaths paths) {
    NewWebapp fallback = installed(paths);
    String prefix = "webapps/" + webappName + "/";
    return manifestPath -> {
      if (!manifestPath.startsWith(prefix)) {
        return fallback.has(manifestPath);
      }
      String relative = manifestPath.substring(prefix.length());
      if (target.webappDir().isPresent()) {
        Path root = target.webappDir().get().toAbsolutePath().normalize();
        Path candidate = root.resolve(relative).normalize();
        return candidate.startsWith(root) && Files.isRegularFile(candidate);
      }
      if (target.warFile().isPresent()) {
        try (FileSystem war = FileSystems.newFileSystem(target.warFile().get())) {
          return Files.isRegularFile(war.getPath(relative));
        } catch (IOException | RuntimeException e) {
          return false;
        }
      }
      return fallback.has(manifestPath);
    };
  }

  static List<Classification> classify(
      UpgradeRuntime rt, UpgradeInput in, ServerIdentity target, NewWebapp webapp) {
    List<Classification> out = new ArrayList<>();
    for (HotfixInstalled h : rt.store().installedHotfixes()) {
      out.add(classify(rt, in.paths(), h, target, webapp));
    }
    return List.copyOf(out);
  }

  static Classification classify(
      UpgradeRuntime rt,
      HotfixPaths paths,
      HotfixInstalled hotfix,
      ServerIdentity target,
      NewWebapp webapp) {
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
      try {
        paths.resolve(entry.path());
      } catch (IllegalArgumentException e) {
        reasons.add(entry.path() + ": " + e.getMessage());
        continue;
      }
      for (String name : entry.replaces()) {
        String sibling = siblingOf(entry.path(), name);
        if (!webapp.has(sibling)) {
          reasons.add("replaces target " + sibling + " is absent from the new webapp");
        }
      }
    }
    Status status = reasons.isEmpty() ? Status.REAPPLICABLE : Status.SUPERSEDED;
    return new Classification(hotfix, status, reasons, Optional.of(bundleDir), manifest);
  }

  /** The manifest-relative path of {@code name} next to {@code manifestPath}. */
  static String siblingOf(String manifestPath, String name) {
    int slash = manifestPath.lastIndexOf('/');
    return slash < 0 ? name : manifestPath.substring(0, slash + 1) + name;
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
