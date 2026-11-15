package com.jaspersoft.jrsctl.ops.customizations;

import com.jaspersoft.jrsctl.core.state.Customization;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Registry of operator-customised files (spec §10.3): registering snapshots the file and records
 * its hash as "original", so that an upgrade can decide between automatic re-application and a
 * reported conflict (spec §10.2 step 13). Invariants: only files under {@code server.installDir} or
 * {@code server.tomcatDir} are accepted; a registered customization's snapshot is
 * retention-protected until it is unregistered; every mutation is audited; nothing here touches the
 * server.
 */
public interface CustomizationOperations {

  /** The webapp directory whose files are an overlay rather than files (issue #117). */
  String SCRIPTS_PREFIX = "scripts/";

  /**
   * Issue #117 (vendor review §5.2, the JavaScript customisation deck): why a per-file comparison
   * of {@code scripts/} cannot work and what to do instead.
   */
  String SCRIPTS_ADVICE =
      "scripts/ is an overlay, not a set of files an upgrade can compare: since 8.0 the front end"
          + " is the jasperserver-ui webpack project, a customised scripts tree is rebuilt and"
          + " copied whole under hashed bundle names, so a per-file comparison always reports a"
          + " conflict. Carry the change in your jasperserver-ui project, rebuild it, and copy the"
          + " result over the new webapp (operator guide, JavaScript customisations)";

  /** Result of {@link #diff(Path)}: the registered copy against the file on disk. */
  record Diff(
      Path path,
      String originalSha256,
      String registeredSha256,
      String currentSha256,
      boolean identical,
      List<String> lines) {
    public Diff {
      Objects.requireNonNull(path, "path");
      Objects.requireNonNull(originalSha256, "originalSha256");
      Objects.requireNonNull(registeredSha256, "registeredSha256");
      Objects.requireNonNull(currentSha256, "currentSha256");
      lines = List.copyOf(lines);
    }
  }

  /** Registers with the file's current hash as the "original" (spec §10.3). */
  default Customization register(Path path) {
    return register(path, java.util.Optional.empty());
  }

  /**
   * Registers {@code path}; when {@code pristineCopy} names a file (for example the vendor's
   * unmodified version from the distribution) its hash is recorded as the "original" so the
   * upgrade's 3-way comparison can tell "unchanged by the vendor" from "customized".
   */
  Customization register(Path path, java.util.Optional<Path> pristineCopy);

  /** How a file of the installed webapp differs from the vendor's copy (#72). */
  enum Change {
    /** Present in both and different: an operator's change. */
    CHANGED,
    /** Only in the installation. */
    ADDED,
    /** Different, but a file the installer fills in with site values; not a customization. */
    INSTALLER,
    /** Only in the vendor's copy. */
    REMOVED
  }

  /**
   * Issue #267: what registering an installer-written file means for an upgrade. The file is
   * accepted; the operator should expect a conflict rather than an automatic re-apply.
   */
  String INSTALLER_ADVICE =
      "this is a file the installer writes with site values; an upgrade re-applies a registered"
          + " file only when the vendor's copy is unchanged, which is rarely true for one the"
          + " installer rewrites, so expect a CONFLICT with a diff in plan-customization-reapply"
          + " and carry the change over by hand";

  /**
   * One file of {@link #scan}; {@code installed} is empty for a removed file. {@code diff} is
   * present for an {@link Change#INSTALLER} file the vendor copy also has (issue #267): the unified
   * diff from the vendor copy to the installed file, or a one-line notice when either is binary or
   * very large.
   */
  record ScanEntry(
      String relativePath,
      Change change,
      Optional<Path> installed,
      Optional<String> vendorSha256,
      boolean registered,
      Optional<String> diff) {
    public ScanEntry {
      Objects.requireNonNull(relativePath, "relativePath");
      Objects.requireNonNull(change, "change");
      Objects.requireNonNull(installed, "installed");
      Objects.requireNonNull(vendorSha256, "vendorSha256");
      Objects.requireNonNull(diff, "diff");
    }

    public ScanEntry(
        String relativePath,
        Change change,
        Optional<Path> installed,
        Optional<String> vendorSha256,
        boolean registered) {
      this(relativePath, change, installed, vendorSha256, registered, Optional.empty());
    }
  }

  /** What kind of Tomcat-side file a {@link TomcatEntry} is (issue #117). */
  enum TomcatKind {
    /** {@code bin/setenv.sh} or {@code bin/setenv.bat}: JAVA_OPTS, heap, {@code --add-opens}. */
    SETENV,
    /** {@code conf/server.xml}: connectors, ports, TLS. */
    SERVER_XML,
    /** {@code conf/Catalina/localhost/*.xml}: a context fragment, such as a data source. */
    CONTEXT_FRAGMENT,
    /** A {@code lib/*.jar} Tomcat does not ship: a JDBC driver or another site addition. */
    LIBRARY
  }

