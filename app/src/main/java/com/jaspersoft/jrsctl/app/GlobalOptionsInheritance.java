package com.jaspersoft.jrsctl.app;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Model.OptionSpec;
import picocli.CommandLine.ParseResult;

/**
 * Carries the {@link GlobalOptions} given before a subcommand down to the command that runs (#354).
 * The root and every leaf mix in their own {@link GlobalOptions}, so picocli parses {@code jrsctl
 * --home <dir> runs list} into the root's copy and the leaf would read its own, empty one: the
 * option was accepted and silently dropped. Invariants: a level without a {@link GlobalOptions}
 * mixin neither receives nor passes on anything; an option a later (deeper) level gives itself wins
 * over the same option given earlier, judged per {@link #GROUPS group} so {@code --no-color} before
 * the command does not override {@code --color always} after it; {@code --set} entries are merged,
 * the later level winning per key; nothing is read from or written to anything but the parsed
 * option values.
 */
final class GlobalOptionsInheritance {

  /**
   * Every {@link GlobalOptions} option, grouped where two flags decide one setting. {@code
   * GlobalOptionsInheritanceTest} fails when an option is added to {@link GlobalOptions} and not
   * here.
   */
  static final List<List<String>> GROUPS =
      List.of(
          List.of("--home"),
          List.of("--set"),
          List.of("--passphrase-file"),
          List.of("--yes"),
          List.of("--non-interactive"),
          List.of("--color", "--no-color"),
          List.of("--ascii"),
          List.of("--json"),
          List.of("--no-pager"));

  private static final String SET = "--set";

  private GlobalOptionsInheritance() {}

  /** Applies the inheritance to the parsed command line, from the root down to the leaf. */
  static void apply(ParseResult root) {
    // option name -> the value the levels so far gave it, in the order they were given
    Map<String, Object> given = new LinkedHashMap<>();
    Map<String, String> sets = new LinkedHashMap<>();
    for (ParseResult level = root; level != null; level = level.subcommand()) {
      CommandSpec spec = level.commandSpec();
      if (!hasGlobalOptions(spec)) {
        continue;
      }
      for (List<String> group : GROUPS) {
        boolean ownGroup = group.stream().anyMatch(level::hasMatchedOption);
        for (String name : group) {
          OptionSpec option = spec.findOption(name);
          if (option == null) {
            continue;
          }
          if (name.equals(SET)) {
            Map<String, String> own = option.getValue();
            Map<String, String> merged = new LinkedHashMap<>(sets);
            merged.putAll(own == null ? Map.of() : own);
            sets = merged;
            option.setValue(new LinkedHashMap<>(merged));
          } else if (ownGroup) {
            if (level.hasMatchedOption(name)) {
              given.put(name, option.getValue());
            } else {
              given.remove(name);
            }
          } else if (given.containsKey(name)) {
            option.setValue(given.get(name));
          }
        }
      }
    }
  }

  private static boolean hasGlobalOptions(CommandSpec spec) {
    return spec.mixins().values().stream().anyMatch(m -> m.userObject() instanceof GlobalOptions);
  }
}
