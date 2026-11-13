package com.jaspersoft.jrsctl.ops.repository;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jaspersoft.jrsctl.core.engine.CheckResult;
import com.jaspersoft.jrsctl.core.engine.Context;
import com.jaspersoft.jrsctl.core.engine.Step;
import com.jaspersoft.jrsctl.core.engine.StepResult;
import com.jaspersoft.jrsctl.core.event.Event;
import com.jaspersoft.jrsctl.core.event.EventSink;
import com.jaspersoft.jrsctl.core.json.Json;
import com.jaspersoft.jrsctl.core.secrets.SecretException;
import com.jaspersoft.jrsctl.core.state.AuditActor;
import com.jaspersoft.jrsctl.jrs.strategy.ExportIndex;
import com.jaspersoft.jrsctl.jrs.vendor.Buildomatic;
import com.jaspersoft.jrsctl.jrs.vendor.IeClasspathJar;
import com.jaspersoft.jrsctl.jrs.vendor.VendorRun;
import com.jaspersoft.jrsctl.jrs.vendor.VendorTools;
import com.jaspersoft.jrsctl.ops.ReportItem;
import com.jaspersoft.jrsctl.ops.Steps;
import com.jaspersoft.jrsctl.ops.db.DatabaseCatalog;
import com.jaspersoft.jrsctl.ops.db.JdbcConnector;
import com.jaspersoft.jrsctl.ops.db.JdbcException;
import com.jaspersoft.jrsctl.ops.doctor.DoctorOperation;
import com.jaspersoft.jrsctl.ops.doctor.DoctorOptions;
import com.jaspersoft.jrsctl.ops.doctor.DoctorReport;
import com.jaspersoft.jrsctl.ops.doctor.JavaVersion;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The steps of {@code repository load} (spec §10.5, ADR-0052). Invariants: the four read-only
 * preflight steps mutate nothing and report every refusal from {@code precheck}, so the run ends
 * with exit 2 and nothing created; {@code render-config}, the last preflight step, is the first to
 * touch disk and only the buildomatic's own {@code build_conf/default} (issue #279 item 6), undone
 * by {@code js-ant clean-config}; the four schema steps are idempotent through a done marker in the
 * run directory and share one compensation, {@link SchemaDrop}; {@code create-js-db} records the
 * schema's name before the vendor target is launched; the vendor tools run with {@code
 * vendor.javaHome} and their output is streamed as redacted log events; {@code record-load} is
 * additive and its compensation removes only its own file.
 */
final class LoadSteps {

  static final String DOCTOR = "doctor";
  static final String VERIFY_BUILDOMATIC = "verify-buildomatic";
  static final String VERIFY_TARGET_SCHEMA = "verify-target-schema";
  static final String ADOPT_EXPORT = "adopt-export";
  static final String RENDER_CONFIG = "render-config";
  static final String CLEAN_CONFIG = "clean-config";
  static final String GEN_CONFIG = "gen-config";
  static final String RENDERED_DIR = "rendered-config";
  static final String CREATE_JS_DB = "create-js-db";
  static final String IMPORT_EXPORT = "import-export";
  static final String RECORD_LOAD = "record-load";
  static final String RECORD_FILE = "repository-load.json";
  static final String AUDIT_COMPLETED = "repository.load.completed";

  /**
   * Doctor items whose FAIL stops the run. The server items are left out on purpose: the server
   * that will use the schema is usually not deployed yet when the schema is loaded, so {@code
   * vendor-java}, which doctor judges against that server's version, is judged here by {@code
   * verify-buildomatic} instead; and {@code database}, which doctor asks through the configured
   * driver, is asked precisely by {@code verify-target-schema}. {@code runs} and {@code lock} are
   * in: the doctor leaves out this run's own row and lock, so a FAIL there is another run, and a
   * schema is not created beside a half-finished run.
   */
  static final Set<String> REQUIRED_NOT_FAILING =
      Set.of("runtime", "config", "secrets", "vendor", "disk", "state", "runs", "lock");

  private LoadSteps() {}

  // ---------------------------------------------------------------- bases

  /** Read-only step skeleton: nothing to compensate. */
  abstract static class ReadOnly implements Step {
    final LoadRuntime rt;
    final LoadInput in;

    ReadOnly(LoadRuntime rt, LoadInput in) {
      this.rt = Objects.requireNonNull(rt, "rt");
      this.in = Objects.requireNonNull(in, "in");
    }

