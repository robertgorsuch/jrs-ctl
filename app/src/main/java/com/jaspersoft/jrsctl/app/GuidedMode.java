package com.jaspersoft.jrsctl.app;

import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The menu {@code jrsctl} offers when it is started without a command on a terminal (#71).
 * Invariants: every job is carried out by running the ordinary command line through {@code runner},
 * so plans, confirmations, the run lock and exit codes are exactly the CLI's; the command line is
 * printed before it runs, so the operator learns the CLI as they go; the global options the menu
 * was started with are passed on to every command; runs that need recovery are shown before the
 * menu; a job only asks for what it needs, re-asks for a file or directory that does not exist, and
 * returns to the menu when the operator gives an empty answer; end of input quits with exit 0;
 * nothing is written by the menu itself.
 */
final class GuidedMode {

  private final PrintWriter out;
  private final List<String> globalArgs;
  private final Function<String[], Integer> runner;
  private final Supplier<List<String>> pendingRuns;

  GuidedMode(
      PrintWriter out,
      List<String> globalArgs,
      Function<String[], Integer> runner,
      Supplier<List<String>> pendingRuns) {
    this.out = Objects.requireNonNull(out, "out");
    this.globalArgs = List.copyOf(globalArgs);
    this.runner = Objects.requireNonNull(runner, "runner");
    this.pendingRuns = Objects.requireNonNull(pendingRuns, "pendingRuns");
  }

  /** Shows the menu until the operator quits or input ends; returns 0. */
  int run() {
    out.println("jrsctl - JasperReports Server lifecycle tool (Actian Jaspersoft)");
    List<String> pending = pendingRuns.get();
    if (!pending.isEmpty()) {
      out.println();
      out.println("! An earlier job was interrupted and must be finished or undone first:");
      for (String id : pending) {
        out.println("    jrsctl runs recover " + id + " --resume     (or --rollback)");
      }
      out.println("  Choose 7 below to do this.");
    }
    while (true) {
      out.println();
      out.println("What do you want to do?");
      out.println("  1) Set up or change settings");
      out.println("  2) Check server health");
      out.println("  3) Back up content");
      out.println("  4) Restore or copy content");
      out.println("  5) Apply or remove a hotfix");
      out.println("  6) Upgrade the server");
      out.println("  7) Recent jobs and recovery");
      out.println("  q) Quit");
      Optional<String> choice = Prompter.line(out, "Choose [1-7, q]: ");
      if (choice.isEmpty() || choice.get().equalsIgnoreCase("q")) {
        return ExitCodes.SUCCESS;
      }
      switch (choice.get()) {
        case "1" -> settings();
        case "2" -> execute("doctor");
        case "3" -> backup();
        case "4" -> restore();
        case "5" -> hotfix();
        case "6" -> upgrade();
        case "7" -> runs();
        default -> out.println("Please type a number from 1 to 7, or q.");
      }
    }
  }

  // ---- jobs -------------------------------------------------------------------------------------

  private void settings() {
    out.println();
    out.println("  1) Detect the installation and write the settings (first time)");
    out.println("  2) Change one setting");
    out.println("  3) List every setting");
    switch (Prompter.line(out, "Choose [1-3]: ").orElse("")) {
      case "1" -> {
        Optional<String> dir =
            Prompter.line(out, "Installation directory (Enter to search for it): ");
        if (dir.isEmpty()) {
          return;
        }
        if (dir.get().isEmpty()) {
          execute("init");
        } else {
          execute("init", "--install-dir", dir.get());
        }
      }
      case "2" -> {
        execute("config", "keys");
        text("Setting to change, e.g. server.baseUrl").ifPresent(k -> execute("config", "set", k));
      }
      case "3" -> execute("config", "keys");
      default -> {
        // back to the menu
      }
    }
  }

  private void backup() {
    out.println();
    out.println("  1) The whole repository (the server keeps running)");
    out.println("  2) One folder");
    out.println("  3) Everything, with users, roles and settings");
    List<String> args = new ArrayList<>(List.of("export"));
    switch (Prompter.line(out, "Choose [1-3]: ").orElse("")) {
      case "1" -> {
        // the whole repository is the default
      }
      case "2" -> {
        Optional<String> uri = text("Folder, e.g. /public/Samples");
        if (uri.isEmpty()) {
          return;
        }
        args.addAll(List.of("--uri", uri.get()));
      }
      case "3" -> args.add("--full-server");
      default -> {
        return;
      }
    }
    Optional<String> file = text("Backup file to write, e.g. /backups/repository.zip");
    if (file.isEmpty()) {
      return;
    }
    args.addAll(List.of("--out", file.get()));
    execute(args.toArray(String[]::new));
  }

  private void restore() {
    Optional<String> archive = existingFile("Backup file to import");
    if (archive.isEmpty()) {
      return;
    }
    List<String> args = new ArrayList<>(List.of("import", archive.get()));
    if (Prompter.yes(out, "Replace resources that already exist on this server? [y/N] ", false)) {
      args.add("--update");
    }
    execute(args.toArray(String[]::new));
  }

  private void hotfix() {
    out.println();
    out.println("  1) Apply a hotfix");
    out.println("  2) Roll back a hotfix (puts back the files it replaced)");
    out.println("  3) List hotfixes");
    switch (Prompter.line(out, "Choose [1-3]: ").orElse("")) {
      case "1" -> {
        // apply verifies first, shows the plan, and for an official Jaspersoft package asks you
        // to confirm its checksum against the support portal (ADR-0027); a separate verify here
        // used to refuse every official package, since none carries a jrsctl signature
        existingFile("Hotfix file (.zip)").ifPresent(bundle -> execute("hotfix", "apply", bundle));
      }
      case "2" -> {
        execute("hotfix", "list");
        Optional<String> id = text("Hotfix id to roll back");
        if (id.isEmpty()) {
          return;
        }
        // a cumulative hotfix applied later owns the same files; rollback is last-in-first-out
        // (spec §8.3), so without --cascade a blocked rollback stops with exit 2
        if (Prompter.yes(
            out, "Also roll back the hotfixes applied after it, if any? [y/N] ", false)) {
          execute("hotfix", "rollback", id.get(), "--cascade");
        } else {
          execute("hotfix", "rollback", id.get());
        }
      }
      case "3" -> execute("hotfix", "list");
      default -> {
        // back to the menu
      }
    }
  }

  private void upgrade() {
    Optional<String> version = text("Version to upgrade to, e.g. 10.0.0");
    if (version.isEmpty()) {
      return;
    }
    Optional<String> pkg = existingDirectory("Unpacked distribution of that version");
    if (pkg.isEmpty()) {
      return;
    }
    if (!Prompter.yes(
        out,
        "Have you backed up the repository database with your database tools? [y/N] ",
        false)) {
      out.println(
          "Back up the repository database first: jrsctl cannot undo what the upgrade does to it.");
      return;
    }
    execute("upgrade", "--to", version.get(), "--package", pkg.get(), "--db-backup-confirmed");
  }

  private void runs() {
    execute("runs", "list");
    List<String> pending = pendingRuns.get();
    if (pending.isEmpty()) {
      return;
    }
    String id = pending.get(0);
    out.println();
    out.println("  1) Finish job " + id + " from where it stopped");
    out.println("  2) Undo job " + id);
    switch (Prompter.line(out, "Choose [1-2]: ").orElse("")) {
      case "1" -> execute("runs", "recover", id, "--resume");
      case "2" -> execute("runs", "recover", id, "--rollback");
      default -> {
        // back to the menu
      }
    }
  }

  // ---- helpers ----------------------------------------------------------------------------------

  private int execute(String... command) {
    List<String> args = new ArrayList<>(List.of(command));
    args.addAll(globalArgs);
    out.println();
    out.println("Running: jrsctl " + String.join(" ", args));
    out.flush();
    int code = runner.apply(args.toArray(String[]::new));
    out.println(
        code == ExitCodes.SUCCESS
            ? "Done."
            : "Finished with exit code " + code + " (jrsctl docs recovery-runbook explains it).");
    return code;
  }

  /** A non-empty answer, or empty when the operator pressed Enter or input ended. */
  private Optional<String> text(String prompt) {
    return Prompter.line(out, prompt + ": ").filter(s -> !s.isEmpty());
  }

  private Optional<String> existingFile(String prompt) {
    while (true) {
      Optional<String> answer = text(prompt);
      if (answer.isEmpty() || Files.isRegularFile(Path.of(answer.get()))) {
        return answer;
      }
      out.println("  no such file: " + answer.get() + " (press Enter to go back)");
    }
  }

  private Optional<String> existingDirectory(String prompt) {
    while (true) {
      Optional<String> answer = text(prompt);
      if (answer.isEmpty() || Files.isDirectory(Path.of(answer.get()))) {
        return answer;
      }
      out.println("  no such directory: " + answer.get() + " (press Enter to go back)");
    }
  }
}
