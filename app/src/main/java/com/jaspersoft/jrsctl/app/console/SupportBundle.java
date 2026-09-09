package com.jaspersoft.jrsctl.app.console;

import com.jaspersoft.jrsctl.core.json.Json;
import com.jaspersoft.jrsctl.core.redact.Redactor;
import com.jaspersoft.jrsctl.core.state.RunRecord;
import com.jaspersoft.jrsctl.core.state.StateStore;
import com.jaspersoft.jrsctl.core.state.Transition;
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
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * The zip behind {@code GET /api/runs/{id}/support-bundle} (spec §13.1): {@code run.json}, {@code
 * plan.json}, {@code transitions.jsonl}, {@code events.jsonl} (when the console kept one), {@code
 * server.json}, {@code doctor.json}, {@code config-redacted.yaml} and the last 2000 lines of {@code
 * logs/jrsctl.log}. Invariants: every byte passes through the {@link Redactor} on its way into the
 * archive, so a registered secret cannot appear in any encoding the redactor knows; the archive is
 * streamed entry by entry to the response and the log tail is kept in a bounded deque, so memory
 * stays flat whatever the log size; a missing optional input yields no entry rather than an error.
 */
final class SupportBundle {

  static final int LOG_TAIL_LINES = 2000;

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

  void write(RunRecord run, OutputStream target) throws IOException {
    StateStore store = services.stateStore().get();
    try (ZipOutputStream zip = new ZipOutputStream(target, StandardCharsets.UTF_8)) {
      text(zip, "run.json", Json.writePretty(views.runDetail(run)));
      run.planId()
          .flatMap(store::loadPlan)
          .ifPresent(
              p -> {
                try {
                  text(zip, "plan.json", p.planJson());
                } catch (IOException e) {
                  throw new java.io.UncheckedIOException(e);
                }
              });
      zip.putNextEntry(new ZipEntry("transitions.jsonl"));
      for (Transition t : store.transitions(run.runId())) {
        line(zip, Json.write(t));
      }
      zip.closeEntry();
      Path events = services.home().runDir(run.runId()).resolve("events.jsonl");
      if (Files.isRegularFile(events)) {
        zip.putNextEntry(new ZipEntry("events.jsonl"));
        try (BufferedReader reader = Files.newBufferedReader(events, StandardCharsets.UTF_8)) {
          String l;
          while ((l = reader.readLine()) != null) {
            line(zip, l);
          }
        }
        zip.closeEntry();
      }
      text(zip, "server.json", Json.writePretty(views.server()));
      text(zip, "doctor.json", Json.writePretty(ConsoleViews.doctor(doctor.current())));
      text(zip, "config-redacted.yaml", ConfigShow.render(services.config()));
      Path log = services.home().root().resolve("logs").resolve("jrsctl.log");
      if (Files.isRegularFile(log)) {
        zip.putNextEntry(new ZipEntry("logs/jrsctl.log"));
        for (String l : tail(log)) {
          line(zip, l);
        }
        zip.closeEntry();
      }
    } catch (java.io.UncheckedIOException e) {
      throw e.getCause();
    }
  }

  private static Deque<String> tail(Path log) throws IOException {
    Deque<String> lines = new ArrayDeque<>(LOG_TAIL_LINES);
    try (BufferedReader reader = Files.newBufferedReader(log, StandardCharsets.UTF_8)) {
      String l;
      while ((l = reader.readLine()) != null) {
        if (lines.size() == LOG_TAIL_LINES) {
          lines.removeFirst();
        }
        lines.addLast(l);
      }
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
