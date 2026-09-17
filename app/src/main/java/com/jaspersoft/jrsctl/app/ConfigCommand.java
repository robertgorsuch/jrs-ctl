package com.jaspersoft.jrsctl.app;

import com.jaspersoft.jrsctl.core.config.Config;
import com.jaspersoft.jrsctl.core.config.ConfigException;
import com.jaspersoft.jrsctl.core.config.ConfigLoader;
import com.jaspersoft.jrsctl.core.config.ConfigWriter;
import com.jaspersoft.jrsctl.core.redact.Redactor;
import com.jaspersoft.jrsctl.core.secrets.EncryptedSecretStore;
import com.jaspersoft.jrsctl.core.secrets.Secret;
import com.jaspersoft.jrsctl.core.secrets.SecretException;
import com.jaspersoft.jrsctl.ops.ConfigShow;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/**
 * {@code jrsctl config show|set|unset|keys} (spec §5.1; #70). Invariants: the effective
 * configuration is printed with every secret as its reference and values behind references are
 * never resolved here; {@code set} and {@code unset} change one key of {@code config.yaml} alone
 * (never the environment or flags), validate the whole file as {@code --set} would before writing,
 * keep the previous file as {@code config.yaml.bak}, audit the change, and say when an environment
 * variable or flag still overrides the key; a password is never accepted on the command line: a
 * password key takes a reference, or a password typed without echo that is stored in {@code
 * secrets.enc} before the file names it as {@code enc:NAME}.
 */