    @Override
    public String phase() {
      return LoadPhases.PREFLIGHT;
    }

    @Override
    public boolean mutating() {
      return false;
    }

    @Override
    public StepResult compensate(Context ctx, EventSink out) {
      return StepResult.ok();
    }

    void info(Context ctx, EventSink out, String message) {
      Steps.info(rt.services(), ctx, out, this, message);
    }
  }

  /** A step that runs a vendor tool once per run and is undone by dropping the schema. */
  abstract static class Vendor implements Step {
    final LoadRuntime rt;
    final LoadInput in;

    Vendor(LoadRuntime rt, LoadInput in) {
      this.rt = Objects.requireNonNull(rt, "rt");
      this.in = Objects.requireNonNull(in, "in");
    }

    Path done(Context ctx) {
      return ctx.home().runDir(ctx.runId()).resolve(id() + ".done");
    }

    boolean isDone(Context ctx) {
      return Files.isRegularFile(done(ctx));
    }

    StepResult markDone(Context ctx, EventSink out, String message) {
      try {
        Files.createDirectories(done(ctx).getParent());
        Files.writeString(done(ctx), "done", StandardCharsets.UTF_8);
      } catch (IOException e) {
        // a resume would run the vendor target again, which the vendor's own scripts do not
        // tolerate for create-js-db; say so rather than report success
        return Steps.recoverable(
            "cannot write " + done(ctx) + ": " + e.getMessage(),
            "free the jrsctl home's disk or fix its permissions, then run jrsctl runs recover");
      }
      Steps.info(rt.services(), ctx, out, this, message);
      return StepResult.ok();
    }

    /** The checks every vendor step shares: the tools and the Java they run with. */
    CheckResult common() {
      if (in.buildomatic().scriptFor(Buildomatic.ANT_SCRIPT).isEmpty()) {
        return CheckResult.fail(
            "no js-ant in " + in.buildomatic().dir(), "unpack the full distribution's buildomatic");
      }
      if (rt.config().vendor().javaHome().isEmpty()) {
        return CheckResult.fail(
            "vendor.javaHome is not set; the vendor tools need a JDK",
            "set vendor.javaHome in config.yaml (jrsctl config set vendor.javaHome <dir>)");
      }
      return CheckResult.pass();
    }

    @Override
    public CheckResult precheck(Context ctx) {
      return common();
    }

    @Override
    public StepResult compensate(Context ctx, EventSink out) {
      return SchemaDrop.compensate(rt, in, ctx, out, this);
    }

    /**
     * Every mutating phase is undone by the same schema drop, so a phase-scoped rollback of, say,
     * {@code initialise} dropped the schema {@code create} made while reporting "rolled back to
     * phase initialise" (#319 item 5). Rolling back the whole run says what happened: the schema is
     * dropped and {@code render-config}'s rendered files, which hold the database password in
     * clear, are cleaned too.
     */
    @Override
    public boolean rollbackAllOnFailure() {
      return true;
    }

    /** Runs one js-ant target; empty when it succeeded, else the failure to return. */
    Optional<StepResult> ant(Context ctx, EventSink out, String target) {
      VendorRun run =
          rt.tools()
              .ant(
                  in.buildomatic(),
                  target,
                  List.of(),
                  rt.config().vendor().javaHome(),
                  out,
                  Steps.scope(ctx, this));
      return switch (run) {
        case VendorRun.Completed c ->
            c.ok()
                ? Optional.empty()
                : Optional.of(
                    Steps.recoverable(
                        "js-ant "
                            + target
                            + " "
                            + c.summary()
                            + ": "
                            + String.join(" | ", c.tail()),
                        "read the buildomatic log under "
                            + in.buildomatic().dir()
                            + "; the schema this run created is dropped by the rollback, so the"
                            + " run can be repeated once the cause is fixed"));
        case VendorRun.TimedOut t ->
            Optional.of(
                Steps.recoverable(
                    "js-ant "
                        + target
                        + " did not finish within "
                        + t.timeout().toMinutes()
                        + " minutes",
                    "check for a hung buildomatic process, then run again"));
        case VendorRun.NotStarted n -> Optional.of(Steps.recoverable(n.reason(), n.remediation()));
      };
    }
  }

  // ---------------------------------------------------------------- preflight

  /** The doctor, with the server items allowed to fail: the target server may not exist yet. */
  static final class Doctor extends ReadOnly {
    private Optional<DoctorReport> last = Optional.empty();

