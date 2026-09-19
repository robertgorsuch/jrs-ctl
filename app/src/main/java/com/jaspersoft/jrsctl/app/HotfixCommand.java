package com.jaspersoft.jrsctl.app;

import com.jaspersoft.jrsctl.core.engine.Plan;
import com.jaspersoft.jrsctl.core.redact.Redactor;
import com.jaspersoft.jrsctl.core.secrets.SecretRef;
import com.jaspersoft.jrsctl.core.state.HotfixInstalled;
import com.jaspersoft.jrsctl.core.state.StateStore;
import com.jaspersoft.jrsctl.ops.PlanRegistry;
import com.jaspersoft.jrsctl.ops.Report;
import com.jaspersoft.jrsctl.ops.ReportItem;
import com.jaspersoft.jrsctl.ops.Services;
import com.jaspersoft.jrsctl.ops.hotfix.HotfixOperations;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/**
 * {@code jrsctl hotfix build|verify|apply|rollback|list} (spec §8.4). Invariants: {@code build},
 * {@code verify} and {@code list} never mutate the server; {@code apply} and {@code rollback} only
 * ever run through {@link PlanExecutor}, so they are shown, confirmed, journaled and locked like
 * every other mutation; a bundle whose signature is not trusted is refused with exit 7 unless
 * {@code --allow-unsigned} is given, in which case the override is audited before planning; a
 * planning failure exits 2 because nothing has been touched yet.
 */
@Command(
    name = "hotfix",
    mixinStandardHelpOptions = true,
    exitCodeOnInvalidInput = ExitCodes.USAGE,
    description = "Verify, apply, roll back and list hotfixes.",
    footerHeading = "%n",
    footer =
        "Hotfixes from Jaspersoft support apply as downloaded: jrsctl hotfix apply <package.zip>"
            + " (it asks you to confirm the checksum shown on the support portal). Bundles you"
            + " author yourself: jrsctl docs hotfix-authoring (for bundle authors only).",
    subcommands = {
      HotfixCommand.Build.class,
      HotfixCommand.Verify.class,
      HotfixCommand.Apply.class,
      HotfixCommand.Rollback.class,
      HotfixCommand.RecordPackage.class,
      HotfixCommand.ListInstalled.class
    })
final class HotfixCommand implements Runnable {

  @Spec CommandSpec spec;

  @Override
  public void run() {
    spec.commandLine().usage(spec.commandLine().getOut());
  }

  /**
   * {@code jrsctl hotfix build <dir> --key <secretRef> --out <bundle>}. Hidden from the group's
   * help: it is for bundle authors, not operators (#62).
   */
  @Command(
      name = "build",
      hidden = true,
      mixinStandardHelpOptions = true,
      exitCodeOnInvalidInput = ExitCodes.USAGE,
      description =
          "Validate a bundle directory, hash its files, sign the manifest and write the ZIP.")
  static final class Build implements Callable<Integer> {

    @Spec CommandSpec spec;
    @Mixin GlobalOptions global;

    @Parameters(index = "0", paramLabel = "<dir>", description = "Directory with manifest.json.")
    Path dir;

    @Option(
        names = "--key",
        required = true,
        paramLabel = "<secretRef>",
        description = "Private key reference: file:/path, env:NAME or enc:NAME (base64 PKCS#8).")
    String key;

    @Option(
        names = "--out",
        required = true,
        paramLabel = "<bundle>",
        description = "Path of the ZIP to write.")
    Path outFile;

    @Override
    public Integer call() {
      PrintWriter out = spec.commandLine().getOut();
      PrintWriter err = spec.commandLine().getErr();
      SecretRef ref;
      try {
        ref = SecretRef.parse(key);
      } catch (IllegalArgumentException e) {
        return ExitCodes.fail(out, err, global.json(), ExitCodes.USAGE, "--key " + e.getMessage());
      }
      try (Bootstrap boot = Bootstrap.open(global, Env.vars(), Clock.systemUTC())) {
        Path written;
        try {
          written = HotfixOps.open(boot.services()).build(dir, ref, outFile);
        } catch (RuntimeException e) {
          return ExitCodes.reportPlanningFailure(out, err, global.json(), e);
        }
        if (global.json()) {
          JsonOut.print(out, Map.of("bundle", written.toString()));
        } else {
          out.println(Redactor.global().redact("wrote " + written));
          out.flush();
        }
        return ExitCodes.SUCCESS;
      }
    }
  }

  /** {@code jrsctl hotfix verify <bundle>}: signature, hashes and applicability only. */
  @Command(
      name = "verify",
      mixinStandardHelpOptions = true,
      exitCodeOnInvalidInput = ExitCodes.USAGE,
      description = "Check a bundle's signature, file hashes and applicability to this server.")
  static final class Verify implements Callable<Integer> {

