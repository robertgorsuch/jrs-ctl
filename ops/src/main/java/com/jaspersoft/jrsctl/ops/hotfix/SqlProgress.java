package com.jaspersoft.jrsctl.ops.hotfix;

import com.jaspersoft.jrsctl.core.engine.Context;
import com.jaspersoft.jrsctl.core.platform.Durability;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Which of a hotfix's SQL scripts were handed to the database, journalled in the run directory so a
 * compensation knows what there is to undo (spec §8.2 step 9).
 *
 * <p>Without this the compensation runs every rollback script the manifest lists, in reverse, and a
 * run that failed on its first script therefore also undoes the second and third, which never ran.
 * Those rollback scripts are written to reverse changes that are not there: at best they are
 * no-operations, at worst they drop a column an earlier hotfix added, and nothing in the manifest
 * makes them safe to run out of turn.
 *
 * <p>Invariants: a script is recorded as started before it is sent, not after, so a process that
 * dies mid-statement still leaves a record that something may have happened; the file is appended
 * to and forced, so the record survives a power cut; the order is the order the scripts ran in, and
 * {@link #started()} preserves it; a script recorded twice by a re-execution appears once. {@link
 * #none()} is for the compensation's own run, which is undone by re-running it rather than by a
 * journal.
 */
final class SqlProgress {

  static final String SUFFIX = ".sql-progress";
  private static final String STARTED = "started ";

  private final Optional<Path> file;

  private SqlProgress(Optional<Path> file) {
    this.file = file;
  }

  /** The journal of {@code stepId} in this run's directory. */
  static SqlProgress of(Context ctx, String stepId) {
    return new SqlProgress(Optional.of(ctx.home().runDir(ctx.runId()).resolve(stepId + SUFFIX)));
  }

  /** A journal that records nothing and reports nothing started. */
  static SqlProgress none() {
    return new SqlProgress(Optional.empty());
  }

  /** Records that {@code script} is about to be sent to the database. */
  void start(String script) throws IOException {
    if (file.isEmpty()) {
      return;
    }
    if (started().contains(script)) {
      return;
    }
    Path path = file.get();
    Files.createDirectories(path.getParent());
    Files.writeString(
        path,
        STARTED + script + System.lineSeparator(),
        StandardCharsets.UTF_8,
        StandardOpenOption.CREATE,
        StandardOpenOption.APPEND);
    Durability.sync(path);
  }

  /** The scripts recorded as started, in the order they were, each once. */
  List<String> started() throws IOException {
    if (file.isEmpty() || !Files.isRegularFile(file.get())) {
      return List.of();
    }
    Set<String> scripts = new LinkedHashSet<>();
    for (String line : Files.readAllLines(file.get(), StandardCharsets.UTF_8)) {
      String text = line.strip();
      if (text.startsWith(STARTED)) {
        scripts.add(text.substring(STARTED.length()));
      }
    }
    return List.copyOf(new ArrayList<>(scripts));
  }
}