    Doctor(LoadRuntime rt, LoadInput in) {
      super(rt, in);
    }

    @Override
    public String id() {
      return DOCTOR;
    }

    @Override
    public String title() {
      return "run doctor against this tools-only configuration";
    }

    @Override
    public String detail() {
      return "the tool, the configuration, its secrets, the vendor tools, the disk, the state"
          + " store, other pending runs and the run lock must pass; the server items may fail, since the server that will use the"
          + " schema is usually not deployed yet, and the Java and the database server are judged"
          + " by the next two steps";
    }

    @Override
    public CheckResult precheck(Context ctx) {
      DoctorReport report;
      try {
        report = new DoctorOperation(rt.services()).run(DoctorOptions.insideRun(ctx.runId()));
      } catch (RuntimeException e) {
        return CheckResult.fail(
            "doctor could not run: " + Steps.describe(e), "run jrsctl doctor and fix it first");
      }
      last = Optional.of(report);
      List<String> failing = new ArrayList<>();
      for (ReportItem item : report.items()) {
        if (item.status() == ReportItem.Status.FAIL && REQUIRED_NOT_FAILING.contains(item.name())) {
          failing.add(item.name() + ": " + item.detail());
        }
      }
      if (!failing.isEmpty()) {
        return CheckResult.fail(
            "doctor reports " + failing.size() + " failing check(s): " + String.join("; ", failing),
            "run jrsctl doctor and fix every FAIL above before loading");
      }
      return CheckResult.pass();
    }

    @Override
    public StepResult execute(Context ctx, EventSink out) {
      DoctorReport report =
          last.orElseGet(
              () -> new DoctorOperation(rt.services()).run(DoctorOptions.insideRun(ctx.runId())));
      for (ReportItem item : report.items()) {
        Event.Log.Level level =
            switch (item.status()) {
              case PASS, SKIP -> Event.Log.Level.INFO;
              case WARN -> Event.Log.Level.WARN;
              case FAIL -> Event.Log.Level.WARN;
            };
        Steps.emit(
            rt.services(),
            ctx,
            out,
            this,
            level,
            "doctor " + item.name() + " " + item.status() + ": " + item.detail());
      }
      return StepResult.ok();
    }
  }

  /** The buildomatic tree ships the scripts, an Ant and an edition, and its Java runs. */
  static final class VerifyBuildomatic extends ReadOnly {
    VerifyBuildomatic(LoadRuntime rt, LoadInput in) {
      super(rt, in);
    }

    @Override
    public String id() {
      return VERIFY_BUILDOMATIC;
    }

    @Override
    public String title() {
      return "verify the buildomatic at " + in.buildomatic().dir();
    }

    @Override
    public String detail() {
      return "js-ant, js-import and the bundled Ant are present; conf_source/ie"
          + (in.edition().equals(LoadInput.PRO) ? "Pro" : "Ce")
          + " names the edition ("
          + in.edition()
          + "); vendor.javaHome runs";
    }

    @Override
    public CheckResult precheck(Context ctx) {
      Buildomatic b = in.buildomatic();
      List<String> missing = b.missingScripts();
      if (!missing.isEmpty()) {
        return CheckResult.fail(
            "missing in " + b.dir() + ": " + String.join(", ", missing),
            "unpack the full distribution; repository load needs js-ant and js-import");
      }
      if (rt.locator().ant(b, System.getenv()).isEmpty()) {
        return CheckResult.fail(
            "no apache-ant next to " + b.dir() + " and no ant on the PATH",
            "unpack the distribution's apache-ant beside buildomatic, or install Ant on the PATH");
      }
      if (LoadInput.edition(b).isEmpty()) {
        return CheckResult.fail(
            b.dir() + " has neither conf_source/iePro nor conf_source/ieCe",
            "point server.buildomaticDir at the buildomatic directory of an unpacked distribution");
      }
      // #315: import-minimal runs with iecp.jar, which a hotfix that swapped lib jars leaves stale
      Optional<IeClasspathJar.Stale> stale = IeClasspathJar.stale(b.dir());
      if (stale.isPresent()) {
        return CheckResult.fail(stale.get().detail(), stale.get().remediation());
      }
      Optional<Path> javaHome = rt.config().vendor().javaHome();
      if (javaHome.isEmpty()) {
        return CheckResult.fail(
            "vendor.javaHome is not set; the vendor tools need a JDK",
            "set vendor.javaHome to the JDK the installation guide names for this version");
      }
      JavaVersion.Probe probe =
          JavaVersion.probe(rt.services().platform().processes(), javaHome.get());
      if (probe.feature().isEmpty()) {
        return CheckResult.fail(
            probe.detail(), "point vendor.javaHome at a working JDK for this version");
      }
      return CheckResult.pass();
    }

