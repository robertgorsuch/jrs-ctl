package com.jaspersoft.jrsctl.ops.hotfix;

import com.jaspersoft.jrsctl.jrs.api.ServerIdentity;
import java.util.ArrayList;
import java.util.List;
import org.semver4j.Semver;

/**
 * Decides whether a manifest's {@code applies} block matches a detected server (spec §8.1).
 * Invariants: version ranges use npm-style semver expressions such as {@code >=10.0.0 <10.1.0} and
 * the server version is coerced (so {@code 8.2} reads as {@code 8.2.0}); an empty editions or
 * tenancy list means "any"; every mismatch is reported.
 */
public final class Applicability {

  private Applicability() {}

  /** Why {@code manifest} does not apply to {@code server}; empty when it does. */
  public static List<String> check(Manifest manifest, ServerIdentity server) {
    List<String> problems = new ArrayList<>();
    Manifest.Applies applies = manifest.applies();
    Semver version = Semver.coerce(server.version());
    if (version == null) {
      problems.add("server version '" + server.version() + "' is not a semantic version");
    } else {
      boolean any = false;
      for (String range : applies.versions()) {
        try {
          if (version.satisfies(range)) {
            any = true;
            break;
          }
        } catch (RuntimeException e) {
          problems.add("applies.versions entry '" + range + "' is not a valid range");
        }
      }
      if (!any) {
        problems.add(
            "server version "
                + server.version()
                + " is outside applies.versions "
                + applies.versions());
      }
    }
    if (!applies.editions().isEmpty() && !applies.editions().contains(server.edition().name())) {
      problems.add(
          "server edition "
              + server.edition()
              + " is not in applies.editions "
              + applies.editions());
    }
    if (!applies.tenancy().isEmpty() && !applies.tenancy().contains(server.tenancy().name())) {
      problems.add(
          "server tenancy " + server.tenancy() + " is not in applies.tenancy " + applies.tenancy());
    }
    return List.copyOf(problems);
  }
}