    @Spec CommandSpec spec;
    @Mixin GlobalOptions global;

    @Parameters(index = "0", paramLabel = "<bundle>", description = "Bundle ZIP to verify.")
    Path bundle;

    @Override
    public Integer call() {
      PrintWriter out = spec.commandLine().getOut();
      PrintWriter err = spec.commandLine().getErr();
      Redactor redactor = Redactor.global();
      try (Bootstrap boot = Bootstrap.open(global, Env.vars(), Clock.systemUTC())) {
        HotfixOperations.VerifyReport report;
        try {
          report = HotfixOps.open(boot.services()).verify(bundle);
        } catch (RuntimeException e) {
          return ExitCodes.reportPlanningFailure(out, err, global.json(), e);
        }
        if (global.json()) {
          out.println(redactor.redact(JsonOut.write(report)));
          out.flush();
        } else {
          Report r = Report.of(items(report));
          ReportPrinter.print(
              out, r.items(), r.counts(), Ansi.forStdout(global, Env.vars()), redactor);
          String noun = report.official() ? "official Jaspersoft package " : "bundle ";
          out.println(noun + report.manifestId() + (report.ok() ? " ok" : " rejected"));
          out.flush();
        }
        return report.ok() ? ExitCodes.SUCCESS : ExitCodes.SIGNATURE_FAILED;
      }
    }

    static List<ReportItem> items(HotfixOperations.VerifyReport report) {
      List<ReportItem> items = new ArrayList<>();
      if (report.signatureValid()) {
        items.add(
            ReportItem.pass("signature", "signed by " + report.signedBy().orElse("a trusted key")));
      } else if (report.official()) {
        items.add(
            ReportItem.pass(
                "signature",
                "official Jaspersoft package, no jrsctl signature; SHA-256 "
                    + report.sha256()
                    + " (apply asks you to confirm it against the support portal)"));
      } else {
        items.add(
            ReportItem.fail(
                "signature",
                "missing, or not made by a trusted key",
                "add the signer's public key with `jrsctl keys add`, or use --allow-unsigned"
                    + " on apply (audited)"));
      }
      items.add(
          report.hashesValid()
              ? ReportItem.pass("hashes", "every listed file matches its manifest hash")
              : ReportItem.fail(
                  "hashes",
                  String.join("; ", report.hashProblems()),
                  "obtain the bundle again from its publisher"));
      items.add(
          report.applicable()
              ? ReportItem.pass("applicability", "applies to the configured server")
              : ReportItem.fail(
                  "applicability",
                  String.join("; ", report.applicabilityProblems()),
                  "check `jrsctl doctor` and the bundle's applies section"));
      items.add(ReportItem.pass("manifest", report.manifestId() + "  " + report.title()));
      return items;
    }
  }

  /** {@code jrsctl hotfix apply <bundle> [--plan] [--yes] [--allow-unsigned] [--rollback-all]}. */
  @Command(
      name = "apply",
      mixinStandardHelpOptions = true,
      exitCodeOnInvalidInput = ExitCodes.USAGE,
      description = "Verify a bundle, show the plan and, after confirmation, apply it.")
  static final class Apply implements Callable<Integer> {

    @Spec CommandSpec spec;
    @Mixin GlobalOptions global;

    @Parameters(index = "0", paramLabel = "<bundle>", description = "Bundle ZIP to apply.")
    Path bundle;

    @Option(names = "--plan", description = "Show the plan and exit without running it.")
    boolean plan;

    @Option(
        names = "--allow-unsigned",
        description = "Apply a bundle whose signature is missing or untrusted (audited).")
    boolean allowUnsigned;

    @Option(
        names = "--rollback-all",
        description = "On failure compensate every step of the plan, not just the failing phase.")
    boolean rollbackAll;