    @Override
    public StepResult execute(Context ctx, EventSink out) {
      info(
          ctx,
          out,
          "buildomatic "
              + in.buildomatic().dir()
              + " verified: edition "
              + in.edition()
              + ", Java "
              + rt.config().vendor().javaHome().map(Path::toString).orElse("?"));
      return StepResult.ok();
    }
  }

  /** The refusals of spec §10.5 that keep the live database out of reach. */
  static final class VerifyTargetSchema extends ReadOnly {
    VerifyTargetSchema(LoadRuntime rt, LoadInput in) {
      super(rt, in);
    }

    @Override
    public String id() {
      return VERIFY_TARGET_SCHEMA;
    }

    @Override
    public String title() {
      return "verify that " + in.schema() + " is the schema buildomatic names and does not exist";
    }

    @Override
    public String detail() {
      return LoadInput.JS_DB_NAME
          + "="
          + in.schema()
          + " and "
          + LoadInput.APP_SERVER_TYPE
          + "="
          + LoadInput.SKIP_APP_SERVER_CHECK
          + " in "
          + in.buildomatic().dir().resolve(Buildomatic.MASTER_PROPERTIES)
          + "; the server's catalogue at "
          + in.catalog().adminUrl()
          + " has no "
          + in.schema();
    }

    @Override
    public CheckResult precheck(Context ctx) {
      Path master = in.buildomatic().dir().resolve(Buildomatic.MASTER_PROPERTIES);
      // re-read now: the plan may be hours old
      Optional<Buildomatic> current = rt.locator().at(in.buildomatic().dir());
      Optional<String> named =
          current
              .flatMap(b -> Optional.ofNullable(b.masterProperties().get(LoadInput.JS_DB_NAME)))
              .map(String::strip);
      if (named.isEmpty() || !named.get().equals(in.schema())) {
        return CheckResult.fail(
            master
                + " names "
                + named
                    .map(n -> LoadInput.JS_DB_NAME + "=" + n)
                    .orElse("no " + LoadInput.JS_DB_NAME)
                + ", not --schema "
                + in.schema()
                + "; repository load never edits that file",
            "set "
                + LoadInput.JS_DB_NAME
                + "="
                + in.schema()
                + " in "
                + master
                + " (and the database credentials the vendor targets need), or pass --schema"
                + " with the name the file has");
      }
      Optional<String> appServer =
          current
              .flatMap(
                  b -> Optional.ofNullable(b.masterProperties().get(LoadInput.APP_SERVER_TYPE)))
              .map(String::strip);
      if (appServer.isEmpty() || !appServer.get().equals(LoadInput.SKIP_APP_SERVER_CHECK)) {
        return CheckResult.fail(
            master
                + " has "
                + LoadInput.APP_SERVER_TYPE
                + "="
                + appServer.orElse("(unset)")
                + "; this buildomatic deploys no webapp, so the vendor targets must skip the"
                + " application server",
            "set "
                + LoadInput.APP_SERVER_TYPE
                + "="
                + LoadInput.SKIP_APP_SERVER_CHECK
                + " in "
                + master);
      }
      try (JdbcConnector.Session session =
          in.jdbc().withUrl(in.catalog().adminUrl()).open(rt.jdbc(), rt.services().secrets())) {
        if (session.queryHasRow(in.catalog().existsSql(in.schema()))) {
          return CheckResult.fail(
              "schema "
                  + in.schema()
                  + " already exists on "
                  + in.catalog().adminUrl()
                  + "; repository load creates a new schema and never drops an existing one",
              "choose another name, or have the DBA drop "
                  + in.schema()
                  + " by name first ("
                  + in.catalog().dropSql(in.schema())
                  + ") if it is an abandoned attempt and nothing uses it");
        }
      } catch (JdbcException | SecretException | IllegalArgumentException e) {
        return CheckResult.fail(
            "cannot ask "
                + in.catalog().adminUrl()
                + " whether "
                + in.schema()
                + " exists: "
                + e.getMessage(),
            "check database.url, database.username and database.passwordRef against the target"
                + " database server, and the JDBC driver directory");
      }
      return CheckResult.pass();
    }

