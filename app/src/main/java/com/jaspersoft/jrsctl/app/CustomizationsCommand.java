package com.jaspersoft.jrsctl.app;

import com.jaspersoft.jrsctl.core.redact.Redactor;
import com.jaspersoft.jrsctl.core.state.Customization;
import com.jaspersoft.jrsctl.ops.Services;
import com.jaspersoft.jrsctl.ops.customizations.CustomizationException;
import com.jaspersoft.jrsctl.ops.customizations.CustomizationOperations;
import com.jaspersoft.jrsctl.ops.customizations.DefaultCustomizationOperations;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/**
 * {@code jrsctl customizations register|unregister|list|diff <path>} (spec §10.3). Invariants:
 * every subcommand is a plain function over the state store and the snapshot store (no run, no
 * lock); a refused request exits 2 with the reason and the remediation; {@code diff} exits 0 when
 * the file still matches its registered copy and 1 when it differs, mirroring {@code diff(1)}.
 */
@Command(
    name = "customizations",
    mixinStandardHelpOptions = true,
    exitCodeOnInvalidInput = ExitCodes.USAGE,
    description = "Register operator-customised files so an upgrade can re-apply or report them.",
    subcommands = {
      CustomizationsCommand.Register.class,
      CustomizationsCommand.Unregister.class,
      CustomizationsCommand.ListRegistered.class,
      CustomizationsCommand.Diff.class
    })
final class CustomizationsCommand implements Runnable {

  @Spec CommandSpec spec;

  @Override
  public void run() {
    spec.commandLine().usage(spec.commandLine().getOut());
  }

  static CustomizationOperations open(Services services) {
    return new DefaultCustomizationOperations(services);
  }

  static int refused(PrintWriter err, CustomizationException e) {
    err.println(Redactor.global().redact("error: " + e.getMessage() + "; " + e.remediation()));
    err.flush();
    return ExitCodes.PRECHECK_FAILED;
  }

  @Command(
      name = "register",
      mixinStandardHelpOptions = true,
      exitCodeOnInvalidInput = ExitCodes.USAGE,
      description = "Snapshot a customised file and record its current hash as the original.")
  static final class Register implements Callable<Integer> {
    @Spec CommandSpec spec;
    @Mixin GlobalOptions global;

    @Parameters(index = "0", paramLabel = "<path>", description = "File under the installation.")
    Path path;

    @picocli.CommandLine.Option(
        names = "--original",
        paramLabel = "<file>",
        description =
            "The vendor's unmodified copy of the file; its hash is recorded as the original so an"
                + " upgrade can re-apply the customization automatically when the vendor did not"
                + " change the file. Default: the file's current hash.")
    Path original;

    @Override
    public Integer call() {
      PrintWriter out = spec.commandLine().getOut();
      PrintWriter err = spec.commandLine().getErr();
      try (Bootstrap boot = Bootstrap.open(global, Env.vars(), Clock.systemUTC())) {
        Customization c;
        try {
          c = open(boot.services()).register(path, java.util.Optional.ofNullable(original));
        } catch (CustomizationException e) {
          return refused(err, e);
        } catch (RuntimeException e) {
          return ExitCodes.reportPlanningFailure(err, e);
        }
        if (global.json()) {
          out.println(Redactor.global().redact(JsonOut.write(row(c))));
        } else {
          out.println(
              Redactor.global()
                  .redact(
                      "registered " + c.path() + " (original sha256 " + c.originalSha256() + ")"));
        }
        out.flush();
        return ExitCodes.SUCCESS;
      }
    }
  }

  @Command(
      name = "unregister",
      mixinStandardHelpOptions = true,
      exitCodeOnInvalidInput = ExitCodes.USAGE,
      description = "Forget a registered file and delete its snapshot.")
  static final class Unregister implements Callable<Integer> {
    @Spec CommandSpec spec;
    @Mixin GlobalOptions global;

    @Parameters(index = "0", paramLabel = "<path>", description = "Registered file.")
    Path path;

