package com.jaspersoft.jrsctl.app;

import com.jaspersoft.jrsctl.core.engine.Plan;
import com.jaspersoft.jrsctl.core.redact.Redactor;
import com.jaspersoft.jrsctl.core.secrets.SecretRef;
import com.jaspersoft.jrsctl.core.state.HotfixInstalled;
import com.jaspersoft.jrsctl.core.state.StateStore;
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
    description = "Build, verify, apply, roll back and list signed hotfix bundles.",
    subcommands = {
      HotfixCommand.Build.class,
      HotfixCommand.Verify.class,
      HotfixCommand.Apply.class,
      HotfixCommand.Rollback.class,
      HotfixCommand.ListInstalled.class
    })
final class HotfixCommand implements Runnable {

  @Spec CommandSpec spec;

  @Override
  public void run() {
    spec.commandLine().usage(spec.commandLine().getOut());
  }

  /** {@code jrsctl hotfix build <dir> --key <secretRef> --out <bundle>}. */
  @Command(
      name = "build",
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
              out, r.items(), r.counts(), Ansi.forStdout(global.noColor(), Env.vars()), redactor);
          out.println(
              report.ok()
                  ? "bundle " + report.manifestId() + " ok"
                  : "bundle " + report.manifestId() + " rejected");
          out.flush();
        }
        return report.ok() ? ExitCodes.SUCCESS : ExitCodes.SIGNATURE_FAILED;
      }
    }

    static List<ReportItem> items(HotfixOperations.VerifyReport report) {
      List<ReportItem> items = new ArrayList<>();
      items.add(
          report.signatureValid()
              ? ReportItem.pass(
                  "signature", "signed by " + report.signedBy().orElse("a trusted key"))
              : ReportItem.fail(
                  "signature",
                  "missing, or not made by a trusted key",
                  "add the signer's public key with `jrsctl keys add`, or use --allow-unsigned"
                      + " on apply (audited)"));
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
          if (!report.signatureValid()) {
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
            services
                .stateStore()
                .get()
                .audit(
                    "operator",
                    "hotfix.apply.allow-unsigned",
                    report.manifestId() + " from " + bundle.toAbsolutePath().normalize());
          }
          planned = ops.planApply(bundle, new HotfixOperations.ApplyOptions(allowUnsigned));
        } catch (RuntimeException e) {
          return ExitCodes.reportPlanningFailure(out, err, global.json(), e);
        }
        PlanExecutor executor = new PlanExecutor(services, global, out, err, Env.vars());
        return executor.execute(
            new PlanExecutor.Request(
                planned,
                PlanRegistry.HOTFIX_APPLY,
                PlanRegistry.applyArgs(bundle, allowUnsigned),
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

  /** {@code jrsctl hotfix list [--json]}: id, title, installed, files, state. */
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
            rows.add(row);
          }
          out.println(redactor.redact(JsonOut.write(rows)));
          out.flush();
          return ExitCodes.SUCCESS;
        }
        if (hotfixes.isEmpty()) {
          out.println("no hotfixes recorded");
          out.flush();
          return ExitCodes.SUCCESS;
        }
        Ansi ansi = Ansi.forStdout(global.noColor(), Env.vars());
        TextTable table = new TextTable();
        table.row(
            ansi.dim("ID"),
            ansi.dim("TITLE"),
            ansi.dim("INSTALLED"),
            ansi.dim("FILES"),
            ansi.dim("STATE"));
        for (HotfixInstalled h : hotfixes) {
          table.row(
              h.id(),
              h.title(),
              h.installedAt().truncatedTo(ChronoUnit.SECONDS).toString(),
              Integer.toString(store.hotfixFiles(h.id()).size()),
              h.state().name());
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
