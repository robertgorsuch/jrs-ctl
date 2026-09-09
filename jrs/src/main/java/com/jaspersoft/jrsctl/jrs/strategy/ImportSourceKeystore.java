package com.jaspersoft.jrsctl.jrs.strategy;

import com.jaspersoft.jrsctl.core.config.Config;
import com.jaspersoft.jrsctl.core.engine.CheckResult;
import com.jaspersoft.jrsctl.core.engine.Context;
import com.jaspersoft.jrsctl.core.engine.Step;
import com.jaspersoft.jrsctl.core.engine.StepResult;
import com.jaspersoft.jrsctl.core.event.EventSink;
import com.jaspersoft.jrsctl.core.json.Json;
import com.jaspersoft.jrsctl.core.redact.Redactor;
import com.jaspersoft.jrsctl.core.secrets.Secret;
import com.jaspersoft.jrsctl.core.secrets.SecretException;
import com.jaspersoft.jrsctl.core.secrets.SecretResolver;
import com.jaspersoft.jrsctl.jrs.api.ImportRequest;
import com.jaspersoft.jrsctl.jrs.api.JrsAdapter;
import com.jaspersoft.jrsctl.jrs.api.JrsUnreachableException;
import com.jaspersoft.jrsctl.jrs.api.KeystoreInfo;
import com.jaspersoft.jrsctl.jrs.vendor.Buildomatic;
import com.jaspersoft.jrsctl.jrs.vendor.VendorRun;
import com.jaspersoft.jrsctl.jrs.vendor.VendorTools;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The vendor-documented keystore import step (spec §9.3): backs up the server's {@code .jrsks} and
 * {@code .jrsksp}, then runs {@code js-import} with the keystore options so the target server
 * adopts the source keystore before the archive is imported. Invariants: the backup is taken once
 * per run (re-execution reuses it, so the pristine files are never overwritten by a second copy);
 * compensation restores exactly the files that existed and deletes any the vendor tool created; the
 * keystore password is resolved from {@code sourceKeystorePassword} through the {@code
 * SecretResolver} in the run context and registered with the {@code Redactor} before it reaches a
 * command line. The exact vendor flags are the least certain part of this build, see {@link
 * VendorTools#importKeystore}.
 */
final class ImportSourceKeystore implements Step {

  static final String ID = "import.source-keystore";
  static final String MANIFEST = "keystore-backup.json";
  static final String BACKUP_DIR = "keystore-backup";

  /**
   * One server keystore file and where its copy went; {@code backup} empty when it did not exist.
   */
  record Entry(Path original, Optional<Path> backup) {}

  record Manifest(List<Entry> entries) {
    Manifest {
      entries = List.copyOf(entries);
    }
  }

  private final String phase;
  private final ImportRequest request;
  private final Path sourceKeystore;
  private final VendorAccess vendor;

  ImportSourceKeystore(String phase, ImportRequest request, VendorAccess vendor) {
    this.phase = Objects.requireNonNull(phase, "phase");
    this.request = Objects.requireNonNull(request, "request");
    this.sourceKeystore =
        request
            .sourceKeystore()
            .orElseThrow(() -> new IllegalArgumentException("request has no source keystore"));
    this.vendor = Objects.requireNonNull(vendor, "vendor");
  }

  @Override
  public String id() {
    return ID;
  }

  @Override
  public String title() {
    return "Import source keystore";
  }

  @Override
  public String phase() {
    return phase;
  }

  @Override
  public String detail() {
    return sourceKeystore.toString();
  }

  @Override
  public CheckResult precheck(Context ctx) {
    if (!Files.isRegularFile(sourceKeystore)) {
      return CheckResult.fail(
          "source keystore " + sourceKeystore + " does not exist",
          "pass the path of the source server's .jrsks file with --source-keystore");
    }
    if (request.sourceKeystorePassword().isPresent() && !ctx.has(SecretResolver.class)) {
      return CheckResult.fail(
          "no SecretResolver registered in the run context",
          "the ops layer must register a SecretResolver so --source-keystore-password-ref can"
              + " be resolved");
    }
    Config config = ctx.service(Config.class);
    Optional<Buildomatic> b = vendor.locate(ctx);
    if (b.isEmpty()) {
      return CheckResult.fail(
          "buildomatic directory not found under server.installDir",
          "set server.installDir to the JasperReports Server installation root");
    }
    if (b.get().scriptFor(Buildomatic.IMPORT_SCRIPT).isEmpty()) {
      return CheckResult.fail(
          "js-import script missing in " + b.get().dir(), "check the installation is complete");
    }
    if (config.vendor().javaHome().isEmpty()) {
      return CheckResult.fail(
          "vendor.javaHome is not set", "set vendor.javaHome to the JDK buildomatic should use");
    }
    try {
      KeystoreInfo ks = ctx.service(JrsAdapter.class).keystore();
      if (ks.keystoreFile().isEmpty()) {
        return CheckResult.warn(
            "server keystore location unknown ("
                + ks.reason().orElse("")
                + "); no backup of the current keystore can be taken");
      }
    } catch (JrsUnreachableException e) {
      return CheckResult.warn("server unreachable, keystore location not verified");
    }
    return CheckResult.pass();
  }

  @Override
  public StepResult execute(Context ctx, EventSink out) {
    Path manifestFile = RunFiles.in(ctx, MANIFEST);
    try {
      if (RunFiles.read(manifestFile).isEmpty()) {
        backup(ctx, manifestFile);
      } else {
        Logs.info(out, ctx, this, "keystore backup already taken in " + manifestFile.getParent());
      }
    } catch (IOException e) {
      return Failures.recoverable(
          "cannot back up the server keystore: " + e.getMessage(),
          List.of(manifestFile),
          "check permissions on the run directory and the keystore files");
    }
    Config config = ctx.service(Config.class);
    Optional<Buildomatic> b = vendor.locate(ctx);
    if (b.isEmpty()) {
      return Failures.recoverable(
          "buildomatic directory not found", List.of(), "set server.installDir");
    }
    Optional<Secret> storepass;
    try {
      storepass = resolvePassword(ctx);
    } catch (SecretException e) {
      return Failures.recoverable(
          "cannot resolve the source keystore password: " + e.getMessage(),
          List.of(),
          "check --source-keystore-password-ref");
    }
    try {
      VendorRun run =
          vendor
              .tools()
              .apply(ctx)
              .importKeystore(
                  b.get(),
                  sourceKeystore,
                  storepass,
                  config.vendor().javaHome(),
                  out,
                  Logs.scope(ctx, this));
      return switch (run) {
        case VendorRun.Completed c ->
            c.ok()
                ? StepResult.ok()
                : Failures.recoverableWithBackups(
                    "js-import (keystore) exited with "
                        + c.exitCode()
                        + ": "
                        + String.join(" | ", c.tail()),
                    List.of(sourceKeystore),
                    backups(ctx),
                    "check the buildomatic log; the previous keystore files are restored by"
                        + " rollback");
        case VendorRun.TimedOut t ->
            Failures.recoverableWithBackups(
                "js-import (keystore) did not finish within " + t.timeout().toMinutes() + "m",
                List.of(sourceKeystore),
                backups(ctx),
                "check for a hung buildomatic process and run again");
        case VendorRun.NotStarted n -> Failures.recoverable(n.reason(), List.of(), n.remediation());
      };
    } finally {
      storepass.ifPresent(Secret::close);
    }
  }

  @Override
  public StepResult compensate(Context ctx, EventSink out) {
    Path manifestFile = RunFiles.in(ctx, MANIFEST);
    try {
      Optional<String> json = RunFiles.read(manifestFile);
      if (json.isEmpty()) {
        return StepResult.ok();
      }
      Manifest manifest = Json.read(json.get(), Manifest.class);
      for (Entry e : manifest.entries()) {
        if (e.backup().isPresent()) {
          Files.copy(e.backup().get(), e.original(), StandardCopyOption.REPLACE_EXISTING);
          Logs.info(out, ctx, this, "restored " + e.original());
        } else if (Files.deleteIfExists(e.original())) {
          Logs.info(out, ctx, this, "removed " + e.original() + " (did not exist before)");
        }
      }
      return StepResult.ok();
    } catch (IOException | IllegalArgumentException e) {
      return Failures.recoverableWithBackups(
          "cannot restore the server keystore: " + e.getMessage(),
          List.of(),
          backups(ctx),
          "copy the files from the backup directory back by hand");
    }
  }

  private void backup(Context ctx, Path manifestFile) throws IOException {
    KeystoreInfo ks;
    try {
      ks = ctx.service(JrsAdapter.class).keystore();
    } catch (JrsUnreachableException e) {
      ks = KeystoreInfo.absent("server unreachable");
    }
    Path dir = RunFiles.in(ctx, BACKUP_DIR);
    Files.createDirectories(dir);
    List<Entry> entries = new ArrayList<>();
    for (Optional<Path> p : List.of(ks.keystoreFile(), ks.propertiesFile())) {
      if (p.isEmpty()) {
        continue;
      }
      Path original = p.get();
      if (Files.isRegularFile(original)) {
        Path copy = dir.resolve(original.getFileName().toString());
        Files.copy(original, copy, StandardCopyOption.REPLACE_EXISTING);
        entries.add(new Entry(original, Optional.of(copy)));
      } else {
        entries.add(new Entry(original, Optional.empty()));
      }
    }
    Files.createDirectories(manifestFile.getParent());
    Path tmp = manifestFile.resolveSibling(manifestFile.getFileName() + ".tmp");
    Files.writeString(tmp, Json.write(new Manifest(entries)), StandardCharsets.UTF_8);
    RunFiles.replace(tmp, manifestFile);
  }

  private List<Path> backups(Context ctx) {
    Path dir = RunFiles.in(ctx, BACKUP_DIR);
    return Files.isDirectory(dir) ? List.of(dir) : List.of();
  }

  private Optional<Secret> resolvePassword(Context ctx) {
    if (request.sourceKeystorePassword().isEmpty()) {
      return Optional.empty();
    }
    Secret s = ctx.service(SecretResolver.class).resolve(request.sourceKeystorePassword().get());
    if (ctx.has(Redactor.class)) {
      ctx.service(Redactor.class).register(s);
    }
    return Optional.of(s);
  }
}