  /**
   * One Tomcat-side file the upgrade guides say to carry over; {@code relativePath} is under the
   * Tomcat.
   */
  record TomcatEntry(String relativePath, TomcatKind kind, boolean registered) {
    public TomcatEntry {
      Objects.requireNonNull(relativePath, "relativePath");
      Objects.requireNonNull(kind, "kind");
    }
  }

  /**
   * The Tomcat-side files an upgrade does not carry over by itself (issue #117): {@code
   * bin/setenv.*}, {@code conf/server.xml}, {@code conf/Catalina/localhost/*.xml} and the {@code
   * lib} jars Tomcat does not ship. There is no pristine Tomcat to compare with, so this lists what
   * to carry over, not what changed; read-only.
   */
  List<TomcatEntry> scanTomcat();

  /**
   * As {@link #scanTomcat()} for an explicit tree (issue #266): {@code tree} is the Tomcat
   * directory (it holds {@code webapps/}) or a webapp directory under one (its grandparent is then
   * listed). Needs no configuration.
   */
  List<TomcatEntry> scanTomcat(Path tree);

  /**
   * A warning to show when {@code path} is registered, for a file that cannot be reconciled per
   * file: one under the webapp's {@code scripts/} overlay (issue #117); empty otherwise.
   */
  Optional<String> registrationAdvice(Path path);

  /** The installed webapp against the vendor's copy. */
  record Scan(Path installedWebapp, Path vendorWebapp, List<ScanEntry> entries) {
    public Scan {
      Objects.requireNonNull(installedWebapp, "installedWebapp");
      Objects.requireNonNull(vendorWebapp, "vendorWebapp");
      entries = List.copyOf(entries);
    }
  }

  /**
   * Compares the installed webapp with the vendor's untouched copy ({@code vendor}: an unpacked
   * distribution, its webapp directory or its war) and lists what differs; read-only (#72).
   */
  Scan scan(Path vendor);

  /**
   * As {@link #scan(Path)} for an explicit tree instead of the configured installation (issue
   * #266): {@code installed} is the webapp directory itself (it holds {@code WEB-INF/}) or a Tomcat
   * tree holding {@code webapps/jasperserver-pro} or {@code webapps/jasperserver}, as extracted
   * from a container image. Needs no configuration; the registered customizations still come from
   * the state store, so {@code registered} is meaningful only when the tree is the configured
   * installation.
   */
  Scan scan(Path installed, Path vendor);

  /** How the vendor's file at one path changed from the source to the target version (#305). */
  enum TargetChange {
    /** Same content in both versions. */
    UNCHANGED,
    /** In both versions, different. */
    CHANGED,
    /** In the source version only. */
    REMOVED,
    /** In the target version only. */
    ADDED,
    /** In neither version. */
    ABSENT
  }

  /**
   * What an upgrade to the target version means for one site file (issue #305, ADR-0057), in the
   * order the report lists them.
   */
  enum TargetClass {
    /** The site changed a vendor file the vendor did not change: re-apply as is. */
    CARRY,
    /** The site and the vendor both changed the file: a three-way merge. */
    MERGE,
    /** The site changed a vendor file the target no longer ships: moved or dropped. */
    OBSOLETE,
    /** The site added a file the target does not ship either: carry it over. */
    OWN,
    /**
     * The site added a file at a path the target now ships: copying it would replace the vendor's.
     */
    CLASH,
    /** A file the installer writes: put the site's values into the target's copy. */
    INSTALLER,
    /**
     * Under {@code scripts/}: rebuild in jasperserver-ui, not a file merge ({@link
     * #SCRIPTS_ADVICE}).
     */
    OVERLAY,
    /** The site deleted a vendor file: check whether the target still ships it. */
    REMOVED
  }

  /**
   * One site file of a {@link TargetScan}; {@code merge} names the {@code .mine}, {@code .base} and
   * {@code .theirs} files written for a {@link TargetClass#MERGE} file with {@code --out}.
   */
  record TargetEntry(
      ScanEntry entry, TargetClass targetClass, TargetChange target, List<Path> merge) {
    public TargetEntry {
      Objects.requireNonNull(entry, "entry");
      Objects.requireNonNull(targetClass, "targetClass");
      Objects.requireNonNull(target, "target");
      merge = List.copyOf(merge);
    }
  }