    @Override
    public Integer call() {
      PrintWriter out = spec.commandLine().getOut();
      PrintWriter err = spec.commandLine().getErr();
      try (Bootstrap boot = Bootstrap.open(global, Env.vars(), Clock.systemUTC())) {
        boolean removed;
        try {
          removed = open(boot.services()).unregister(path);
        } catch (CustomizationException e) {
          return refused(err, e);
        } catch (RuntimeException e) {
          return ExitCodes.reportPlanningFailure(err, e);
        }
        if (!removed) {
          err.println(Redactor.global().redact("error: " + path + " is not registered"));
          err.flush();
          return ExitCodes.PRECHECK_FAILED;
        }
        out.println(Redactor.global().redact("unregistered " + path));
        out.flush();
        return ExitCodes.SUCCESS;
      }
    }
  }

  @Command(
      name = "list",
      mixinStandardHelpOptions = true,
      exitCodeOnInvalidInput = ExitCodes.USAGE,
      description = "List registered customizations.")
  static final class ListRegistered implements Callable<Integer> {
    @Spec CommandSpec spec;
    @Mixin GlobalOptions global;

    @Override
    public Integer call() {
      PrintWriter out = spec.commandLine().getOut();
      Redactor redactor = Redactor.global();
      try (Bootstrap boot = Bootstrap.open(global, Env.vars(), Clock.systemUTC())) {
        List<Customization> all;
        try {
          all = open(boot.services()).list();
        } catch (RuntimeException e) {
          return ExitCodes.reportPlanningFailure(spec.commandLine().getErr(), e);
        }
        if (global.json()) {
          List<Map<String, Object>> rows = new ArrayList<>();
          for (Customization c : all) {
            rows.add(row(c));
          }
          out.println(redactor.redact(JsonOut.write(rows)));
          out.flush();
          return ExitCodes.SUCCESS;
        }
        if (all.isEmpty()) {
          out.println("no customizations registered");
          out.flush();
          return ExitCodes.SUCCESS;
        }
        Ansi ansi = Ansi.forStdout(global.noColor(), Env.vars());
        TextTable table = new TextTable();
        table.row(ansi.dim("PATH"), ansi.dim("ORIGINAL SHA256"), ansi.dim("REGISTERED"));
        for (Customization c : all) {
          table.row(
              c.path().toString(),
              c.originalSha256(),
              c.registeredAt().truncatedTo(ChronoUnit.SECONDS).toString());
        }
        for (String line : table.lines()) {
          out.println(redactor.redact(line));
        }
        out.flush();
        return ExitCodes.SUCCESS;
      }
    }
  }

  @Command(
      name = "diff",
      mixinStandardHelpOptions = true,
      exitCodeOnInvalidInput = ExitCodes.USAGE,
      description =
          "Unified diff between the registered copy and the file on disk (exit 1 when they differ).")
  static final class Diff implements Callable<Integer> {
    @Spec CommandSpec spec;
    @Mixin GlobalOptions global;

    @Parameters(index = "0", paramLabel = "<path>", description = "Registered file.")
    Path path;

    @Override
    public Integer call() {
      PrintWriter out = spec.commandLine().getOut();
      PrintWriter err = spec.commandLine().getErr();
      Redactor redactor = Redactor.global();
      try (Bootstrap boot = Bootstrap.open(global, Env.vars(), Clock.systemUTC())) {
        CustomizationOperations.Diff diff;
        try {
          diff = open(boot.services()).diff(path);
        } catch (CustomizationException e) {
          return refused(err, e);
        } catch (RuntimeException e) {
          return ExitCodes.reportPlanningFailure(err, e);
        }
        if (global.json()) {
          Map<String, Object> tree = new LinkedHashMap<>();
          tree.put("path", diff.path().toString());
          tree.put("originalSha256", diff.originalSha256());
          tree.put("registeredSha256", diff.registeredSha256());
          tree.put("currentSha256", diff.currentSha256());
          tree.put("identical", diff.identical());
          tree.put("lines", diff.lines());
          out.println(redactor.redact(JsonOut.write(tree)));
        } else if (diff.identical()) {
          out.println(redactor.redact(diff.path() + " matches its registered copy"));
        } else {
          for (String line : diff.lines()) {
            out.println(redactor.redact(line));
          }
        }
        out.flush();
        return diff.identical() ? ExitCodes.SUCCESS : ExitCodes.USAGE;
      }
    }
  }

  static Map<String, Object> row(Customization c) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("path", c.path().toString());
    row.put("originalSha256", c.originalSha256());
    row.put("snapshotRef", c.snapshotRef());
    row.put("registeredAt", c.registeredAt());
    return row;
  }
}
