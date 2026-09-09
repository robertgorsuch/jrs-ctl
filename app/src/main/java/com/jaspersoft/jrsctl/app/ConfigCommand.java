package com.jaspersoft.jrsctl.app;

import com.jaspersoft.jrsctl.core.config.Config;
import com.jaspersoft.jrsctl.core.config.ConfigWriter;
import com.jaspersoft.jrsctl.core.redact.Redactor;
import com.jaspersoft.jrsctl.ops.ConfigShow;
import java.io.PrintWriter;
import java.time.Clock;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

/**
 * {@code jrsctl config} with the {@code show} subcommand (spec §5.1). Invariant: the effective
 * configuration is printed with every secret as its reference; values behind references are never
 * resolved here.
 */
@Command(
    name = "config",
    mixinStandardHelpOptions = true,
    exitCodeOnInvalidInput = ExitCodes.USAGE,
    description = "Inspect the effective configuration.",
    subcommands = {ConfigCommand.Show.class})
final class ConfigCommand implements Runnable {

  @Spec CommandSpec spec;

  @Override
  public void run() {
    spec.commandLine().usage(spec.commandLine().getOut());
  }

  /** {@code jrsctl config show}: the merged flag &gt; env &gt; file &gt; default view as YAML. */
  @Command(
      name = "show",
      mixinStandardHelpOptions = true,
      exitCodeOnInvalidInput = ExitCodes.USAGE,
      description = "Print the effective configuration (flag > env > file > default) as YAML.")
  static final class Show implements Callable<Integer> {

    @Spec CommandSpec spec;
    @Mixin GlobalOptions global;

    @Override
    public Integer call() {
      PrintWriter out = spec.commandLine().getOut();
      Redactor redactor = Redactor.global();
      try (Bootstrap boot = Bootstrap.open(global, Env.vars(), Clock.systemUTC())) {
        Config config = boot.services().config();
        String text =
            global.json() ? JsonOut.write(ConfigWriter.toTree(config)) : ConfigShow.render(config);
        out.println(redactor.redact(text.stripTrailing()));
        out.flush();
        return ExitCodes.SUCCESS;
      }
    }
  }
}
