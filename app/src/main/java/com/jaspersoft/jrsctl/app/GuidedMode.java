package com.jaspersoft.jrsctl.app;

import com.jaspersoft.jrsctl.core.config.ConfigLoader;
import com.jaspersoft.jrsctl.core.platform.UserPaths;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The menu {@code jrsctl} offers when it is started without a command on a terminal (#71; spec
 * §17.1). Invariants: every job is carried out by running the ordinary command line through {@code
 * runner}, so plans, confirmations, the run lock and exit codes are exactly the CLI's; the command
 * line is printed before it runs, so the operator learns the CLI as they go; the global options the
 * menu was started with are passed on to every command; runs that need recovery are shown before
 * the menu; every option the vendor's documented paths need is reachable here (field test 2, I4:
 * the import options, the export scope, the upgrade mode, Tomcat, existing export and rehearsal),
 * with Enter meaning the CLI's own default; a job asks only for what it needs, re-asks for a file,
 * directory or choice that is not acceptable, and returns to the menu when the operator gives an
 * empty answer where one is required; end of input quits with exit 0; nothing is written by the
 * menu itself.
 */
final class GuidedMode {

  private final PrintWriter out;
  private final List<String> globalArgs;
  private final Function<String[], Integer> runner;
  private final Supplier<List<String>> pendingRuns;
  private final Supplier<Optional<Path>> snapshotsDir;

  GuidedMode(
      PrintWriter out,
      List<String> globalArgs,
      Function<String[], Integer> runner,
      Supplier<List<String>> pendingRuns) {
    this(out, globalArgs, runner, pendingRuns, Optional::empty);
  }