    @Override
    public StepResult execute(Context ctx, EventSink out) {
      info(
          ctx,
          out,
          "schema "
              + in.schema()
              + " is what buildomatic names and does not exist yet on "
              + in.catalog().adminUrl());
      return StepResult.ok();
    }
  }

  /** The export exists, is an export, and is the file the plan was made for. */
  static final class AdoptExport extends ReadOnly {
    AdoptExport(LoadRuntime rt, LoadInput in) {
      super(rt, in);
    }

    @Override
    public String id() {
      return ADOPT_EXPORT;
    }

    @Override
    public String title() {
      return "adopt the export " + in.export().getFileName();
    }

    @Override
    public String detail() {
      return in.export()
          + " (sha256 "
          + in.exportSha256()
          + "), "
          + in.sidecar()
              .map(s -> "exported from " + s.serverIdentity() + " version " + s.serverVersion())
              .orElse("no sidecar beside it")
          + in.keyAlias().map(a -> ", key alias " + a).orElse("");
    }

    @Override
    public CheckResult precheck(Context ctx) {
      if (!Files.isRegularFile(in.export())) {
        return CheckResult.fail(
            in.export() + " does not exist", "put the export back or plan again with --from");
      }
      String sha;
      try {
        sha = rt.files().sha256(in.export());
      } catch (IOException e) {
        return CheckResult.fail(
            "cannot read " + in.export() + ": " + e.getMessage(),
            "check the file and its permissions");
      }
      if (!sha.equals(in.exportSha256())) {
        return CheckResult.fail(
            in.export() + " changed since the plan was made (sha256 " + sha + ")",
            "plan again so the run describes the file it will load");
      }
      try {
        ExportIndex.read(in.export());
      } catch (IOException | IllegalArgumentException e) {
        return CheckResult.fail(
            in.export() + " is not a JasperReports Server export: " + e.getMessage(),
            "pass a js-export --everything archive or a jrsctl export --full-server archive");
      }
      return CheckResult.pass();
    }

    @Override
    public StepResult execute(Context ctx, EventSink out) {
      info(ctx, out, "export adopted: " + detail());
      return StepResult.ok();
    }
  }

  /**
   * {@code js-ant clean-config} then {@code js-ant gen-config}: the buildomatic renders {@code
   * default_master.properties} into {@code build_conf/default}, the files every later vendor target
   * reads. The rendered {@code metadata.jdbc.url} must name {@code --schema} on the database server
   * the plan was made for; otherwise the step fails and its compensation cleans what it rendered,
   * so nothing is created against a connection the operator has not seen. The rendered properties
   * are copied, passwords masked, to {@code snapshots/<runId>/rendered-config} for the record.
   */
  static final class RenderConfig extends Vendor {
    RenderConfig(LoadRuntime rt, LoadInput in) {
      super(rt, in);
    }

    @Override
    public String id() {
      return RENDER_CONFIG;
    }

    @Override
    public String phase() {
      return LoadPhases.PREFLIGHT;
    }

    @Override
    public String title() {
      return "render the buildomatic's configuration (js-ant "
          + CLEAN_CONFIG
          + ", "
          + GEN_CONFIG
          + ")";
    }

    @Override
    public String detail() {
      return "js-ant "
          + CLEAN_CONFIG
          + " then "
          + GEN_CONFIG
          + " renders "
          + Buildomatic.MASTER_PROPERTIES
          + " into "
          + in.renderedDir()
          + "; "
          + RenderedConfig.JDBC_URL_KEY
          + " in "
          + RenderedConfig.JDBC_PROPERTIES
          + " must name "
          + in.schema()
          + " on the server of "
          + in.catalog().adminUrl()
          + "; the rendered properties are copied, passwords masked, to snapshots/<runId>/"
          + RENDERED_DIR;
    }

