package com.jaspersoft.jrsctl.app.console;

import com.jaspersoft.jrsctl.app.LogFile;
import com.jaspersoft.jrsctl.core.engine.RunRecord;
import com.jaspersoft.jrsctl.core.engine.Transition;
import com.jaspersoft.jrsctl.core.json.Json;
import com.jaspersoft.jrsctl.core.redact.Redactor;
import com.jaspersoft.jrsctl.core.state.StateStore;
import com.jaspersoft.jrsctl.ops.ConfigShow;
import com.jaspersoft.jrsctl.ops.Services;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * The zip behind {@code GET /api/runs/{id}/support-bundle} (spec §13.1): {@code run.json}, {@code
 * plan.json}, {@code transitions.jsonl}, {@code events.jsonl} (when the console kept one), {@code
 * server.json}, {@code doctor.json}, {@code config-redacted.yaml} and the tail of the JSON log.
 * Invariants: every byte passes through the {@link Redactor} on its way into the archive, so a
 * registered secret cannot appear in any encoding the redactor knows; everything that can fail,
 * which is the live doctor run and the server probe behind it, happens in {@link #prepare} before a
 * single response header is committed, so a failure is an error document and never a truncated zip
 * delivered as 200 (review 4.8); the archive is then streamed entry by entry and both tails are
 * kept in bounded deques, so memory stays flat whatever the log or event stream size; a missing
 * optional input yields no entry rather than an error; the log is the file the running process
 * actually writes, named by {@link LogFile#PROPERTY}, not a guess at its location.
 */
final class SupportBundle {

  static final int LOG_TAIL_LINES = 2000;
  static final int EVENT_TAIL_LINES = 20_000;

  private final Services services;
  private final ConsoleViews views;
  private final DoctorCache doctor;
  private final Redactor redactor;

  SupportBundle(Services services, ConsoleViews views, DoctorCache doctor) {
    this.services = Objects.requireNonNull(services, "services");
    this.views = Objects.requireNonNull(views, "views");
    this.doctor = Objects.requireNonNull(doctor, "doctor");
    this.redactor = services.redactor();
  }

  /**
   * Everything that has to be computed before the response is committed: the live doctor report and
   * the server probe behind it, plus the documents that read the state store (review 4.8).
   */
  record Prepared(
      RunRecord run,
      String runJson,
      Optional<String> planJson,
      String serverJson,
      String doctorJson,
      String configYaml) {}

  /** Runs everything that can fail. Throws before any byte of the response is written. */
  Prepared prepare(RunRecord run) {
    StateStore store = services.stateStore().get();
    return new Prepared(
        run,
        Json.writePretty(views.runDetail(run)),
        run.planId().flatMap(store::loadPlan).map(p -> p.planJson()),
        Json.writePretty(views.server()),
        Json.writePretty(ConsoleViews.doctor(doctor.current())),
        ConfigShow.render(services.config()));
  }

  void write(RunRecord run, OutputStream target) throws IOException {
    write(prepare(run), target);
  }

  void write(Prepared prepared, OutputStream target) throws IOException {
    RunRecord run = prepared.run();
    StateStore store = services.stateStore().get();
    try (ZipOutputStream zip = new ZipOutputStream(target, StandardCharsets.UTF_8)) {
      text(zip, "run.json", prepared.runJson());
      if (prepared.planJson().isPresent()) {
        text(zip, "plan.json", prepared.planJson().get());
      }
      zip.putNextEntry(new ZipEntry("transitions.jsonl"));
      for (Transition t : store.transitions(run.runId())) {
        line(zip, Json.write(t));
      }
      zip.closeEntry();
      Path events = services.home().runDir(run.runId()).resolve("events.jsonl");
      if (Files.isRegularFile(events)) {
        zip.putNextEntry(new ZipEntry("events.jsonl"));
        for (String l : tail(events, EVENT_TAIL_LINES)) {
          line(zip, l);
        }
        zip.closeEntry();
      }
      text(zip, "server.json", prepared.serverJson());
      text(zip, "doctor.json", prepared.doctorJson());
      text(zip, "config-redacted.yaml", prepared.configYaml());
      Path log = logFile();
      if (Files.isRegularFile(log)) {
        zip.putNextEntry(new ZipEntry("logs/" + log.getFileName()));
        for (String l : tail(log, LOG_TAIL_LINES)) {
          line(zip, l);
        }
        zip.closeEntry();
      }
    }
  }

  /**
   * The log this process is writing (review 4.8). {@code Main} always sets the property, from
   * {@code --home}, {@code JRSCTL_HOME} or the platform default; the home-relative path is only a
   * fallback for a console started in-process by a test.
   */
  Path logFile() {
    Path underHome = services.home().root().resolve("logs").resolve("jrsctl.log");
    String configured = System.getProperty(LogFile.PROPERTY);
    if (configured == null || configured.isBlank()) {
      return underHome;
    }
    // The property is what Main set from --home, so the two are normally the same file. When the
    // property names a file that is not there, the home's log is the better answer: a bundle with
    // no log in it is worse than a bundle with the log the console has been writing.
    Path named = Path.of(configured);
    return Files.isRegularFile(named) ? named : underHome;
  }

  /** The last {@code limit} lines of a text file, read with flat memory. */
  private static Deque<String> tail(Path file, int limit) throws IOException {
    Deque<String> lines = new ArrayDeque<>(limit);
    boolean truncated = false;
    try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
      String l;
      while ((l = reader.readLine()) != null) {
        if (lines.size() == limit) {
          lines.removeFirst();
          truncated = true;
        }
        lines.addLast(l);
      }
    }
    if (truncated) {
      lines.addFirst("... earlier lines omitted; this is the last " + limit + " of " + file);
    }
    return lines;
  }

  private void text(ZipOutputStream zip, String name, String content) throws IOException {
    zip.putNextEntry(new ZipEntry(name));
    zip.write(redactor.redact(content).getBytes(StandardCharsets.UTF_8));
    zip.closeEntry();
  }

  private void line(ZipOutputStream zip, String content) throws IOException {
    zip.write(redactor.redact(content).getBytes(StandardCharsets.UTF_8));
    zip.write('\n');
  }
}