    @Override
    public Integer call() {
      PrintWriter out = spec.commandLine().getOut();
      PrintWriter err = spec.commandLine().getErr();
      try (Bootstrap boot = Bootstrap.open(global, Env.vars(), Clock.systemUTC())) {
        Services services = boot.services();
        Plan planned;
        boolean unsignedAccepted = allowUnsigned;
        try {
          HotfixOperations ops = HotfixOps.open(services);
          HotfixOperations.VerifyReport report = ops.verify(bundle);
          if (!report.hashesValid()) {
            return ExitCodes.fail(
                out,
                err,
                global.json(),
                ExitCodes.SIGNATURE_FAILED,
                "bundle rejected, file hashes do not match the manifest: "
                    + String.join("; ", report.hashProblems()));
          }
          String from = bundle.toAbsolutePath().normalize().toString();
          if (!report.signatureValid() && report.official()) {
            // ADR-0027: an official package has no signature to check; the operator compares
            // its checksum with the support portal instead, and that answer is audited
            out.println(
                "Official Jaspersoft package "
                    + report.manifestId()
                    + ", SHA-256 "
                    + report.sha256()
                    + ".");
            out.flush();
            if (!allowUnsigned) {
              if (global.json() || global.nonInteractive()) {
                return ExitCodes.fail(
                    out,
                    err,
                    global.json(),
                    ExitCodes.SIGNATURE_FAILED,
                    "official Jaspersoft package "
                        + report.manifestId()
                        + " carries no jrsctl signature",
                    Optional.of(
                        "check its SHA-256 "
                            + report.sha256()
                            + " against the checksum on the support portal, then re-run with"
                            + " --allow-unsigned"));
              }
              if (!Confirm.ask(out, "Does this match the checksum on the support portal? [y/N] ")) {
                return ExitCodes.fail(
                    out,
                    err,
                    global.json(),
                    ExitCodes.SIGNATURE_FAILED,
                    "checksum not confirmed; nothing has changed",
                    Optional.of("download the package again from the support portal"));
              }
              services
                  .stateStore()
                  .get()
                  .audit(
                      "operator",
                      "hotfix.apply.official-confirmed",
                      report.manifestId() + " sha256 " + report.sha256() + " from " + from);
              unsignedAccepted = true;
            }
          }
          if (!report.signatureValid() && !report.official()) {
            if (!allowUnsigned) {
              return ExitCodes.fail(
                  out,
                  err,
                  global.json(),
                  ExitCodes.SIGNATURE_FAILED,
                  "bundle signature is missing or not made by a trusted key",
                  Optional.of(
                      "add the key with `jrsctl keys add` or pass --allow-unsigned (audited)"));
            }
          }
          if (!report.signatureValid() && allowUnsigned) {
            services
                .stateStore()
                .get()
                .audit(
                    "operator",
                    "hotfix.apply.allow-unsigned",
                    report.manifestId() + " from " + from);
          }
          planned = ops.planApply(bundle, new HotfixOperations.ApplyOptions(unsignedAccepted));
        } catch (RuntimeException e) {
          return ExitCodes.reportPlanningFailure(out, err, global.json(), e);
        }
        PlanExecutor executor = new PlanExecutor(services, global, out, err, Env.vars());
        return executor.execute(
            new PlanExecutor.Request(
                planned,
                PlanRegistry.HOTFIX_APPLY,
                PlanRegistry.applyArgs(bundle, unsignedAccepted),
                plan,
                rollbackAll));
      }
    }
  }

  /** {@code jrsctl hotfix rollback <id> [--cascade] [--plan] [--yes]}. */
  @Command(
      name = "rollback",
      mixinStandardHelpOptions = true,
      exitCodeOnInvalidInput = ExitCodes.USAGE,
      description = "Restore the files an installed hotfix replaced and mark it rolled back.")
  static final class Rollback implements Callable<Integer> {

    @Spec CommandSpec spec;
    @Mixin GlobalOptions global;

    @Parameters(
        index = "0",
        paramLabel = "<id>",
        description = "Hotfix id, e.g. JRS-8.2.0-HF-0001.")
    String id;

    @Option(
        names = "--cascade",
        description = "Also roll back later hotfixes that own the same files, newest first.")
    boolean cascade;

    @Option(names = "--plan", description = "Show the plan and exit without running it.")
    boolean plan;

    @Override
    public Integer call() {
      PrintWriter out = spec.commandLine().getOut();
      PrintWriter err = spec.commandLine().getErr();
      try (Bootstrap boot = Bootstrap.open(global, Env.vars(), Clock.systemUTC())) {
        Services services = boot.services();
        Plan planned;
        try {
          planned =
              HotfixOps.open(services)
                  .planRollback(id, new HotfixOperations.RollbackOptions(cascade));
        } catch (RuntimeException e) {
          return ExitCodes.reportPlanningFailure(out, err, global.json(), e);
        }
        PlanExecutor executor = new PlanExecutor(services, global, out, err, Env.vars());
        return executor.execute(
            new PlanExecutor.Request(
                planned,
                PlanRegistry.HOTFIX_ROLLBACK,
                PlanRegistry.rollbackArgs(id, cascade),
                plan,
                false));
      }
    }
  }

  /**
   * {@code jrsctl hotfix record <package.zip> [--json]}: enters an official package applied by hand
   * into the ledger (ADR-0030, issue #99). Nothing on the server is touched.
   */
  @Command(
      name = "record",
      mixinStandardHelpOptions = true,
      exitCodeOnInvalidInput = ExitCodes.USAGE,
      description =
          "Record an official Jaspersoft hotfix package that was applied by hand, so hotfix list"
              + " shows it. The row owns no files: rollback refuses it and an upgrade never"
              + " re-applies it.")
  static final class RecordPackage implements Callable<Integer> {