    @Override
    public StepResult execute(Context ctx, EventSink out) {
      if (isDone(ctx)) {
        Steps.info(
            rt.services(), ctx, out, this, "configuration already rendered in this run; skipping");
        return StepResult.ok();
      }
      Optional<StepResult> failure = ant(ctx, out, CLEAN_CONFIG);
      if (failure.isPresent()) {
        return failure.get();
      }
      failure = ant(ctx, out, GEN_CONFIG);
      if (failure.isPresent()) {
        return failure.get();
      }
      Path jdbcFile = in.renderedDir().resolve(RenderedConfig.JDBC_PROPERTIES);
      if (!Files.isRegularFile(jdbcFile)) {
        return Steps.recoverable(
            "js-ant " + GEN_CONFIG + " rendered no " + jdbcFile,
            "run js-ant " + GEN_CONFIG + " in " + in.buildomatic().dir() + " and read its output");
      }
      Optional<String> url;
      try {
        url = RenderedConfig.property(jdbcFile, RenderedConfig.JDBC_URL_KEY);
      } catch (IOException e) {
        return Steps.recoverable(
            "cannot read " + jdbcFile + ": " + e.getMessage(),
            "check the file and its permissions");
      }
      if (url.isEmpty()) {
        return Steps.recoverable(
            jdbcFile + " has no " + RenderedConfig.JDBC_URL_KEY,
            "check dbType, dbHost, dbPort and js.dbName in "
                + in.buildomatic().dir().resolve(Buildomatic.MASTER_PROPERTIES));
      }
      Optional<DatabaseCatalog> rendered;
      try {
        rendered = DatabaseCatalog.of(in.jdbc().withUrl(url.get()));
      } catch (IllegalArgumentException e) {
        rendered = Optional.empty();
      }
      if (rendered.isEmpty() || !rendered.get().schemaName().equals(in.schema())) {
        return Steps.recoverable(
            "rendered "
                + RenderedConfig.JDBC_URL_KEY
                + "="
                + url.get()
                + " names "
                + rendered.map(r -> "database " + r.schemaName()).orElse("no database")
                + ", not --schema "
                + in.schema(),
            "fix js.dbName (and dbType) in "
                + in.buildomatic().dir().resolve(Buildomatic.MASTER_PROPERTIES)
                + ", then run again");
      }
      if (!rendered.get().sameServerAs(in.catalog())) {
        return Steps.recoverable(
            "rendered "
                + RenderedConfig.JDBC_URL_KEY
                + "="
                + url.get()
                + " names the server of "
                + rendered.get().adminUrl()
                + "; the plan was made for "
                + in.catalog().adminUrl(),
            "make dbHost and dbPort in "
                + in.buildomatic().dir().resolve(Buildomatic.MASTER_PROPERTIES)
                + " and database.url in config.yaml agree, then plan again");
      }
      List<String> copied;
      try {
        copied =
            RenderedConfig.copy(
                in.renderedDir(), RenderedConfig.copyDir(ctx), rt.services().redactor());
      } catch (IOException e) {
        return Steps.recoverable(
            "cannot copy the rendered configuration to "
                + RenderedConfig.copyDir(ctx)
                + ": "
                + e.getMessage(),
            "fix the jrsctl home's disk or permissions; nothing was created");
      }
      return markDone(
          ctx,
          out,
          "configuration rendered; "
              + RenderedConfig.JDBC_URL_KEY
              + "="
              + url.get()
              + "; "
              + copied.size()
              + " properties file(s) copied, passwords masked, to "
              + RenderedConfig.copyDir(ctx));
    }

    /** {@code js-ant clean-config} when something is rendered, then the copy and the marker go. */
    @Override
    public StepResult compensate(Context ctx, EventSink out) {
      if (Files.isDirectory(in.renderedDir())) {
        Optional<StepResult> failure = ant(ctx, out, CLEAN_CONFIG);
        if (failure.isPresent()) {
          return failure.get();
        }
      }
      try {
        RenderedConfig.deleteCopy(RenderedConfig.copyDir(ctx));
        Files.deleteIfExists(done(ctx));
      } catch (IOException e) {
        return Steps.recoverable(
            "cannot remove " + RenderedConfig.copyDir(ctx) + ": " + e.getMessage(),
            "remove it by hand");
      }
      Steps.info(
          rt.services(),
          ctx,
          out,
          this,
          "rendered configuration cleaned (js-ant " + CLEAN_CONFIG + ")");
      return StepResult.ok();
    }
  }

  // ---------------------------------------------------------------- create, initialise, load

  /** {@code js-ant create-js-db}, with the schema's name recorded before the target runs. */
  static final class CreateJsDb extends Vendor {
    CreateJsDb(LoadRuntime rt, LoadInput in) {
      super(rt, in);
    }

    @Override
    public String id() {
      return CREATE_JS_DB;
    }

    @Override
    public String title() {
      return "create the empty schema " + in.schema() + " (js-ant " + CREATE_JS_DB + ")";
    }

    @Override
    public String phase() {
      return LoadPhases.CREATE;
    }

