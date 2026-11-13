package com.jaspersoft.jrsctl.app;

import com.jaspersoft.jrsctl.core.engine.Plan;
import com.jaspersoft.jrsctl.core.redact.Redactor;
import com.jaspersoft.jrsctl.jrs.api.ExportImportStrategy;
import com.jaspersoft.jrsctl.jrs.api.ExportRequest;
import com.jaspersoft.jrsctl.jrs.strategy.Sidecar;
import com.jaspersoft.jrsctl.ops.PlanRegistry;
import com.jaspersoft.jrsctl.ops.Services;
import com.jaspersoft.jrsctl.ops.StrategyFlag;
import com.jaspersoft.jrsctl.ops.exim.ExportImportOperations;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/**
 * {@code jrsctl export [--uri <uri>]... [--users-roles] [--access-events] [--audit-events]
 * [--monitoring] [--settings] [--full-server] [--strategy rest|vendor] --out <file> [--plan]
 * [--yes] [--json]} (spec §9.5). Invariants: the archive and its {@code .jrsctl.json} sidecar are
 * the only files written; the export only ever runs through {@link PlanExecutor}, so it is shown,
 * confirmed, journaled and locked like every other mutation; a planning failure (unreachable
 * server, bad configuration) exits 2 because nothing has been touched yet.
 */
@Command(
    name = "export",
    mixinStandardHelpOptions = true,
    exitCodeOnInvalidInput = ExitCodes.USAGE,
    description =
        "Export repository content, or the full server, to a ZIP archive with a .jrsctl.json"
            + " sidecar.",
    subcommands = {ExportCommand.RecordSidecar.class})
final class ExportCommand implements Callable<Integer> {

  @Spec CommandSpec spec;
  @Mixin GlobalOptions global;

  @Option(
      names = "--uri",
      paramLabel = "<uri>",
      description = "Repository URI to export; repeatable. Default: the whole repository.")
  List<String> uris = new ArrayList<>();

  @Option(
      names = "--users-roles",
      description =
          "Include users and roles in a --uri export. A --full-server export already has them.")
  boolean usersRoles;

  @Option(names = "--access-events", description = "Include access events.")
  boolean accessEvents;

  @Option(names = "--audit-events", description = "Include audit events.")
  boolean auditEvents;

  @Option(names = "--monitoring", description = "Include monitoring events.")
  boolean monitoring;

  @Option(names = "--settings", description = "Include server settings.")
  boolean settings;

  @Option(
      names = "--full-server",
      description =
          "Export everything with the vendor tools (js-export --everything); the server keeps"
              + " running. Already carries the repository, users, roles, permissions, report jobs,"
              + " calendars and the settings changed in the UI; events only with"
              + " --access-events, --audit-events or --monitoring.")
  boolean fullServer;

  @Option(
      names = "--stop-service",
      description =
          "Stop the service while the vendor tools export, for an export taken with nothing"
              + " running, and start it again afterwards.")
  boolean stopService;

  @Option(
      names = "--strategy",
      paramLabel = "rest|vendor",
      description = "Force the REST or vendor CLI strategy instead of selecting one.")
  String strategy;

  @Option(
      names = "--key-alias",
      paramLabel = "<alias>",
      description =
          "Encrypt the archive with this key of the server's keystore instead of its own"
              + " import/export key; an import must name the same alias. The alias must exist"
              + " in the importing server's keystore too.")
  String keyAlias;

  @Option(
      names = {"--legacy-key", "--portable"},
      description =
          "Encrypt with the Legacy key ("
              + ExportRequest.PORTABLE_KEY_ALIAS
              + "), which every keystore since 7.5 holds, so another server can import the"
              + " archive; Jaspersoft's import calls it the Legacy key (the sidecar records it, so"
              + " jrsctl's import needs no flag). --portable is the older name.")
  boolean portable;

  @Option(
      names = "--organization",
      paramLabel = "<id>",
      description =
          "Export one organisation only (its resources, users and roles, sub-organisations"
              + " included); every --uri is then relative to it.")
  String organization;

