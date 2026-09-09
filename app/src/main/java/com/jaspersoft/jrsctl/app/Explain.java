package com.jaspersoft.jrsctl.app;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import picocli.CommandLine;
import picocli.CommandLine.IExecutionStrategy;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Model.OptionSpec;
import picocli.CommandLine.ParseResult;

/**
 * {@code --explain} for every command (spec §14 Phase 8, §17 "Embedded help"). Invariants: the
 * option is added once, programmatically, to every command in the tree (including commands added in
 * later phases), so no command class declares it; when it is matched anywhere on the command line
 * the long-form explanation of the deepest command named is printed to that command's standard
 * output and the process exits 0 without constructing a {@code Bootstrap}, touching the home or
 * running any command code (required options and positional parameters are not validated, exactly
 * as for {@code --help}); the text is the command's {@code ### `jrsctl <path> ...`} section of the
 * embedded operator guide, so the explanation and the documentation cannot drift apart; a group
 * command prints the sections of all its subcommands and the root prints every command section.
 */
final class Explain {

  static final String OPTION = "--explain";

  /**
   * Leading command-path words of a heading: lowercase identifiers, never a flag or placeholder.
   */
  private static final Pattern WORD = Pattern.compile("[a-z][a-z0-9-]*");

  private static final Pattern BLANK = Pattern.compile("\\s+");

  private Explain() {}

  /** Adds {@code --explain} to every command under {@code root} and installs the strategy. */
  static CommandLine install(CommandLine root) {
    addOption(root, Collections.newSetFromMap(new IdentityHashMap<>()));
    root.setExecutionStrategy(new Strategy());
    return root;
  }

  private static void addOption(CommandLine cmd, Set<CommandSpec> seen) {
    CommandSpec spec = cmd.getCommandSpec();
    if (seen.add(spec) && spec.findOption(OPTION) == null) {
      spec.addOption(
          OptionSpec.builder(OPTION)
              .help(true)
              .description(
                  "Explain this command: what it does and mutates, how it rolls back, its exit"
                      + " codes and flags; then exit without running it.")
              .build());
    }
    for (CommandLine sub : cmd.getSubcommands().values()) {
      addOption(sub, seen);
    }
  }

  /** The explanation for a command path such as {@code "hotfix apply"} ({@code ""} = root). */
  static Optional<String> text(String commandPath) {
    Map<String, Section> sections = sections();
    Section exact = sections.get(commandPath);
    if (exact != null) {
      return Optional.of(exact.render());
    }
    String prefix = commandPath.isEmpty() ? "" : commandPath + " ";
    StringBuilder sb = new StringBuilder();
    for (Map.Entry<String, Section> e : sections.entrySet()) {
      if (e.getKey().startsWith(prefix)) {
        if (sb.length() > 0) {
          sb.append(System.lineSeparator());
        }
        sb.append(e.getValue().render());
      }
    }
    return sb.length() == 0 ? Optional.empty() : Optional.of(sb.toString());
  }

  /** True when the guide has a section whose heading names exactly this command path. */
  static boolean hasSection(String commandPath) {
    return sections().containsKey(commandPath);
  }

  /** Every {@code ### `jrsctl ...`} section of the embedded operator guide, keyed by path. */
  static Map<String, Section> sections() {
    List<String> lines = EmbeddedDocs.lines("operator-guide").orElse(List.of());
    Map<String, Section> out = new LinkedHashMap<>();
    String key = null;
    String heading = null;
    List<String> body = new ArrayList<>();
    for (String line : lines) {
      if (line.startsWith("#") && !line.startsWith("####")) {
        if (key != null) {
          out.putIfAbsent(key, new Section(heading, List.copyOf(body)));
        }
        key = null;
        body = new ArrayList<>();
        if (line.startsWith("### ")) {
          Optional<String> path = commandPath(line.substring(4));
          if (path.isPresent()) {
            key = path.get();
            heading = line.substring(4).trim();
          }
        }
      } else if (key != null) {
        body.add(line);
      }
    }
    if (key != null) {
      out.putIfAbsent(key, new Section(heading, List.copyOf(body)));
    }
    return out;
  }

  /** {@code `jrsctl hotfix apply <bundle> [--plan]`} becomes {@code "hotfix apply"}. */
  static Optional<String> commandPath(String headingText) {
    String text = headingText.replace("`", "").trim();
    if (!text.equals("jrsctl") && !text.startsWith("jrsctl ")) {
      return Optional.empty();
    }
    List<String> words = new ArrayList<>();
    for (String token : BLANK.splitAsStream(text.substring("jrsctl".length()).trim()).toList()) {
      if (token.isEmpty() || !WORD.matcher(token).matches()) {
        break;
      }
      words.add(token);
    }
    return Optional.of(String.join(" ", words));
  }

  /** One command section of the guide: the heading text (without {@code ###}) and its body. */
  record Section(String heading, List<String> body) {
    String render() {
      StringBuilder sb = new StringBuilder();
      sb.append(heading.replace("`", "")).append(System.lineSeparator());
      sb.append("=".repeat(Math.min(heading.replace("`", "").length(), 100)))
          .append(System.lineSeparator());
      int start = 0;
      int end = body.size();
      while (start < end && body.get(start).isBlank()) {
        start++;
      }
      while (end > start && body.get(end - 1).isBlank()) {
        end--;
      }
      for (String line : body.subList(start, end)) {
        sb.append(line).append(System.lineSeparator());
      }
      return sb.toString();
    }
  }

  /**
   * Prints the explanation and exits 0 when {@code --explain} was given anywhere; otherwise runs
   * the last command like picocli's default {@link CommandLine.RunLast}.
   */
  static final class Strategy implements IExecutionStrategy {
    @Override
    public int execute(ParseResult parseResult) {
      Integer help = CommandLine.executeHelpRequest(parseResult);
      if (help != null) {
        return help;
      }
      if (!requested(parseResult)) {
        return new CommandLine.RunLast().execute(parseResult);
      }
      ParseResult deepest = parseResult;
      while (deepest.hasSubcommand()) {
        deepest = deepest.subcommand();
      }
      CommandLine target = deepest.commandSpec().commandLine();
      String path = deepest.commandSpec().qualifiedName(" ");
      String relative = path.equals("jrsctl") ? "" : path.substring("jrsctl ".length());
      Optional<String> text = text(relative);
      if (text.isEmpty()) {
        PrintWriter err = target.getErr();
        err.println(
            "error: no explanation is embedded for '"
                + path
                + "'; see `jrsctl docs operator-guide` or `"
                + path
                + " --help`");
        err.flush();
        return ExitCodes.USAGE;
      }
      PrintWriter out = target.getOut();
      out.print(text.get());
      out.flush();
      return ExitCodes.SUCCESS;
    }

    private static boolean requested(ParseResult pr) {
      for (ParseResult p = pr; p != null; p = p.hasSubcommand() ? p.subcommand() : null) {
        if (p.hasMatchedOption(OPTION)) {
          return true;
        }
      }
      return false;
    }
  }
}