    @Override
    public String detail() {
      return "records the name in "
          + SchemaDrop.MARKER
          + " first, so a failure anywhere after this drops exactly this schema";
    }

    @Override
    public StepResult execute(Context ctx, EventSink out) {
      if (isDone(ctx)) {
        Steps.info(rt.services(), ctx, out, this, "schema already created in this run; skipping");
        return StepResult.ok();
      }
      try {
        SchemaDrop.record(ctx, in.schema());
      } catch (IOException e) {
        return Steps.recoverable(
            "cannot write " + SchemaDrop.marker(ctx) + ": " + e.getMessage(),
            "fix the jrsctl home's disk or permissions; nothing was created");
      }
      Optional<StepResult> failure = ant(ctx, out, CREATE_JS_DB);
      if (failure.isPresent()) {
        return failure.get();
      }
      return markDone(ctx, out, "schema " + in.schema() + " created");
    }
  }

  /** {@code js-ant init-js-db-<ce|pro>}. */
  static final class InitJsDb extends Vendor {
    InitJsDb(LoadRuntime rt, LoadInput in) {
      super(rt, in);
    }

    @Override
    public String id() {
      return in.initTarget();
    }

    @Override
    public String title() {
      return "initialise the schema (js-ant " + in.initTarget() + ")";
    }

    @Override
    public String phase() {
      return LoadPhases.INITIALISE;
    }

    @Override
    public String detail() {
      return "the vendor's tables and sequences in " + in.schema();
    }

    @Override
    public StepResult execute(Context ctx, EventSink out) {
      if (isDone(ctx)) {
        Steps.info(
            rt.services(), ctx, out, this, "schema already initialised in this run; skipping");
        return StepResult.ok();
      }
      Optional<StepResult> failure = ant(ctx, out, in.initTarget());
      if (failure.isPresent()) {
        return failure.get();
      }
      return markDone(ctx, out, "schema initialised with js-ant " + in.initTarget());
    }
  }

  /** {@code js-ant import-minimal-<ce|pro>}. */
  static final class ImportMinimal extends Vendor {
    ImportMinimal(LoadRuntime rt, LoadInput in) {
      super(rt, in);
    }

    @Override
    public String id() {
      return in.minimalTarget();
    }

    @Override
    public String title() {
      return "load the minimal content (js-ant " + in.minimalTarget() + ")";
    }

    @Override
    public String phase() {
      return LoadPhases.INITIALISE;
    }

    @Override
    public String detail() {
      return "the vendor's core resources, themes and the default administrators";
    }

    @Override
    public StepResult execute(Context ctx, EventSink out) {
      if (isDone(ctx)) {
        Steps.info(
            rt.services(), ctx, out, this, "minimal content already loaded in this run; skipping");
        return StepResult.ok();
      }
      Optional<StepResult> failure = ant(ctx, out, in.minimalTarget());
      if (failure.isPresent()) {
        return failure.get();
      }
      return markDone(ctx, out, "minimal content loaded with js-ant " + in.minimalTarget());
    }
  }

  /** {@code js-import --input-zip <export> --include-server-settings [events] [--keyalias]}. */
  static final class ImportExport extends Vendor {
    ImportExport(LoadRuntime rt, LoadInput in) {
      super(rt, in);
    }

    @Override
    public String id() {
      return IMPORT_EXPORT;
    }

    @Override
    public String title() {
      return "import " + in.export().getFileName() + " into " + in.schema() + " (js-import)";
    }

    @Override
    public String phase() {
      return LoadPhases.LOAD;
    }

    @Override
    public String detail() {
      return "js-import " + String.join(" ", VendorTools.importArgs(in.importRequest()));
    }

    @Override
    public CheckResult precheck(Context ctx) {
      CheckResult common = common();
      if (common instanceof CheckResult.Fail) {
        return common;
      }
      if (in.buildomatic().scriptFor(Buildomatic.IMPORT_SCRIPT).isEmpty()) {
        return CheckResult.fail(
            "no js-import in " + in.buildomatic().dir(),
            "unpack the full distribution's buildomatic");
      }
      return CheckResult.pass();
    }

