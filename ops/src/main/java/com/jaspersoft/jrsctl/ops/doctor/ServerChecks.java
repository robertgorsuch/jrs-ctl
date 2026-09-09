package com.jaspersoft.jrsctl.ops.doctor;

import com.jaspersoft.jrsctl.core.compat.CompatMatrix;
import com.jaspersoft.jrsctl.core.config.Config;
import com.jaspersoft.jrsctl.core.secrets.Secret;
import com.jaspersoft.jrsctl.core.secrets.SecretException;
import com.jaspersoft.jrsctl.core.secrets.SecretRef;
import com.jaspersoft.jrsctl.jrs.api.Capability;
import com.jaspersoft.jrsctl.jrs.api.Credentials;
import com.jaspersoft.jrsctl.jrs.api.KeystoreInfo;
import com.jaspersoft.jrsctl.jrs.api.ServerIdentity;
import com.jaspersoft.jrsctl.jrs.api.Session;
import com.jaspersoft.jrsctl.ops.ReportItem;
import com.jaspersoft.jrsctl.ops.Services;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * The {@code doctor} checks that need a reachable server (spec §12.1, §5.7, §9.3). Invariants:
 * every method is read-only against the server; the login secret is resolved, handed to the adapter
 * and closed without appearing in any item; compat is judged only from the matrix, never from
 * version-string branching in this class.
 */
final class ServerChecks {

  static final String APP_SERVER = "tomcat";

  private ServerChecks() {}

  static ReportItem auth(Services s, ServerProbe.Connected c) {
    Config.Auth auth = s.config().server().auth();
    Optional<String> username = auth.username();
    Optional<SecretRef> ref = auth.passwordRef();
    if (username.isEmpty() || ref.isEmpty()) {
      return ReportItem.fail(
          "auth",
          "server.auth.username or server.auth.passwordRef is not configured",
          "set both in config.yaml (run jrsctl init)");
    }
    try (Secret password = s.secrets().resolve(ref.get())) {
      Session session =
          c.adapter().login(new Credentials(username.get(), password, Optional.empty()));
      return ReportItem.pass(
          "auth", "logged in as " + username.get() + " (" + session.mode() + ")");
    } catch (SecretException e) {
      return ReportItem.fail("auth", e.getMessage(), "fix " + ref.get().render());
    } catch (RuntimeException e) {
      return ReportItem.fail(
          "auth",
          "login as " + username.get() + " failed: " + e.getMessage(),
          "check the credentials behind " + ref.get().render() + " and server.auth.mode");
    }
  }

  static ReportItem identity(ServerProbe.Connected c) {
    ServerIdentity id = c.identity();
    return ReportItem.pass(
        "identity",
        id.version()
            + " "
            + id.edition()
            + " "
            + id.tenancy()
            + " build "
            + id.build()
            + " features "
            + new TreeSet<>(id.features()));
  }

  /** FAIL on an unsupported combination, or WARN when {@code allowUnsupported}. */
  static ReportItem compat(Services s, ServerProbe.Connected c, boolean allowUnsupported) {
    CompatMatrix matrix = s.matrix();
    ServerIdentity id = c.identity();
    String edition = id.edition().name();
    Optional<String> database = s.config().database().type().map(Config.DatabaseType::yamlValue);
    Optional<CompatMatrix.Entry> entry = matrix.find(id.version());
    boolean supported =
        entry
            .filter(e -> e.editions().contains(edition))
            .filter(e -> e.appServers().contains(APP_SERVER))
            .filter(e -> database.map(d -> e.databases().contains(d)).orElse(true))
            .isPresent();
    String combo =
        id.version()
            + " "
            + edition
            + " on "
            + APP_SERVER
            + database.map(d -> " with " + d).orElse("");
    if (supported) {
      return ReportItem.pass(DoctorReport.COMPAT, combo + " (" + entry.get().label() + ")");
    }
    String detail =
        entry.isEmpty()
            ? combo + ": version not in the compatibility matrix"
            : combo + ": combination not listed under " + entry.get().label();
    if (allowUnsupported) {
      return ReportItem.warn(
          DoctorReport.COMPAT,
          detail + "; continuing because --allow-unsupported was given",
          "expect no support for this combination");
    }
    return ReportItem.fail(
        DoctorReport.COMPAT,
        detail,
        "use a supported JasperReports Server version or pass --allow-unsupported (audited)");
  }

