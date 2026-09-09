package com.jaspersoft.jrsctl.app;

import java.io.PrintWriter;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/**
 * {@code jrsctl docs [<name>] [--json]} (spec §14 Phase 8, §17): lists or prints the documentation
 * embedded in the jar so an operator on an offline host has the operator guide, the hotfix
 * authoring guide, the security notes and the README without network access. Invariants: read-only
 * and independent of the jrsctl home and configuration (no {@code Bootstrap}); the listing is the
 * same set of names that {@code docs <name>} accepts; an unknown name is a usage error (exit 1)
 * that lists the valid names; {@code --json} affects the listing only and emits an array of {@code
 * {name, title, bytes}}.
 */
@Command(
    name = "docs",
    mixinStandardHelpOptions = true,
    exitCodeOnInvalidInput = ExitCodes.USAGE,
    description =
        "List the documentation embedded in the jar, or print one document to standard output.")
final class DocsCommand implements Callable<Integer> {

  @Spec CommandSpec spec;

  @Parameters(
      index = "0",
      arity = "0..1",
      paramLabel = "<name>",
      description = "Document to print: operator-guide, hotfix-authoring, security or readme.")
  String name;

  @Option(names = "--json", description = "Emit the listing as a JSON array of {name,title,bytes}.")
  boolean json;

  @Override
  public Integer call() {
    PrintWriter out = spec.commandLine().getOut();
    PrintWriter err = spec.commandLine().getErr();
    if (name == null) {
      List<EmbeddedDocs.Doc> docs = EmbeddedDocs.list();
      if (json) {
        out.println(JsonOut.write(docs));
      } else {
        TextTable table = new TextTable().row("NAME", "TITLE", "BYTES");
        for (EmbeddedDocs.Doc d : docs) {
          table.row(d.name(), d.title(), Long.toString(d.bytes()));
        }
        table.lines().forEach(out::println);
        out.println();
        out.println("Print one with: jrsctl docs <name>");
      }
      out.flush();
      return ExitCodes.SUCCESS;
    }
    if (EmbeddedDocs.print(name, out)) {
      return ExitCodes.SUCCESS;
    }
    err.println(
        "error: no embedded document named '"
            + name
            + "'; valid names: "
            + String.join(", ", EmbeddedDocs.NAMES));
    err.flush();
    return ExitCodes.USAGE;
  }
}