    @Override
    public StepResult execute(Context ctx, EventSink out) {
      if (isDone(ctx)) {
        Steps.info(rt.services(), ctx, out, this, "export already imported in this run; skipping");
        return StepResult.ok();
      }
      VendorRun run =
          rt.tools()
              .importArchive(
                  in.buildomatic(),
                  in.importRequest(),
                  Optional.empty(),
                  rt.config().vendor().javaHome(),
                  out,
                  Steps.scope(ctx, this));
      return switch (run) {
        case VendorRun.Completed c ->
            c.processed() && c.reported() == VendorRun.Reported.SUCCEEDED
                ? markDone(ctx, out, "export imported into " + in.schema())
                : Steps.recoverable(
                    "js-import "
                        + c.summary()
                        + ", so the export may not be loaded: "
                        + String.join(" | ", c.tail()),
                    VendorTools.outputHint(Buildomatic.IMPORT_SCRIPT)
                        + "; the rollback drops the schema, so the run can be repeated once the"
                        + " cause is fixed");
        case VendorRun.TimedOut t ->
            Steps.recoverable(
                "js-import did not finish within " + t.timeout().toMinutes() + " minutes",
                "check for a hung buildomatic process before running again");
        case VendorRun.NotStarted n -> Steps.recoverable(n.reason(), n.remediation());
      };
    }
  }

  // ---------------------------------------------------------------- verify

  /** What was loaded, where and from which export, for the record and the audit log. */
  static final class RecordLoad implements Step {
    private final LoadRuntime rt;
    private final LoadInput in;

    RecordLoad(LoadRuntime rt, LoadInput in) {
      this.rt = Objects.requireNonNull(rt, "rt");
      this.in = Objects.requireNonNull(in, "in");
    }

    static Path file(Context ctx) {
      return ctx.home().snapshots().resolve(ctx.runId()).resolve(RECORD_FILE);
    }

    @Override
    public String id() {
      return RECORD_LOAD;
    }

    @Override
    public String title() {
      return "record the load";
    }

    @Override
    public String phase() {
      return LoadPhases.VERIFY;
    }

    @Override
    public String detail() {
      return "snapshots/<runId>/"
          + RECORD_FILE
          + " (with the hashes of the rendered configuration kept under "
          + RENDERED_DIR
          + ") and the audit entry "
          + AUDIT_COMPLETED;
    }

    @Override
    public CheckResult precheck(Context ctx) {
      return CheckResult.pass();
    }

    @Override
    public StepResult execute(Context ctx, EventSink out) {
      ObjectNode node = Json.mapper().createObjectNode();
      node.put("schema", in.schema());
      node.put("export", in.export().toString());
      node.put("exportSha256", in.exportSha256());
      node.put("buildomatic", in.buildomatic().dir().toString());
      node.put("edition", in.edition());
      node.put("includeEvents", in.options().includeEvents());
      node.put("keyAlias", in.keyAlias().orElse(null));
      node.put("loadedAt", rt.clock().instant().toString());
      node.put("runId", ctx.runId());
      try {
        Path copy = RenderedConfig.copyDir(ctx);
        List<Path> rendered = RenderedConfig.propertiesFiles(copy);
        if (!rendered.isEmpty()) {
          ObjectNode renderedNode = node.putObject("renderedConfig");
          renderedNode.put("dir", copy.toString());
          ObjectNode files = renderedNode.putObject("files");
          for (Path f : rendered) {
            files.put(f.getFileName().toString(), rt.files().sha256(f));
          }
        }
        Files.createDirectories(file(ctx).getParent());
        Files.writeString(file(ctx), Json.writePretty(node), StandardCharsets.UTF_8);
      } catch (IOException e) {
        return Steps.recoverable(
            "cannot write " + file(ctx) + ": " + e.getMessage(),
            "the schema is loaded; fix the jrsctl home's disk or permissions and resume");
      }
      rt.store()
          .audit(
              AuditActor.current(),
              AUDIT_COMPLETED,
              in.schema() + " from " + in.export() + " (" + ctx.runId() + ")");
      Steps.info(rt.services(), ctx, out, this, "load recorded in " + file(ctx));
      Steps.info(
          rt.services(),
          ctx,
          out,
          this,
          "next: deploy the server against "
              + in.schema()
              + ", then jrsctl import verify "
              + in.export()
              + " against it, and pause the imported report jobs before the scheduler starts"
              + " (POST /rest_v2/jobs/pause)");
      return StepResult.ok();
    }

    @Override
    public StepResult compensate(Context ctx, EventSink out) {
      try {
        Files.deleteIfExists(file(ctx));
      } catch (IOException e) {
        return Steps.recoverable(
            "cannot remove " + file(ctx) + ": " + e.getMessage(), "remove it by hand");
      }
      return StepResult.ok();
    }
  }
}
