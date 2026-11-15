package com.jaspersoft.jrsctl.app;

import com.jaspersoft.jrsctl.core.redact.Redactor;
import com.jaspersoft.jrsctl.ops.customizations.CustomizationException;
import com.jaspersoft.jrsctl.ops.customizations.CustomizationOperations;
import com.jaspersoft.jrsctl.ops.customizations.CustomizationOperations.Readiness;
import com.jaspersoft.jrsctl.ops.customizations.CustomizationOperations.ReadinessFinding;
import com.jaspersoft.jrsctl.ops.customizations.CustomizationOperations.ReadinessKind;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/**
 * {@code jrsctl customizations readiness} (issue #306, ADR-0061): a report-only check of the site's
 * classes, jars and descriptor files for a move to 10.x (Jakarta EE, JDK 17). Invariants: reads
 * only, writes nothing, needs no server; exit 0 whenever the check ran, whatever it found, 1 for a
 * usage error, 2 when an input cannot be read, 6 for a target below 10.0; the text and the JSON say
 * that a file without findings is not shown to be ready.
 */
@Command(
    name = "readiness",
    mixinStandardHelpOptions = true,
    exitCodeOnInvalidInput = ExitCodes.USAGE,
    description =
        "Report the site's classes, jars and XML/JSP files that refer to javax.* packages renamed"
            + " in Jakarta EE, Java EE namespaces, classes the target version no longer has, or a"
            + " Java newer than 17. Report only; a file without findings is not proven ready.")
final class CustomizationsReadinessCommand implements Callable<Integer> {

  @Spec CommandSpec spec;
  @Mixin GlobalOptions global;

  @Option(
      names = "--target",
      required = true,
      paramLabel = "<version>",
      description = "The version you upgrade to, 10.0 or later.")
  String target;

  @Option(
      names = "--vendor",
      paramLabel = "<path>",
      description =
          "The vendor's copy of the version the server runs (distribution, webapp directory or"
              + " .war): the site's changed and added files are the ones checked.")
  Path vendor;

  @Option(
      names = "--installed",
      paramLabel = "<dir>",
      description =
          "With --vendor: check this webapp or Tomcat tree instead of the configured installation.")
  Path installed;

  @Option(
      names = "--target-vendor",
      paramLabel = "<path>",
      description =
          "The vendor's copy of the version you upgrade to: library and server classes the site"
              + " uses are looked up in its webapp, and those it lacks are reported.")
  Path targetVendor;

  @Option(
      names = "--jar",
      paramLabel = "<file>",
      description =
          "Also check this jar, class file or directory of classes (a module built outside the"
              + " webapp). Repeatable.")
  List<Path> jars = new ArrayList<>();

  /** What each kind asks of the operator, in report order. */
  static final Map<ReadinessKind, String> HINTS = new EnumMap<>(ReadinessKind.class);

  static {
    HINTS.put(
        ReadinessKind.JAKARTA,
        "names javax.* classes Jakarta EE renamed to jakarta.*: port them (the Tomcat Jakarta EE"
            + " migration tool or OpenRewrite's javax-to-jakarta recipes can help)");
    HINTS.put(
        ReadinessKind.JAKARTA_SHIPPED,
        "names renamed javax.* classes the target still ships: they may still resolve, but"
            + " check what the target uses itself");
    HINTS.put(
        ReadinessKind.NAMESPACE,
        "declares a Java EE namespace: Jakarta EE 10 descriptors use"
            + " https://jakarta.ee/xml/ns/jakartaee; check whether the target accepts this one");
    HINTS.put(
        ReadinessKind.NOT_IN_TARGET,
        "names library or server classes the target's webapp does not have: removed or moved,"
            + " or, for a third-party library jar, an optional dependency the target never had");
    HINTS.put(
        ReadinessKind.BYTECODE,
        "is compiled for a Java newer than 17, the runtime the 10.0.0 distribution bundles");
    HINTS.put(ReadinessKind.UNREADABLE, "could not be read; check it by hand");
  }

  private static final int SHOWN = 8;
  private static final Pattern MAJOR = Pattern.compile("^(\\d+)");

