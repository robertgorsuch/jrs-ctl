package com.jaspersoft.jrsctl.app;

import com.jaspersoft.jrsctl.core.config.Config;
import com.jaspersoft.jrsctl.core.config.ConfigWriter;
import com.jaspersoft.jrsctl.core.redact.Redactor;
import com.jaspersoft.jrsctl.ops.init.InitOperation;
import com.jaspersoft.jrsctl.ops.init.InitReport;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/**
 * {@code jrsctl init}: detect the installation and write {@code config.yaml} (spec §12.0).
 * Invariants: nothing is written without confirmation unless {@code --yes}; an existing file is
 * kept unless {@code --force}; the values are shown with their sources first so the operator can
 * judge them; secrets are never read and only {@code env:} placeholders are written.
 */
@Command(
    name = "init",
    mixinStandardHelpOptions = true,
    exitCodeOnInvalidInput = ExitCodes.USAGE,
    description = "Detect the JasperReports Server installation and write config.yaml.")
final class InitCommand implements Callable<Integer> {

  @Spec CommandSpec spec;
  @Mixin GlobalOptions global;

  @Option(
      names = "--install-dir",
      paramLabel = "<dir>",
      description = "Installation root to inspect first (skips the platform search).")
  Path installDir;

  @Option(names = "--force", description = "Overwrite an existing config.yaml.")
  boolean force;

  @Override
  public Integer call() throws IOException {
    PrintWriter out = spec.commandLine().getOut();
    PrintWriter err = spec.commandLine().getErr();
    Redactor redactor = Redactor.global();
    try (Bootstrap boot = Bootstrap.open(global, Env.vars(), Clock.systemUTC())) {
      InitOperation op = new InitOperation(boot.services());
      InitReport report = op.detect(Optional.ofNullable(installDir));
      Config config = op.toConfig(report);
      Path target = boot.services().home().configFile();
      if (global.json()) {
        // one document: the detection report plus whether config.yaml was written (only --yes
        // writes in JSON mode, there is no prompt)
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("detectedInstall", report.detectedInstall());
        json.put("values", report.values());
        json.put("config", ConfigWriter.toTree(config));
        json.put("configFile", target.toString());
        if (global.yes()) {
          try {
            Path written = op.write(config, force);
            json.put("written", true);
            json.put("writtenTo", written.toString());
          } catch (FileAlreadyExistsException e) {
            return alreadyExists(out, err, e);
          }
        } else {
          json.put("written", false);
        }
        JsonOut.print(out, json);
        return ExitCodes.SUCCESS;
      }
      TextTable table = new TextTable();
      for (InitReport.Detected d : report.values()) {
        table.row(d.key(), d.value(), Ansi.forStdout(global, Env.vars()).dim(d.source()));
      }
      for (String line : table.lines()) {
        out.println(redactor.redact(line));
      }
      if (!report.detectedInstall()) {
        out.println("no installation detected; pass --install-dir <root> to point at one");
      }
      if (!global.yes()) {
        if (global.nonInteractive()) {
          err.println("error: confirmation required to write " + target);
          err.println(
              "pass --yes to write without asking (review 4.4: --non-interactive never confirms)");
          err.flush();
          return ExitCodes.PRECHECK_FAILED;
        }
        if (!Confirm.ask(out, "Write config to " + target + "? [y/N] ")) {
          out.println("config not written (pass --yes to write without asking)");
          out.flush();
          return ExitCodes.SUCCESS;
        }
      }
      try {
        Path written = op.write(config, force);
        out.println("wrote " + written);
        out.flush();
        return ExitCodes.SUCCESS;
      } catch (FileAlreadyExistsException e) {
        return alreadyExists(out, err, e);
      }
    }
  }

  private int alreadyExists(PrintWriter out, PrintWriter err, FileAlreadyExistsException e) {
    return ExitCodes.fail(
        out,
        err,
        global.json(),
        ExitCodes.PRECHECK_FAILED,
        e.getFile() + " already exists",
        Optional.of("pass --force to overwrite it"));
  }
}
