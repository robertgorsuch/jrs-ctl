package com.jaspersoft.jrsctl.ops.hotfix;

import com.jaspersoft.jrsctl.core.crypto.Ed25519;
import com.jaspersoft.jrsctl.core.keys.KeyRing;
import com.jaspersoft.jrsctl.core.platform.FileOps;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Checks a bundle the way spec §8.1 and §11.1 prescribe: the detached Ed25519 signature over the
 * exact manifest bytes against the trusted key ring, then the SHA-256 of every file the manifest
 * lists, then that the ZIP carries nothing else. Invariants: hashing streams through {@link
 * FileOps#sha256}; a missing or malformed signature is "unsigned", never an exception; the problems
 * list is complete, so the operator sees every bad file at once.
 */
public final class BundleVerifier {

  /** Outcome of the signature check. */
  public record Signature(boolean present, Optional<KeyRing.TrustedKey> signedBy) {
    public boolean valid() {
      return signedBy.isPresent();
    }
  }

  private final KeyRing keys;
  private final FileOps files;

  public BundleVerifier(KeyRing keys, FileOps files) {
    this.keys = Objects.requireNonNull(keys, "keys");
    this.files = Objects.requireNonNull(files, "files");
  }

  /** Verifies the signature over the manifest bytes. */
  public Signature signature(HotfixBundle bundle) {
    Optional<String> text = bundle.signature();
    if (text.isEmpty() || text.get().isBlank()) {
      return new Signature(false, Optional.empty());
    }
    byte[] sig;
    try {
      sig = Ed25519.decodeSignature(text.get());
    } catch (IllegalArgumentException e) {
      return new Signature(true, Optional.empty());
    }
    return new Signature(true, keys.verify(bundle.manifestBytes(), sig));
  }

  /**
   * Hash mismatches, missing listed files and unlisted files. Uses the manifest's listed files
   * only; the manifest itself has already passed {@link ManifestValidator}.
   */
  public List<String> hashProblems(HotfixBundle bundle, Manifest manifest) {
    List<String> problems = new ArrayList<>();
    Set<String> listed = new LinkedHashSet<>();
    for (Manifest.FileEntry f : manifest.files()) {
      if (f.action() == Manifest.Action.DELETE) {
        continue;
      }
      listed.add(f.bundlePath());
      check(bundle, f.bundlePath(), f.sha256(), problems);
    }
    for (Manifest.SqlEntry s : manifest.sql()) {
      listed.add(s.file());
      check(bundle, s.file(), s.sha256(), problems);
      if (s.rollbackFile().isPresent()) {
        listed.add(s.rollbackFile().get());
        check(bundle, s.rollbackFile().get(), s.rollbackSha256(), problems);
      }
    }
    for (Manifest.CheckFile c : manifest.checks()) {
      listed.add(c.file());
      check(bundle, c.file(), c.sha256(), problems);
    }
    for (String entry : bundle.files()) {
      if (!listed.contains(entry)) {
        problems.add("unlisted file " + entry);
      }
    }
    return List.copyOf(problems);
  }

  private void check(
      HotfixBundle bundle, String entry, Optional<String> expected, List<String> problems) {
    Path file = bundle.file(entry);
    if (!Files.isRegularFile(file)) {
      problems.add("missing file " + entry);
      return;
    }
    if (expected.isEmpty()) {
      problems.add("no sha256 for " + entry);
      return;
    }
    try {
      String actual = files.sha256(file);
      if (!actual.equals(expected.get())) {
        problems.add("sha256 mismatch for " + entry + ": " + actual + " != " + expected.get());
      }
    } catch (IOException e) {
      problems.add("cannot hash " + entry + ": " + e.getMessage());
    }
  }
}
