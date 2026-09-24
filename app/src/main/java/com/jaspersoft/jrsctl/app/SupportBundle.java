package com.jaspersoft.jrsctl.app;

import com.jaspersoft.jrsctl.core.config.Config;
import com.jaspersoft.jrsctl.core.engine.RunRecord;
import com.jaspersoft.jrsctl.core.engine.Transition;
import com.jaspersoft.jrsctl.core.json.Json;
import com.jaspersoft.jrsctl.core.platform.TomcatLayout;
import com.jaspersoft.jrsctl.core.redact.Redactor;
import com.jaspersoft.jrsctl.core.state.StateStore;
import com.jaspersoft.jrsctl.core.state.StoredPlan;
import com.jaspersoft.jrsctl.jrs.api.ServerIdentity;
import com.jaspersoft.jrsctl.jrs.vendor.BuildomaticLocator;
import com.jaspersoft.jrsctl.jrs.vendor.BuildomaticResolution;
import com.jaspersoft.jrsctl.ops.ConfigShow;
import com.jaspersoft.jrsctl.ops.Services;
import com.jaspersoft.jrsctl.ops.doctor.DoctorOperation;
import com.jaspersoft.jrsctl.ops.doctor.DoctorOptions;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * The zip behind {@code jrsctl runs support-bundle} (and, until ADR-0038 lands, {@code GET
 * /api/runs/{id}/support-bundle}): {@code run.json}, {@code plan.json}, {@code transitions.jsonl},
 * {@code events.jsonl} (when the console kept one), {@code server.json}, {@code doctor.json},
 * {@code config-redacted.yaml} and the tail of the JSON log. Invariants: every byte passes through
 * the {@link Redactor} on its way into the archive, so a registered secret cannot appear in any
 * encoding the redactor knows; everything that can fail, which is the live doctor run and the
 * server probe behind it, happens in {@link #prepare} before a single response header is committed,
 * so a failure is an error document and never a truncated zip delivered as 200 (review 4.8); the
 * archive is then streamed entry by entry and both tails are kept in bounded deques, so memory
 * stays flat whatever the log or event stream size; a missing optional input yields no entry rather
 * than an error; the log is the file the running process actually writes, named by {@link
 * LogFile#PROPERTY}, not a guess at its location.
 */
public final class SupportBundle {

  static final int LOG_TAIL_LINES = 2000;
  static final int EVENT_TAIL_LINES = 20_000;

  private final Services services;
  private final Redactor redactor;

  public SupportBundle(Services services) {
    this.services = Objects.requireNonNull(services, "services");
    this.redactor = services.redactor();
  }

  /**
   * Everything that has to be computed before the first byte is written: the live doctor report and
   * the server probe behind it, plus the documents that read the state store (review 4.8).
   */
  public record Prepared(
      RunRecord run,
      String runJson,
      Optional<String> planJson,
      String serverJson,
      String doctorJson,
      String configYaml) {}

  /** Runs everything that can fail. Throws before any byte of the archive is written. */
  public Prepared prepare(RunRecord run) {
    StateStore store = services.stateStore().get();
    Optional<StoredPlan> plan = run.planId().flatMap(store::loadPlan);
    Map<String, Object> runDoc =
        RunsCommand.showTree(
            run,
            plan.map(p -> RunsCommand.parse(p.planJson())),
            store.transitions(run.runId()),
            store.snapshots(run.runId()),
            services.clock());
    return new Prepared(
        run,
        Json.writePretty(runDoc),
        plan.map(StoredPlan::planJson),
        Json.writePretty(serverDocument()),
        JsonOut.write(new DoctorOperation(services).run(DoctorOptions.DEFAULT)),
        ConfigShow.render(services.config()));
  }

  /**
   * The server as the adapter sees it, or {@code reachable:false} with the configured base URL when
   * it cannot be reached: a bundle is wanted most when the server is broken, so this never throws.
   */
  private Map<String, Object> serverDocument() {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("baseUrl", services.config().server().baseUrl().map(Object::toString).orElse(""));
    try {
      ServerIdentity identity = services.adapter().get().identity();
      m.put("reachable", true);
      m.put("identity", identity);
    } catch (RuntimeException e) {
      m.put("reachable", false);
      m.put("error", redactor.redact(String.valueOf(e.getMessage())));
    }
    return m;
  }

  public void write(RunRecord run, OutputStream target) throws IOException {
    write(prepare(run), target);
  }

  public void write(Prepared prepared, OutputStream target) throws IOException {
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
      // review §3.4 (issue #111): the vendor's own troubleshooting files, tail-capped and redacted
      for (VendorLogs.Source source : vendorSources()) {
        zip.putNextEntry(new ZipEntry(source.entry()));
        try {
          VendorLogs.tail(
              source.file(),
              VendorLogs.TAIL_BYTES,
              l -> {
                try {
                  line(zip, source.masterProperties() ? VendorLogs.blankPassword(l) : l);
                } catch (IOException e) {
                  throw new UncheckedIOException(e);
                }
              });
        } catch (IOException | UncheckedIOException e) {
          // a vendor file that vanishes or cannot be read mid-way is a note, not a broken zip
          line(zip, "... " + source.file() + " could not be read: " + e.getMessage());
        }
        zip.closeEntry();
      }
    }
  }

  /** The vendor files this installation has, from the configuration and the buildomatic lookup. */
  List<VendorLogs.Source> vendorSources() {
    Config.Server server = services.config().server();
    Optional<TomcatLayout> layout =
        server.installDir().flatMap(d -> services.platform().detectTomcat(d));
    Optional<Path> tomcatDir = server.tomcatDir().or(() -> layout.map(TomcatLayout::tomcatDir));
    Optional<Path> webappDir =
        tomcatDir.flatMap(
            t ->
                server
                    .webappName()
                    .map(n -> t.resolve("webapps").resolve(n.yamlValue()))
                    .or(() -> layout.map(TomcatLayout::webappDir)));
    Optional<Path> buildomatic =
        switch (new BuildomaticLocator(services.platform()).resolve(services.config())) {
          case BuildomaticResolution.Found found -> Optional.of(found.buildomatic().dir());
          case BuildomaticResolution.NotFound missing -> Optional.empty();
        };
    return VendorLogs.locate(server.installDir(), tomcatDir, webappDir, buildomatic);
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
