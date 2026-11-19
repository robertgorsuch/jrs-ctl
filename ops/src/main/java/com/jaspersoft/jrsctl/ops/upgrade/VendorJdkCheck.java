package com.jaspersoft.jrsctl.ops.upgrade;

import com.jaspersoft.jrsctl.core.compat.CompatMatrix;
import com.jaspersoft.jrsctl.core.engine.CheckResult;
import com.jaspersoft.jrsctl.jrs.vendor.VendorJava;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * One configured vendor JDK judged against the Java versions a JasperReports Server release
 * supports (ADR-0065, issues #338 and #348). Preflight's target and source checks and the database
 * rollback's precheck share it, so the three refuse with the same wording and a JDK both checks
 * name is started once. A {@link Probe} is a fact about a directory; {@link #judge} turns it into a
 * refusal, or empty when the JDK fits.
 */
final class VendorJdkCheck {

  private VendorJdkCheck() {}

  /** What probing a configured JDK found. */
  sealed interface Probe permits NotSet, NotDirectory, Unknown, Found {}

  /** No JDK is configured under the key. */
  record NotSet() implements Probe {}

  /** The configured path is not a directory. */
  record NotDirectory(Path jdk) implements Probe {}

  /** {@code java -version} gave nothing this tool can read. */
  record Unknown(Path jdk) implements Probe {}

  /** The JDK reports this feature release. */
  record Found(Path jdk, int major) implements Probe {}

  /** Probes {@code jdk}; never throws, a failed {@code java -version} is {@link Unknown}. */
  static Probe probe(UpgradeRuntime rt, Optional<Path> jdk) {
    if (jdk.isEmpty()) {
      return new NotSet();
    }
    Path dir = jdk.get();
    if (!Files.isDirectory(dir)) {
      return new NotDirectory(dir);
    }
    Optional<Integer> found;
    try {
      found = VendorJava.detect(dir, rt.services().platform().processes());
    } catch (RuntimeException e) {
      found = Optional.empty();
    }
    return found.<Probe>map(n -> new Found(dir, n)).orElseGet(() -> new Unknown(dir));
  }

  /**
   * The refusal for {@code probe}, or empty when it found a JDK in {@code allowed}.
   *
   * @param key the configuration key the JDK came from, named in the message
   * @param user who runs on the JDK, the subject of "needs", e.g. "JasperReports Server 10.0.0"
   * @param remedy the remediation for every refusal
   */
  static Optional<CheckResult> judge(
      Probe probe, String key, Set<Integer> allowed, String user, String remedy) {
    Objects.requireNonNull(remedy, "remedy");
    String required = CompatMatrix.describeJava(allowed);
    return switch (probe) {
      case NotSet n ->
          Optional.of(
              CheckResult.fail(
                  key + " is not set; " + user + " needs a " + required + " JDK", remedy));
      case NotDirectory n ->
          Optional.of(CheckResult.fail(key + " " + n.jdk() + " is not a directory", remedy));
      case Unknown u ->
          Optional.of(
              CheckResult.fail(
                  "cannot determine the Java version of " + key + " " + u.jdk(), remedy));
      case Found f ->
          allowed.contains(f.major())
              ? Optional.empty()
              : Optional.of(
                  CheckResult.fail(
                      key
                          + " "
                          + f.jdk()
                          + " is Java "
                          + f.major()
                          + "; "
                          + user
                          + " needs "
                          + required,
                      remedy));
    };
  }
}