    @Spec CommandSpec spec;
    @Mixin GlobalOptions global;

    @Parameters(
        index = "0",
        paramLabel = "<package.zip>",
        description = "The hotfix ZIP as support published it (readme.txt beside the payload).")
    Path packageZip;

    @Override
    public Integer call() {
      PrintWriter out = spec.commandLine().getOut();
      PrintWriter err = spec.commandLine().getErr();
      Redactor redactor = Redactor.global();
      try (Bootstrap boot = Bootstrap.open(global, Env.vars(), Clock.systemUTC())) {
        Services services = boot.services();
        HotfixInstalled row;
        try {
          row = HotfixOps.open(services).record(packageZip);
        } catch (RuntimeException e) {
          return ExitCodes.reportPlanningFailure(out, err, global.json(), e);
        }
        if (global.json()) {
          Map<String, Object> doc = new LinkedHashMap<>();
          doc.put("id", row.id());
          doc.put("title", row.title());
          doc.put("version", row.version());
          doc.put("state", row.state());
          doc.put("origin", row.origin());
          doc.put("recordedAt", row.installedAt());
          out.println(redactor.redact(JsonOut.write(doc)));
        } else {
          out.println(
              redactor.redact(
                  "recorded "
                      + row.id()
                      + " ("
                      + row.title()
                      + ") as applied by hand: it is listed, cannot be rolled back by jrsctl, and"
                      + " an upgrade will not re-apply it"));
        }
        out.flush();
        return ExitCodes.SUCCESS;
      }
    }
  }

  /** {@code jrsctl hotfix list [--json]}: id, title, installed, files, state, origin. */
  @Command(
      name = "list",
      mixinStandardHelpOptions = true,
      exitCodeOnInvalidInput = ExitCodes.USAGE,
      description = "List hotfixes recorded in the state store.")
  static final class ListInstalled implements Callable<Integer> {

    @Spec CommandSpec spec;
    @Mixin GlobalOptions global;

    @Override
    public Integer call() {
      PrintWriter out = spec.commandLine().getOut();
      Redactor redactor = Redactor.global();
      try (Bootstrap boot = Bootstrap.open(global, Env.vars(), Clock.systemUTC())) {
        Services services = boot.services();
        List<HotfixInstalled> hotfixes;
        try {
          hotfixes = HotfixOps.open(services).list();
        } catch (RuntimeException e) {
          return ExitCodes.reportPlanningFailure(
              out, spec.commandLine().getErr(), global.json(), e);
        }
        StateStore store = services.stateStore().get();
        if (global.json()) {
          List<Map<String, Object>> rows = new ArrayList<>();
          for (HotfixInstalled h : hotfixes) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", h.id());
            row.put("title", h.title());
            row.put("version", h.version());
            row.put("installedAt", h.installedAt());
            row.put("installedRunId", h.installedRunId());
            row.put("files", store.hotfixFiles(h.id()).size());
            row.put("state", h.state());
            row.put("snapshotRef", h.snapshotRef());
            row.put("origin", h.origin());
            rows.add(row);
          }
          out.println(redactor.redact(JsonOut.write(rows)));
          out.flush();
          return ExitCodes.SUCCESS;
        }
        if (hotfixes.isEmpty()) {
          out.println(
              "no hotfixes recorded: jrsctl lists the hotfixes it applied itself and those"
                  + " entered with jrsctl hotfix record <package.zip>; one applied by hand and not"
                  + " recorded is not shown");
          out.flush();
          return ExitCodes.SUCCESS;
        }
        Ansi ansi = Ansi.forStdout(global, Env.vars());
        TextTable table = new TextTable();
        table.row(
            ansi.dim("ID"),
            ansi.dim("TITLE"),
            ansi.dim("INSTALLED"),
            ansi.dim("FILES"),
            ansi.dim("STATE"),
            ansi.dim("ORIGIN"));
        for (HotfixInstalled h : hotfixes) {
          table.row(
              h.id(),
              h.title(),
              h.installedAt().truncatedTo(ChronoUnit.SECONDS).toString(),
              Integer.toString(store.hotfixFiles(h.id()).size()),
              h.state().name(),
              // ADR-0030: a recorded row was applied by hand and cannot be rolled back
              h.recorded() ? "recorded (by hand)" : "jrsctl");
        }
        for (String line : table.lines()) {
          out.println(redactor.redact(line));
        }
        out.flush();
        return ExitCodes.SUCCESS;
      }
    }
  }
}