  GuidedMode(
      PrintWriter out,
      List<String> globalArgs,
      Function<String[], Integer> runner,
      Supplier<List<String>> pendingRuns,
      Supplier<Optional<Path>> snapshotsDir) {
    this.out = Objects.requireNonNull(out, "out");
    this.globalArgs = List.copyOf(globalArgs);
    this.runner = Objects.requireNonNull(runner, "runner");
    this.pendingRuns = Objects.requireNonNull(pendingRuns, "pendingRuns");
    this.snapshotsDir = Objects.requireNonNull(snapshotsDir, "snapshotsDir");
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
      out.println("  8) Read the documentation");
      out.println("  q) Quit");
      out.println();
      out.println("Each entry runs an ordinary command and prints it first. jrsctl --help lists");
      out.println("every command, jrsctl console opens the web console, --json makes any command");
      out.println("machine-readable.");
      Optional<String> choice = Prompter.line(out, "Choose [1-8, q]: ");
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
        case "8" -> docs();
        default -> out.println("Please type a number from 1 to 8, or q.");
      }
    }
  }

  // ---- jobs -------------------------------------------------------------------------------------

  private void settings() {
    out.println();
    out.println("  1) Detect the installation and write the settings");
    out.println("  2) Show and change settings");
    switch (Prompter.line(out, "Choose [1-2]: ").orElse("")) {
      case "1" -> detect();
      case "2" -> changeSettings();
      default -> {
        // back to the menu
      }
    }
  }

  /** Enter means "search for it"; a directory that does not exist is asked for again. */
  private void detect() {
    boolean asked = false;
    while (true) {
      Optional<String> dir =
          Prompter.line(out, "Installation directory (Enter to search for it): ");
      if (dir.isEmpty()) {
        return;
      }
      if (dir.get().isEmpty()) {
        if (!asked) {
          execute("init");
        }
        return;
      }
      if (Files.isDirectory(local(dir.get()))) {
        execute("init", "--install-dir", dir.get());
        return;
      }
      out.println("  no such directory: " + dir.get() + " (press Enter to go back)");
      asked = true;
    }
  }

  /** The table once, then key and value until Enter; an unknown key is re-asked, not sent. */
  private void changeSettings() {
    execute("config", "keys");
    out.println("Change one with: jrsctl config set <key> <value>, or here, one at a time.");
    Set<String> known = new ConfigLoader().knownKeys();
    while (true) {
      Optional<String> key = text("Setting to change (Enter to finish)");
      if (key.isEmpty()) {
        return;
      }
      if (!known.contains(key.get())) {
        out.println("  unknown setting " + key.get() + " (the table above lists them)");
        continue;
      }
      Optional<String> value = text("New value for " + key.get() + " (Enter to skip)");
      if (value.isEmpty()) {
        continue;
      }
      execute("config", "set", key.get(), value.get());
    }
  }

  private void backup() {
    out.println();
    out.println(
        "  1) Everything in the repository, over REST (no vendor tools; the server keeps running)");
    out.println("  2) One folder");
    out.println("  3) Everything including users, roles and settings, with the vendor js-export");
    List<String> args = new ArrayList<>(List.of("export"));
    boolean fullServer = false;
    switch (Prompter.line(out, "Choose [1-3]: ").orElse("")) {
      case "1" -> args.addAll(List.of("--strategy", "rest"));
      case "2" -> {
        Optional<String> uri = text("Folder, e.g. /public/Samples");
        if (uri.isEmpty()) {
          return;
        }
        args.addAll(List.of("--uri", uri.get()));
      }
      case "3" -> {
        args.add("--full-server");
        fullServer = true;
        if (Prompter.yes(
            out,
            "Stop the server while js-export runs (consistent copy, short outage)? [y/N] ",
            false)) {
          args.add("--stop-service");
        }
      }
      default -> {
        return;
      }
    }
    Optional<String> file = text("Backup file to write, e.g. /backups/repository.zip");
    if (file.isEmpty()) {
      return;
    }
    if (!fullServer) {
      // a full-server export already carries users, roles, events and settings
      if (Prompter.yes(out, "Include users and roles? [y/N] ", false)) {
        args.add("--users-roles");
      }
      if (Prompter.yes(out, "Include access, audit and monitoring events? [y/N] ", false)) {
        args.addAll(List.of("--access-events", "--audit-events", "--monitoring"));
      }
      if (Prompter.yes(out, "Include server settings? [y/N] ", false)) {
        args.add("--settings");
      }
    }
    if (Prompter.yes(out, "Portable (decryptable on another server)? [y/N] ", false)) {
      args.add("--portable");
    }
    Optional<String> organization = Prompter.line(out, "Organisation (Enter for all): ");
    if (organization.isEmpty()) {
      return;
    }
    if (!organization.get().isEmpty()) {
      args.addAll(List.of("--organization", organization.get()));
    }
    args.addAll(List.of("--out", file.get()));
    execute(args.toArray(String[]::new));
  }

  private void restore() {
    Optional<String> archive = existingFile("Backup file to import");
    if (archive.isEmpty()) {
      return;
    }
    out.println(
        "jrsctl first exports the resources the archive will touch (the whole repository when the"
            + " archive has no .jrsctl.json beside it) as a rollback copy under "
            + snapshotsDir.get().map(Path::toString).orElse("the jrsctl home's snapshots directory")
            + "; a failed import re-imports that copy. Enter keeps the default for each question.");
    List<String> args = new ArrayList<>(List.of("import", archive.get()));
    if (Prompter.yes(out, "Replace resources that already exist on this server? [y/N] ", false)) {
      args.add("--update");
    }
    if (Prompter.yes(out, "Skip themes? [y/N] ", false)) {
      args.add("--skip-themes");
    }
    Optional<String> broken =
        choice(
            "When the archive has broken dependencies: fail, skip or include [fail]",
            "fail",
            Set.of("fail", "skip", "include"));
    if (broken.isEmpty()) {
      return;
    }
    if (!broken.get().equals("fail")) {
      args.addAll(List.of("--broken-dependencies", broken.get()));
    }
    Optional<String> strategy =
        choice("Strategy: auto, rest or vendor [auto]", "auto", Set.of("auto", "rest", "vendor"));
    if (strategy.isEmpty()) {
      return;
    }
    if (!strategy.get().equals("auto")) {
      args.addAll(List.of("--strategy", strategy.get()));
    }
    Optional<String> alias =
        Prompter.line(
            out, "Key alias the archive was encrypted with (Enter for this server's key): ");
    if (alias.isEmpty()) {
      return;
    }
    if (!alias.get().isEmpty()) {
      args.addAll(List.of("--key-alias", alias.get()));
    }
    Optional<String> organization =
        Prompter.line(out, "Import into organisation (Enter for the archive's own): ");
    if (organization.isEmpty()) {
      return;
    }
    if (!organization.get().isEmpty()) {
      args.addAll(List.of("--organization", organization.get()));
      if (Prompter.yes(
          out, "Merge when the archive's organisation id differs from it? [y/N] ", false)) {
        args.add("--merge-organization");
      }
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
    Optional<String> mode = choice("Mode [newdb]", "newdb", Set.of("newdb", "samedb"));
    if (mode.isEmpty()) {
      return;
    }
    boolean samedb = mode.get().equals("samedb");
    List<String> args =
        new ArrayList<>(List.of("upgrade", "--to", version.get(), "--package", pkg.get()));
    if (samedb) {
      args.addAll(List.of("--mode", "samedb"));
    }
    Optional<Optional<String>> tomcat =
        optionalExistingDirectory("New Tomcat directory (Enter to keep the current one)");
    if (tomcat.isEmpty()) {
      return;
    }
    tomcat.get().ifPresent(t -> args.addAll(List.of("--tomcat-dir", t)));
    if (!samedb) {
      Optional<Optional<String>> export =
          optionalExistingFile("Export taken earlier to upgrade from (Enter to export now)");
      if (export.isEmpty()) {
        return;
      }
      if (export.get().isPresent()) {
        args.addAll(List.of("--export", export.get().get()));
        Optional<String> alias =
            Prompter.line(out, "Key alias it was encrypted with (Enter for this server's key): ");
        if (alias.isEmpty()) {
          return;
        }
        if (!alias.get().isEmpty()) {
          args.addAll(List.of("--key-alias", alias.get()));
        }
      }
      // upgrade guide 10.1 p.80: the newdb script leaves the events behind (issue #106)
      if (Prompter.yes(
          out,
          "Import the access, audit and monitoring events after the vendor run"
              + " (js-upgrade-newdb leaves them behind)? [y/N] ",
          false)) {
        args.add("--include-events");
      }
    }
    if (Prompter.yes(
        out, "Rehearse with the vendor's validation first (changes nothing)? [Y/n] ", true)) {
      List<String> rehearsal = new ArrayList<>(args);
      rehearsal.add("--test");
      if (execute(rehearsal.toArray(String[]::new)) != ExitCodes.SUCCESS) {
        out.println("The rehearsal found problems; fix what it reports, then come back.");
        return;
      }
    }
    if (samedb) {
      // samedb migrates the schema in place and no export undoes that (ADR-0029)
      if (!Prompter.yes(out, "Database backed up with your database tools? [y/N] ", false)) {
        out.println(
            "Back up the repository database first: samedb changes it in place and jrsctl cannot"
                + " undo that.");
        return;
      }
      args.add("--db-backup-confirmed");
      // installation guide 10.1 pp.194-199 (issue #108): the vendor's password migration
      if (Prompter.yes(
          out,
          "Migrate stored passwords to the modern format after the upgrade (js-ant"
              + " migrate-passwords, 10.1 or later; only a database restore undoes it)? [y/N] ",
          false)) {
        args.add("--migrate-passwords");
      }
    } else {
      out.println(
          "jrsctl takes a full export of the repository first; upgrade rollback --restore-database"
              + " can rebuild the database from it.");
    }
    execute(args.toArray(String[]::new));
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

  private void docs() {
    List<EmbeddedDocs.Doc> docs = EmbeddedDocs.list();
    if (docs.isEmpty()) {
      out.println("No documentation is embedded in this build.");
      return;
    }
    out.println();
    for (int i = 0; i < docs.size(); i++) {
      out.println("  " + (i + 1) + ") " + docs.get(i).title() + " (" + docs.get(i).name() + ")");
    }
    Optional<String> pick = Prompter.line(out, "Document [1-" + docs.size() + "]: ");
    if (pick.isEmpty() || pick.get().isEmpty()) {
      return;
    }
    try {
      int n = Integer.parseInt(pick.get());
      if (n >= 1 && n <= docs.size()) {
        execute("docs", docs.get(n - 1).name());
        return;
      }
    } catch (NumberFormatException e) {
      // falls through to the hint
    }
    out.println("Please type a number from 1 to " + docs.size() + ".");
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

  /**
   * One of {@code allowed}, case-insensitively; Enter gives {@code fallback}; anything else is
   * asked again; empty only at end of input.
   */
  private Optional<String> choice(String prompt, String fallback, Set<String> allowed) {
    while (true) {
      Optional<String> answer = Prompter.line(out, prompt + ": ");
      if (answer.isEmpty()) {
        return Optional.empty();
      }
      String a = answer.get().toLowerCase(Locale.ROOT);
      if (a.isEmpty()) {
        return Optional.of(fallback);
      }
      if (allowed.contains(a)) {
        return Optional.of(a);
      }
      out.println("  please answer " + String.join(", ", allowed.stream().sorted().toList()));
    }
  }

  /** The path as the command will read it: a leading {@code ~} is the operator's home. */
  private static Path local(String typed) {
    return Path.of(UserPaths.expand(typed, Env.vars()));
  }

  private Optional<String> existingFile(String prompt) {
    while (true) {
      Optional<String> answer = text(prompt);
      if (answer.isEmpty() || Files.isRegularFile(local(answer.get()))) {
        return answer;
      }
      out.println("  no such file: " + answer.get() + " (press Enter to go back)");
    }
  }

  private Optional<String> existingDirectory(String prompt) {
    while (true) {
      Optional<String> answer = text(prompt);
      if (answer.isEmpty() || Files.isDirectory(local(answer.get()))) {
        return answer;
      }
      out.println("  no such directory: " + answer.get() + " (press Enter to go back)");
    }
  }

  /**
   * An optional path: the outer empty is end of input, the inner empty is Enter (keep the default);
   * a path that does not exist is asked again.
   */
  private Optional<Optional<String>> optionalExistingFile(String prompt) {
    while (true) {
      Optional<String> answer = Prompter.line(out, prompt + ": ");
      if (answer.isEmpty()) {
        return Optional.empty();
      }
      if (answer.get().isEmpty()) {
        return Optional.of(Optional.empty());
      }
      if (Files.isRegularFile(local(answer.get()))) {
        return Optional.of(answer);
      }
      out.println("  no such file: " + answer.get() + " (Enter to skip)");
    }
  }

  private Optional<Optional<String>> optionalExistingDirectory(String prompt) {
    while (true) {
      Optional<String> answer = Prompter.line(out, prompt + ": ");
      if (answer.isEmpty()) {
        return Optional.empty();
      }
      if (answer.get().isEmpty()) {
        return Optional.of(Optional.empty());
      }
      if (Files.isDirectory(local(answer.get()))) {
        return Optional.of(answer);
      }
      out.println("  no such directory: " + answer.get() + " (Enter to skip)");
    }
  }
}
