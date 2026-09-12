package com.jaspersoft.jrsctl.app;

import com.jaspersoft.jrsctl.core.platform.DefaultHome;
import java.nio.file.Path;
import java.util.Map;

/**
 * Decides where the JSON log goes before any logger exists (spec §5.1: everything lives under
 * {@code $JRSCTL_HOME}). Invariants: the home is derived from {@code --home}, then {@code
 * JRSCTL_HOME}, then the platform default, using only the argument list and the environment so no
 * class that owns a logger is loaded first; an explicit {@code -Djrsctl.log.file} is never
 * overridden. The platform default comes from {@link DefaultHome}, the same class the running
 * {@link com.jaspersoft.jrsctl.core.platform.Platform} uses, so the log and the state store cannot
 * end up in different homes.
 */
public final class LogFile {

  public static final String PROPERTY = "jrsctl.log.file";

  /**
   * Threshold of logback's console appender: {@code OFF} with {@code --json}, else {@code WARN}.
   */
  static final String CONSOLE_LEVEL_PROPERTY = "jrsctl.log.console";

  private LogFile() {}

  static void configure(String[] args, Map<String, String> env) {
    if (System.getProperty(CONSOLE_LEVEL_PROPERTY) == null) {
      System.setProperty(CONSOLE_LEVEL_PROPERTY, consoleLevel(args));
    }
    if (System.getProperty(PROPERTY) != null) {
      return;
    }
    System.setProperty(PROPERTY, resolve(args, env).toString());
  }

  /**
   * What may reach standard error from the loggers (review 4.5): nothing in {@code --json} mode,
   * where standard output carries exactly the documents and standard error must stay silent;
   * warnings and errors otherwise. INFO always goes to the JSON log file, never to the terminal.
   */
  static String consoleLevel(String[] args) {
    for (String arg : args) {
      if (arg.equals("--json")) {
        return "OFF";
      }
    }
    return "WARN";
  }

  static Path resolve(String[] args, Map<String, String> env) {
    return home(args, env).resolve("logs").resolve("jrsctl.log");
  }

  static Path home(String[] args, Map<String, String> env) {
    for (int i = 0; i < args.length; i++) {
      String arg = args[i];
      if (arg.equals("--home") && i + 1 < args.length) {
        return Path.of(args[i + 1]).toAbsolutePath().normalize();
      }
      if (arg.startsWith("--home=")) {
        return Path.of(arg.substring("--home=".length())).toAbsolutePath().normalize();
      }
    }
    String fromEnv = env.get("JRSCTL_HOME");
    if (fromEnv != null && !fromEnv.isBlank()) {
      return Path.of(fromEnv.strip()).toAbsolutePath().normalize();
    }
    return platformDefault(env);
  }

  private static Path platformDefault(Map<String, String> env) {
    return DefaultHome.choose(env).home();
  }
}