  @Option(
      names = "--skip-dependent-resources",
      description =
          "With --uri, do not include resources a selected resource depends on (data sources,"
              + " queries, files included by reference). The archive will cause broken"
              + " dependencies on import unless the same dependencies already exist there.")
  boolean skipDependentResources;

  @Option(
      names = "--skip-favorite-resources",
      description = "Do not export resources added to Favorites.")
  boolean skipFavoriteResources;

  // Not `required = true`: picocli enforces a parent's required options even when the `record`
  // subcommand runs, so the check happens in call() instead.
  @Option(
      names = "--out",
      paramLabel = "<file>",
      description = "Archive to write (required); the sidecar goes next to it.")
  Path out;

  @Option(names = "--plan", description = "Show the plan and exit without running it.")
  boolean plan;

  @Override
  public Integer call() {
    PrintWriter outWriter = spec.commandLine().getOut();
    PrintWriter err = spec.commandLine().getErr();
    if (out == null) {
      return ExitCodes.fail(
          outWriter,
          err,
          global.json(),
          ExitCodes.USAGE,
          "Missing required option: '--out=<file>'");
    }
    Optional<ExportImportStrategy.Kind> kind;
    try {
      kind = StrategyFlag.parse(strategy);
    } catch (IllegalArgumentException e) {
      return ExitCodes.fail(outWriter, err, global.json(), ExitCodes.USAGE, e.getMessage());
    }
    if (portable && keyAlias != null) {
      return ExitCodes.fail(
          outWriter,
          err,
          global.json(),
          ExitCodes.USAGE,
          "--legacy-key (--portable) is --key-alias "
              + ExportRequest.PORTABLE_KEY_ALIAS
              + "; give one of them");
    }
    ExportImportOperations.ExportOptions options =
        new ExportImportOperations.ExportOptions(
            new LinkedHashSet<>(uris),
            usersRoles,
            accessEvents,
            auditEvents,
            monitoring,
            settings,
            fullServer,
            out,
            kind,
            stopService,
            portable
                ? Optional.of(ExportRequest.PORTABLE_KEY_ALIAS)
                : Optional.ofNullable(keyAlias),
            Optional.ofNullable(organization),
            skipDependentResources,
            skipFavoriteResources);
    try (Bootstrap boot = Bootstrap.open(global, Env.vars(), Clock.systemUTC())) {
      Services services = boot.services();
      Plan planned;
      try {
        planned = EximOps.open(services).planExport(options);
      } catch (RuntimeException e) {
        return ExitCodes.reportPlanningFailure(outWriter, err, global.json(), e);
      }
      PlanExecutor executor = new PlanExecutor(services, global, outWriter, err, Env.vars());
      return executor.execute(
          new PlanExecutor.Request(
              planned, PlanRegistry.EXPORT, PlanRegistry.exportArgs(options), plan, false));
    }
  }

  /**
   * {@code jrsctl export record <archive> [--source-version <v>] [--source-keystore <path>]
   * [--key-alias <alias>] [--force] [--json]} (issue #263): the sidecar for an archive {@code
   * js-export} made by hand, so {@code import} can check the hash, adopt the key alias, scope its
   * snapshot and judge the source version. Invariants: writes only the sidecar; needs no server and
   * no configuration; a refusal (no {@code index.xml}, a sidecar already there) exits 2 with
   * nothing written.
   */
  @Command(
      name = "record",
      mixinStandardHelpOptions = true,
      exitCodeOnInvalidInput = ExitCodes.USAGE,
      description =
          "Write the .jrsctl.json sidecar for an export archive made by hand with js-export, so"
              + " import can verify the archive, adopt its key alias and snapshot only what it"
              + " touches. Reads the archive's index.xml; nothing on any server is touched.")
  static final class RecordSidecar implements Callable<Integer> {
    @Spec CommandSpec spec;
    @Mixin GlobalOptions global;

