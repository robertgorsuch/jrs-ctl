package com.jaspersoft.jrsctl.ops.hotfix;

import com.jaspersoft.jrsctl.core.engine.Plan;
import com.jaspersoft.jrsctl.core.secrets.SecretRef;
import com.jaspersoft.jrsctl.core.state.HotfixInstalled;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Entry points of the hotfix subsystem (spec §8). Planning methods never mutate anything; they
 * verify, validate and return a {@link Plan} whose execution is the {@code Runner}'s job.
 * Invariant: a plan returned here has already passed signature verification (unless {@code
 * allowUnsigned}) and manifest validation, so {@code --plan} output is trustworthy.
 */
public interface HotfixOperations {

  /** Options for {@code hotfix apply}. */
  record ApplyOptions(boolean allowUnsigned) {}

  /** Options for {@code hotfix rollback}. */
  record RollbackOptions(boolean cascade) {}

  /** Result of {@code hotfix verify}: signature, hashes and applicability only. */
  record VerifyReport(
      boolean signatureValid,
      Optional<String> signedBy,
      boolean hashesValid,
      List<String> hashProblems,
      boolean applicable,
      List<String> applicabilityProblems,
      String manifestId,
      String title) {
    public boolean ok() {
      return signatureValid && hashesValid && applicable;
    }
  }

  /** Builds a signed bundle from a directory holding manifest.json, payload/, sql/, checks/. */
  Path build(Path bundleDir, SecretRef privateKeyRef, Path out);

  VerifyReport verify(Path bundle);

  Plan planApply(Path bundle, ApplyOptions options);

  Plan planRollback(String hotfixId, RollbackOptions options);

  List<HotfixInstalled> list();
}