  @Override
  public Integer call() {
    PrintWriter out = spec.commandLine().getOut();
    PrintWriter err = spec.commandLine().getErr();
    Redactor redactor = Redactor.global();
    if (installed != null && vendor == null) {
      return ExitCodes.fail(
          out,
          err,
          global.json(),
          ExitCodes.USAGE,
          "--installed needs --vendor: without the vendor's copy every file would be checked as"
              + " the site's");
    }
    if (vendor == null && jars.isEmpty()) {
      return ExitCodes.fail(
          out, err, global.json(), ExitCodes.USAGE, "pass --vendor, --jar, or both");
    }
    Matcher m = MAJOR.matcher(target.strip());
    if (!m.find()) {
      return ExitCodes.fail(
          out, err, global.json(), ExitCodes.USAGE, "--target " + target + " is not a version");
    }
    if (Integer.parseInt(m.group(1)) < 10) {
      return ExitCodes.fail(
          out,
          err,
          global.json(),
          ExitCodes.UNSUPPORTED,
          "readiness checks a move to 10.x (Jakarta EE, JDK 17); --target " + target + " is older");
    }
    try (Bootstrap boot = Bootstrap.open(global, Env.vars(), Clock.systemUTC())) {
      CustomizationOperations ops = CustomizationsCommand.open(boot.services());
      Readiness r;
      try {
        r =
            ops.readiness(
                new CustomizationOperations.ReadinessOptions(
                    target.strip(),
                    Optional.ofNullable(vendor),
                    Optional.ofNullable(installed),
                    Optional.ofNullable(targetVendor),
                    jars));
      } catch (CustomizationException e) {
        return CustomizationsCommand.refused(out, err, global.json(), e);
      } catch (RuntimeException e) {
        return ExitCodes.reportPlanningFailure(out, err, global.json(), e);
      }
      if (global.json()) {
        out.println(redactor.redact(JsonOut.write(json(r))));
      } else {
        print(out, redactor, r);
      }
      out.flush();
      return ExitCodes.SUCCESS;
    }
  }

  static Map<String, Object> json(Readiness r) {
    Map<String, Object> tree = new LinkedHashMap<>();
    tree.put("targetVersion", r.targetVersion());
    tree.put("installedWebapp", r.installedWebapp().map(Path::toString).orElse(null));
    tree.put("targetVendorWebapp", r.targetVendorWebapp().map(Path::toString).orElse(null));
    tree.put("libraryLookup", r.targetVendorWebapp().isPresent());
    tree.put("filesChecked", r.filesChecked());
    tree.put("classesChecked", r.classesChecked());
    Map<String, Integer> counts = new LinkedHashMap<>();
    r.counts().forEach((k, v) -> counts.put(k.name(), v));
    tree.put("counts", counts);
    List<Map<String, Object>> findings = new ArrayList<>();
    for (ReadinessFinding f : r.findings()) {
      Map<String, Object> one = new LinkedHashMap<>();
      one.put("file", f.file());
      one.put("kind", f.kind().name());
      one.put("references", f.references());
      findings.add(one);
    }
    tree.put("findings", findings);
    return tree;
  }

  private static void print(PrintWriter out, Redactor redactor, Readiness r) {
    out.println(
        redactor.redact(
            "Readiness for "
                + r.targetVersion()
                + ": "
                + r.filesChecked()
                + " file(s) checked, "
                + r.classesChecked()
                + " class(es) read"
                + r.installedWebapp().map(w -> ", site files of " + w).orElse("")));
    List<String> counts = new ArrayList<>();
    r.counts().forEach((k, v) -> counts.add(v + " " + k.name()));
    out.println("  files with findings: " + String.join(", ", counts));
    if (r.targetVendorWebapp().isEmpty()) {
      out.println(
          "  library classes not looked up: pass --target-vendor to check them against the"
              + " target's webapp");
    }
    for (ReadinessKind kind : ReadinessKind.values()) {
      List<ReadinessFinding> of = r.findings().stream().filter(f -> f.kind() == kind).toList();
      if (of.isEmpty()) {
        continue;
      }
      out.println();
      out.println(kind.name() + ": " + HINTS.get(kind));
      for (ReadinessFinding f : of) {
        List<String> refs = f.references();
        String shown = String.join(", ", refs.subList(0, Math.min(SHOWN, refs.size())));
        out.println(
            redactor.redact(
                "  "
                    + f.file()
                    + ": "
                    + shown
                    + (refs.size() > SHOWN ? " and " + (refs.size() - SHOWN) + " more" : "")));
      }
    }
    out.println();
    out.println(
        "No finding does not mean ready: this reads the names a file uses, not what it does."
            + " Build and test the site's code against the target; jdeps --jdk-internals also"
            + " finds JDK-internal APIs, which this check does not.");
  }
}