    @picocli.CommandLine.Parameters(
        index = "0",
        paramLabel = "<archive>",
        description = "The export ZIP; the sidecar is written next to it.")
    Path archive;

    @Option(
        names = "--source-version",
        paramLabel = "<version>",
        description =
            "Version of the server that exported the archive, when its index.xml does not say"
                + " (an explicit value wins over the index).")
    String sourceVersion;

    @Option(
        names = "--source-keystore",
        paramLabel = "<path>",
        description =
            "The exporting server's .jrsks, read only for the fingerprint import compares with the"
                + " importing server's keystore.")
    Path sourceKeystore;

    @Option(
        names = "--key-alias",
        paramLabel = "<alias>",
        description =
            "The key alias the archive was encrypted with (js-export --keyalias); the Legacy"
                + " alias is read from index.xml without this flag.")
    String keyAlias;

    @Option(names = "--force", description = "Replace a sidecar that is already there.")
    boolean force;

    @Override
    public Integer call() {
      PrintWriter outWriter = spec.commandLine().getOut();
      PrintWriter err = spec.commandLine().getErr();
      Redactor redactor = Redactor.global();
      ExportImportOperations.RecordSidecarOptions options =
          new ExportImportOperations.RecordSidecarOptions(
              archive,
              Optional.ofNullable(sourceVersion),
              Optional.ofNullable(sourceKeystore),
              Optional.ofNullable(keyAlias),
              force);
      try (Bootstrap boot = Bootstrap.open(global, Env.vars(), Clock.systemUTC())) {
        ExportImportOperations.RecordedSidecar recorded;
        try {
          recorded = EximOps.open(boot.services()).recordSidecar(options);
        } catch (RuntimeException e) {
          return ExitCodes.reportPlanningFailure(outWriter, err, global.json(), e);
        }
        Sidecar sidecar = recorded.sidecar();
        if (global.json()) {
          Map<String, Object> tree = new LinkedHashMap<>();
          tree.put("archive", archive.toString());
          tree.put("sidecar", recorded.file().toString());
          tree.put("sha256", sidecar.sha256());
          tree.put("serverVersion", sidecar.serverVersion());
          tree.put("origin", sidecar.origin());
          tree.put("fullServer", sidecar.flags().fullServer());
          tree.put("uris", sidecar.flags().uris());
          tree.put("modules", new ArrayList<>(new java.util.TreeSet<>(recorded.index().modules())));
          sidecar.keystoreFingerprint().ifPresent(f -> tree.put("keystoreFingerprint", f));
          sidecar.flags().keyAlias().ifPresent(a -> tree.put("keyAlias", a));
          outWriter.println(redactor.redact(JsonOut.write(tree)));
        } else {
          outWriter.println(redactor.redact("sidecar written to " + recorded.file()));
          outWriter.println("  archive SHA-256   " + sidecar.sha256());
          outWriter.println(
              "  source version    "
                  + sidecar.serverVersion()
                  + (sidecar.serverVersion().equals(ExportImportOperations.UNKNOWN_VERSION)
                      ? " (not in index.xml; pass --source-version to record it)"
                      : ""));
          outWriter.println(
              "  content           "
                  + (sidecar.flags().fullServer()
                      ? "full server (root folder, users, roles, report jobs"
                          + (recorded.index().modules().contains("calendars") ? ", calendars" : "")
                          + ")"
                      : String.join(", ", sidecar.flags().uris())));
          outWriter.println(
              "  keystore          "
                  + sidecar
                      .keystoreFingerprint()
                      .map(f -> "fingerprint " + f)
                      .orElse(
                          "not recorded; import warns and continues, or pass --source-keystore"));
          outWriter.println(
              "  key alias         "
                  + sidecar
                      .flags()
                      .keyAlias()
                      .orElse("none; the importing server decrypts with its own keystore"));
        }
        outWriter.flush();
        return ExitCodes.SUCCESS;
      }
    }
  }
}