@Command(
    name = "config",
    mixinStandardHelpOptions = true,
    exitCodeOnInvalidInput = ExitCodes.USAGE,
    description = "Inspect and change the configuration.",
    subcommands = {
      ConfigCommand.Show.class,
      ConfigCommand.SetValue.class,
      ConfigCommand.Unset.class,
      ConfigCommand.Keys.class
    })
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

    @picocli.CommandLine.Option(
        names = "--format",
        paramLabel = "yaml|properties",
        description = "Print as YAML (default) or as jrsctl.properties lines.")
    String format = "yaml";

    @Override
    public Integer call() {
      PrintWriter out = spec.commandLine().getOut();
      Redactor redactor = Redactor.global();
      try (Bootstrap boot = Bootstrap.open(global, Env.vars(), Clock.systemUTC())) {
        Config config = boot.services().config();
        if (!global.json() && format.equalsIgnoreCase("properties")) {
          out.println(redactor.redact(ConfigWriter.renderProperties(config).stripTrailing()));
          out.flush();
          return ExitCodes.SUCCESS;
        }
        if (global.json()) {
          out.println(redactor.redact(JsonOut.write(ConfigWriter.toTree(config)).stripTrailing()));
          out.flush();
          return ExitCodes.SUCCESS;
        }
        StringBuilder text = new StringBuilder(ConfigShow.render(config).stripTrailing());
        Map<String, ConfigLoader.Source> sources =
            new ConfigLoader()
                .sources(boot.services().home().configFile(), Env.vars(), global.set());
        for (String key : boot.fromBuildomatic().keySet()) {
          text.append(System.lineSeparator())
              .append("# ")
              .append(key)
              .append(": from ")
              .append(boot.fromBuildomatic().get(key));
        }
        for (Map.Entry<String, ConfigLoader.Source> s : sources.entrySet()) {
          overriddenBy(s.getValue())
              .ifPresent(
                  by ->
                      text.append(System.lineSeparator())
                          .append("# ")
                          .append(s.getKey())
                          .append(": overridden by ")
                          .append(by));
        }
        out.println(redactor.redact(text.toString()));
        out.flush();
        return ExitCodes.SUCCESS;
      }
    }
  }

  /** {@code jrsctl config set <key> [<value>]}. */
  @Command(
      name = "set",
      mixinStandardHelpOptions = true,
      exitCodeOnInvalidInput = ExitCodes.USAGE,
      description =
          "Change one setting in config.yaml; without a value it is asked for (passwords are"
              + " typed hidden and stored encrypted).")
  static final class SetValue implements Callable<Integer> {

    @Spec CommandSpec spec;
    @Mixin GlobalOptions global;

    @Parameters(index = "0", paramLabel = "<key>", description = "Setting, e.g. server.baseUrl.")
    String key;

    @Parameters(
        index = "1",
        arity = "0..1",
        paramLabel = "<value>",
        description = "New value; for a password key, a reference such as env:NAME.")
    String value;

    @Override
    public Integer call() throws IOException {
      PrintWriter out = spec.commandLine().getOut();
      PrintWriter err = spec.commandLine().getErr();
      ConfigLoader loader = new ConfigLoader();
      try (Bootstrap boot = Bootstrap.open(global, Env.vars(), Clock.systemUTC())) {
        Path file = boot.services().home().configFile();
        if (!loader.knownKeys().contains(key)) {
          return fail(
              out,
              err,
              "unknown configuration key " + key,
              "list the keys with: jrsctl config keys");
        }
        if (value == null && global.json()) {
          // a prompt would mix its text into the JSON document
          return fail(
              out,
              err,
              "--json needs the value on the command line",
              "jrsctl config set " + key + " <value> --json");
        }
        String old = ConfigKeys.value(loader.load(file, Map.of(), Map.of()), key);
        char[] typed = new char[0];
        List<Secret> held = new ArrayList<>();
        try {
          String raw;
          if (ConfigKeys.isSecret(key)) {
            if (value != null) {
              if (!isReference(value)) {
                return fail(
                    out,
                    err,
                    "a password is never given on the command line, where the shell history and"
                        + " process list keep it",
                    "type it instead with: jrsctl config set "
                        + key
                        + ", or give a reference such as env:NAME, file:/path or enc:NAME");
              }
              raw = value;
            } else {
              String name = old.startsWith("enc:") ? old.substring(4) : ConfigKeys.secretName(key);
              Optional<char[]> answer =
                  Prompter.secret(
                      out, "Password for " + key + " (stored encrypted as enc:" + name + "): ");
              if (answer.isEmpty() || answer.get().length == 0) {
                answer.ifPresent(c -> Arrays.fill(c, '\0'));
                return fail(
                    out, err, "no password entered; nothing changed", "run the command again");
              }
              typed = answer.get();
              raw = "enc:" + name;
            }
          } else if (value != null) {
            raw = value;
          } else {
            Optional<String> answer =
                Prompter.line(out, "New value for " + key + " [" + old + "]: ");
            if (answer.isEmpty() || answer.get().isEmpty()) {
              out.println("nothing changed");
              out.flush();
              return ExitCodes.SUCCESS;
            }
            raw = answer.get();
          }
          Config updated;
          try {
            updated = loader.fileWith(file, key, raw);
          } catch (ConfigException e) {
            return fail(out, err, e.getMessage(), e.remediation());
          }
          if (typed.length > 0) {
            Optional<EncryptedSecretStore> store = SecretStores.forWriting(boot, global, out, held);
            if (store.isEmpty()) {
              return fail(
                  out, err, "no passphrase given; nothing changed", "run the command again");
            }
            try {
              if (!store.get().exists()) {
                store.get().init();
              }
              try (Secret secret = Secret.of(typed)) {
                store.get().set(raw.substring(4), secret);
              }
            } catch (SecretException e) {
              return fail(
                  out,
                  err,
                  "password not stored; nothing changed: " + e.getMessage(),
                  "run the command again");
            }
            boot.services().stateStore().get().audit("operator", "secrets.set", raw.substring(4));
          }
          Optional<Path> backup = write(updated, file);
          boot.services().stateStore().get().audit("operator", "config.set", key);
          report(out, key, old, ConfigKeys.value(updated, key), file, backup, global);
          warnIfOverridden(out, loader, file, key, global);
          return ExitCodes.SUCCESS;
        } finally {
          Arrays.fill(typed, '\0');
          held.forEach(Secret::close);
        }
      }
    }

    private int fail(PrintWriter out, PrintWriter err, String message, String remediation) {
      return ExitCodes.fail(
          out, err, global.json(), ExitCodes.PRECHECK_FAILED, message, Optional.of(remediation));
    }

    private static boolean isReference(String candidate) {
      return candidate.startsWith("env:")
          || candidate.startsWith("file:")
          || candidate.startsWith("enc:");
    }
  }

  /** {@code jrsctl config unset <key>}. */
  @Command(
      name = "unset",
      mixinStandardHelpOptions = true,
      exitCodeOnInvalidInput = ExitCodes.USAGE,
      description = "Remove one setting from config.yaml so its default applies.")
  static final class Unset implements Callable<Integer> {

    @Spec CommandSpec spec;
    @Mixin GlobalOptions global;

    @Parameters(index = "0", paramLabel = "<key>", description = "Setting, e.g. server.runAsUser.")
    String key;

    @Override
    public Integer call() throws IOException {
      PrintWriter out = spec.commandLine().getOut();
      PrintWriter err = spec.commandLine().getErr();
      ConfigLoader loader = new ConfigLoader();
      try (Bootstrap boot = Bootstrap.open(global, Env.vars(), Clock.systemUTC())) {
        Path file = boot.services().home().configFile();
        Config updated;
        String old;
        try {
          old =
              loader.knownKeys().contains(key)
                  ? ConfigKeys.value(loader.load(file, Map.of(), Map.of()), key)
                  : "";
          updated = loader.fileWithout(file, key);
        } catch (ConfigException e) {
          return ExitCodes.fail(
              out,
              err,
              global.json(),
              ExitCodes.PRECHECK_FAILED,
              e.getMessage(),
              Optional.of(e.remediation()));
        }
        Optional<Path> backup = write(updated, file);
        boot.services().stateStore().get().audit("operator", "config.unset", key);
        report(out, key, old, ConfigKeys.value(updated, key), file, backup, global);
        warnIfOverridden(out, loader, file, key, global);
        return ExitCodes.SUCCESS;
      }
    }
  }

  /** {@code jrsctl config keys}: every setting with its value, source and description. */
  @Command(
      name = "keys",
      mixinStandardHelpOptions = true,
      exitCodeOnInvalidInput = ExitCodes.USAGE,
      description =
          "List every setting with its current value, where it comes from and what it is for.")
  static final class Keys implements Callable<Integer> {

    @Spec CommandSpec spec;
    @Mixin GlobalOptions global;

    @Override
    public Integer call() {
      PrintWriter out = spec.commandLine().getOut();
      Redactor redactor = Redactor.global();
      try (Bootstrap boot = Bootstrap.open(global, Env.vars(), Clock.systemUTC())) {
        Config config = boot.services().config();
        Map<String, ConfigLoader.Source> sources =
            new ConfigLoader()
                .sources(boot.services().home().configFile(), Env.vars(), global.set());
        if (global.json()) {
          List<Map<String, String>> rows = new ArrayList<>();
          for (Map.Entry<String, ConfigLoader.Source> s : sources.entrySet()) {
            Map<String, String> row = new LinkedHashMap<>();
            row.put("key", s.getKey());
            row.put("value", ConfigKeys.value(config, s.getKey()));
            row.put("source", label(s.getKey(), s.getValue(), boot));
            row.put("description", ConfigKeys.description(s.getKey()));
            rows.add(row);
          }
          JsonOut.print(out, rows);
          return ExitCodes.SUCCESS;
        }
        TextTable table = new TextTable().row("KEY", "VALUE", "SOURCE", "DESCRIPTION");
        for (Map.Entry<String, ConfigLoader.Source> s : sources.entrySet()) {
          table.row(
              s.getKey(),
              ConfigKeys.value(config, s.getKey()),
              label(s.getKey(), s.getValue(), boot),
              ConfigKeys.description(s.getKey()));
        }
        table.lines().forEach(line -> out.println(redactor.redact(line)));
        out.println();
        out.println("Change one with: jrsctl config set <key> <value>");
        out.flush();
        return ExitCodes.SUCCESS;
      }
    }

    private static String label(String key, ConfigLoader.Source source, Bootstrap boot) {
      if (boot.fromBuildomatic().containsKey(key)) {
        return "default_master.properties";
      }
      return switch (source.origin()) {
        case FLAG -> "--set";
        case ENVIRONMENT -> source.detail();
        case FILE -> "config.yaml";
        case DEFAULT -> "default";
      };
    }
  }

  // ---- shared -----------------------------------------------------------------------------------

  /**
   * Writes {@code config} to {@code file}, keeping the previous file as {@code config.yaml.bak}.
   */
  private static Optional<Path> write(Config config, Path file) throws IOException {
    Optional<Path> backup = Optional.empty();
    if (Files.exists(file)) {
      Path bak = file.resolveSibling(file.getFileName() + ".bak");
      Files.copy(file, bak, StandardCopyOption.REPLACE_EXISTING);
      backup = Optional.of(bak);
    }
    ConfigWriter.write(config, file);
    return backup;
  }

  private static void report(
      PrintWriter out,
      String key,
      String old,
      String now,
      Path file,
      Optional<Path> backup,
      GlobalOptions global) {
    if (global.json()) {
      Map<String, String> json = new LinkedHashMap<>();
      json.put("key", key);
      json.put("old", old);
      json.put("new", now);
      json.put("file", file.toString());
      backup.ifPresent(b -> json.put("backup", b.toString()));
      JsonOut.print(out, json);
      return;
    }
    out.println(key + ": " + shown(old) + " -> " + shown(now));
    out.println(
        "wrote "
            + file
            + backup.map(b -> " (previous file saved as " + b.getFileName() + ")").orElse(""));
    out.flush();
  }

  private static String shown(String value) {
    return value.isEmpty() ? "(not set)" : value;
  }

  private static void warnIfOverridden(
      PrintWriter out, ConfigLoader loader, Path file, String key, GlobalOptions global) {
    if (global.json()) {
      return;
    }
    overriddenBy(loader.sources(file, Env.vars(), global.set()).get(key))
        .ifPresent(
            by ->
                out.println(
                    "note: "
                        + by
                        + " still overrides this setting, so jrsctl keeps using that value"));
    out.flush();
  }

  private static Optional<String> overriddenBy(ConfigLoader.Source source) {
    return switch (source.origin()) {
      case FLAG -> Optional.of("--set");
      case ENVIRONMENT -> Optional.of(source.detail());
      case FILE, DEFAULT -> Optional.empty();
    };
  }
}