  /** A {@link Scan} classified against the target version's vendor webapp (issue #305). */
  record TargetScan(
      Scan scan, Path targetVendorWebapp, List<TargetEntry> entries, Optional<Path> out) {
    public TargetScan {
      Objects.requireNonNull(scan, "scan");
      Objects.requireNonNull(targetVendorWebapp, "targetVendorWebapp");
      Objects.requireNonNull(out, "out");
      entries = List.copyOf(entries);
    }

    /** How many entries each class holds, every class present, in report order. */
    public java.util.Map<TargetClass, Long> counts() {
      java.util.Map<TargetClass, Long> m = new java.util.EnumMap<>(TargetClass.class);
      for (TargetClass c : TargetClass.values()) {
        m.put(c, 0L);
      }
      entries.forEach(e -> m.merge(e.targetClass(), 1L, Long::sum));
      return m;
    }
  }

  /**
   * Classifies every file of {@code scan} against the target version's vendor webapp ({@code
   * targetVendor}: an unpacked distribution, its webapp directory or its war; issue #305,
   * ADR-0057). With {@code out}, writes each {@link TargetClass#MERGE} file as {@code <path>.mine},
   * {@code .base} and {@code .theirs} under it, refusing a directory that is not empty. Read-only
   * otherwise; registers nothing.
   */
  TargetScan classifyAgainstTarget(Scan scan, Path targetVendor, Optional<Path> out);

  /** What {@code customizations readiness} reports about one site file (issue #306, ADR-0061). */
  enum ReadinessKind {
    /** Names {@code javax.*} classes Jakarta EE 9 renamed to {@code jakarta.*}. */
    JAKARTA,
    /** Names renamed {@code javax.*} classes the target's webapp still ships itself. */
    JAKARTA_SHIPPED,
    /** Declares a Java EE schema or descriptor namespace. */
    NAMESPACE,
    /** Names a library or server class that the target's webapp does not hold. */
    NOT_IN_TARGET,
    /** Compiled for a Java newer than 17. */
    BYTECODE,
    /** Could not be read. */
    UNREADABLE
  }

  /** One kind of finding in one site file, with what it refers to, sorted. */
  record ReadinessFinding(String file, ReadinessKind kind, List<String> references) {
    public ReadinessFinding {
      Objects.requireNonNull(file, "file");
      Objects.requireNonNull(kind, "kind");
      references = List.copyOf(references);
    }
  }

  /**
   * The inputs of {@code customizations readiness}: the site's changed and added files from a scan
   * against {@code vendor} (of {@code installed}, else the configured installation), and/or the
   * {@code jars} named; {@code targetVendor} enables the lookup of library classes.
   */
  record ReadinessOptions(
      String targetVersion,
      Optional<Path> vendor,
      Optional<Path> installed,
      Optional<Path> targetVendor,
      List<Path> jars) {
    public ReadinessOptions {
      Objects.requireNonNull(targetVersion, "targetVersion");
      Objects.requireNonNull(vendor, "vendor");
      Objects.requireNonNull(installed, "installed");
      Objects.requireNonNull(targetVendor, "targetVendor");
      jars = List.copyOf(jars);
    }
  }

  /** The readiness report: what was checked and what was found. */
  record Readiness(
      String targetVersion,
      Optional<Path> installedWebapp,
      Optional<Path> targetVendorWebapp,
      int filesChecked,
      int classesChecked,
      List<ReadinessFinding> findings) {
    public Readiness {
      Objects.requireNonNull(targetVersion, "targetVersion");
      Objects.requireNonNull(installedWebapp, "installedWebapp");
      Objects.requireNonNull(targetVendorWebapp, "targetVendorWebapp");
      findings = List.copyOf(findings);
    }

    /** Files with at least one finding, per kind. */
    public Map<ReadinessKind, Integer> counts() {
      Map<ReadinessKind, Integer> m = new java.util.EnumMap<>(ReadinessKind.class);
      for (ReadinessKind k : ReadinessKind.values()) {
        m.put(k, 0);
      }
      for (ReadinessFinding f : findings) {
        m.merge(f.kind(), 1, Integer::sum);
      }
      return m;
    }
  }

  /**
   * A report-only readiness check of the site's classes, jars and descriptor files for a move to
   * {@code targetVersion} (10.x: Jakarta EE, JDK 17; issue #306, ADR-0061). A file without findings
   * is not shown to be ready; the check names likely work only.
   */
  Readiness readiness(ReadinessOptions options);

  /**
   * Registers every {@link Change#CHANGED} and {@link Change#ADDED} file of {@code scan} that is
   * not registered yet: a changed file with the vendor's hash as its original, an added one with
   * its own; returns the new registrations.
   */
  List<Customization> registerScan(Scan scan);

  boolean unregister(Path path);

  List<Customization> list();

  Diff diff(Path path);
}