  static ReportItem capabilities(Services s, ServerProbe.Connected c) {
    ServerIdentity id = c.identity();
    Set<String> probed =
        c.adapter().capabilities().stream()
            .map(Enum::name)
            .collect(Collectors.toCollection(TreeSet::new));
    if (s.matrix().find(id.version()).isEmpty()) {
      return ReportItem.skip(
          "capabilities",
          "probed " + probed + "; no matrix entry for " + id.version() + " to compare with",
          "fix the compat check first");
    }
    Set<String> expected =
        new TreeSet<>(s.matrix().expectedCapabilities(id.version(), id.edition().name()));
    Set<String> missing = new TreeSet<>(expected);
    missing.removeAll(probed);
    Set<String> extra = new TreeSet<>(probed);
    extra.removeAll(expected);
    if (missing.isEmpty() && extra.isEmpty()) {
      return ReportItem.pass("capabilities", "probed " + probed + " as expected");
    }
    return ReportItem.warn(
        "capabilities",
        "probed " + probed + "; missing " + missing + "; unexpected " + extra,
        "the server may be partially configured (REST login, keystore, organizations); check its"
            + " configuration or the matrix entry");
  }

  static ReportItem keystore(Services s, ServerProbe.Connected c) {
    ServerIdentity id = c.identity();
    boolean expected =
        c.adapter().capabilities().contains(Capability.KEYSTORE_ENCRYPTION)
            || s.matrix()
                .expectedCapabilities(id.version(), id.edition().name())
                .contains(Capability.KEYSTORE_ENCRYPTION.name());
    KeystoreInfo info = c.adapter().keystore();
    if (info.present()) {
      return ReportItem.pass(
          "keystore",
          info.keystoreFile().map(Path::toString).orElse("keystore")
              + info.fingerprint().map(f -> " sha256 " + shortHash(f)).orElse(""));
    }
    String reason = info.reason().orElse("keystore not found");
    if (expected) {
      return ReportItem.fail(
          "keystore",
          reason,
          "set server.runAsUser to the account that runs Tomcat and make its ~/.jrsks and"
              + " ~/.jrsksp readable to jrsctl");
    }
    return ReportItem.pass("keystore", "not applicable: " + reason);
  }

  static ReportItem vendorJava(Services s, ServerProbe.Connected c) {
    Optional<Path> javaHome = s.config().vendor().javaHome();
    Optional<CompatMatrix.Entry> entry = s.matrix().find(c.identity().version());
    if (entry.isEmpty()) {
      return ReportItem.skip(
          "vendor-java",
          "no matrix entry for " + c.identity().version() + "; required Java unknown",
          "fix the compat check first");
    }
    int required = entry.get().javaForBuildomatic();
    if (javaHome.isEmpty()) {
      return ReportItem.skip(
          "vendor-java",
          "vendor.javaHome is not configured",
          "set vendor.javaHome to a Java "
              + required
              + " JDK for buildomatic (needed by upgrade"
              + " and vendor export/import)");
    }
    JavaVersion.Probe probe = JavaVersion.probe(s.platform().processes(), javaHome.get());
    if (probe.feature().isEmpty()) {
      return ReportItem.fail(
          "vendor-java",
          probe.detail(),
          "point vendor.javaHome at a working Java " + required + " JDK");
    }
    int found = probe.feature().get();
    if (found != required) {
      return ReportItem.fail(
          "vendor-java",
          javaHome.get()
              + " is Java "
              + found
              + "; JRS "
              + c.identity().version()
              + " needs Java "
              + required,
          "set vendor.javaHome to a Java " + required + " JDK");
    }
    return ReportItem.pass("vendor-java", javaHome.get() + " is Java " + found + " as required");
  }

  private static String shortHash(String hash) {
    String h = hash.toLowerCase(Locale.ROOT);
    return h.length() > 12 ? h.substring(0, 12) : h;
  }
}
