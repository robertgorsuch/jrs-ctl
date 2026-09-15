package com.jaspersoft.jrsctl.ops.upgrade;

import com.jaspersoft.jrsctl.core.engine.CheckResult;
import com.jaspersoft.jrsctl.core.engine.Context;
import com.jaspersoft.jrsctl.core.engine.Step;
import com.jaspersoft.jrsctl.core.engine.StepFailure;
import com.jaspersoft.jrsctl.core.engine.StepResult;
import com.jaspersoft.jrsctl.core.event.EventSink;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Writes the ZIP a re-applied hotfix's embedded apply steps read (issue #49). Planning builds it
 * only long enough to plan the apply and deletes it again, so the file exists once the run reaches
 * the reconcile phase and never for a plan that is shown and not run. Invariants: every execute
 * rebuilds the archive from the installing run's bundle copy, so a second execute converges on the
 * same entries; the embedded {@code VerifySignature} precheck that runs next re-verifies the
 * signature and every hash before anything is applied; compensation deletes the ZIP.
 */
final class RepackHotfixBundle implements Step {

  static final String ID = "repack-bundle";

  private final Path bundleDir;
  private final Path zip;

  RepackHotfixBundle(Path bundleDir, Path zip) {
    this.bundleDir = Objects.requireNonNull(bundleDir, "bundleDir");
    this.zip = Objects.requireNonNull(zip, "zip");
  }

  @Override
  public String id() {
    return ID;
  }

  @Override
  public String title() {
    return "Re-pack the installed hotfix bundle";
  }

  @Override
  public String phase() {
    return Phases.RECONCILE;
  }

  @Override
  public String detail() {
    return bundleDir + " -> " + zip;
  }

  @Override
  public CheckResult precheck(Context ctx) {
    if (!Files.isDirectory(bundleDir)) {
      return CheckResult.fail(
          "the installed bundle copy " + bundleDir + " is gone",
          "re-apply the hotfix by hand with `jrsctl hotfix apply <bundle>` after the upgrade");
    }
    return CheckResult.pass();
  }

  @Override
  public StepResult execute(Context ctx, EventSink out) {
    try {
      BundleZips.zip(bundleDir, zip);
      return StepResult.ok();
    } catch (IOException e) {
      return StepResult.failed(
          StepFailure.fatal(
              "cannot re-pack " + bundleDir + ": " + e.getMessage(),
              "check that " + zip.getParent() + " is writable"));
    }
  }

  @Override
  public StepResult compensate(Context ctx, EventSink out) {
    try {
      Files.deleteIfExists(zip);
    } catch (IOException e) {
      // a leftover re-pack ZIP is harmless: the next execute replaces it
    }
    return StepResult.ok();
  }
}
