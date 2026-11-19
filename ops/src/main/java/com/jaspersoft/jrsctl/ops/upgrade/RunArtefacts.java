package com.jaspersoft.jrsctl.ops.upgrade;

import com.jaspersoft.jrsctl.core.JrsctlHome;
import com.jaspersoft.jrsctl.core.platform.Platform;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The rollback-point-B files of an upgrade run that are not {@code SnapshotStore} entries, for
 * {@code runs show} (issue #341): the webapp and buildomatic archives, which carry a {@code
 * .sha256} sidecar rather than a snapshot record, and the full export, this run's own or one
 * adopted from outside the home (ADR-0028). Invariants: read-only; only files that exist are
 * listed; an adopted export is listed from its record whether or not the file is still there, since
 * the record is what a rollback would use; an unreadable record is listed as such, never hidden.
 */
public final class RunArtefacts {

  /** One file and what it is. */
  public record Artefact(String label, Path path) {
    public Artefact {
      Objects.requireNonNull(label, "label");
      Objects.requireNonNull(path, "path");
    }
  }

  private RunArtefacts() {}

  public static List<Artefact> of(JrsctlHome home, String runId, Platform.OsFamily os) {
    Objects.requireNonNull(home, "home");
    Objects.requireNonNull(runId, "runId");
    Objects.requireNonNull(os, "os");
    SnapshotSet set = SnapshotSet.of(home, runId, os);
    List<Artefact> out = new ArrayList<>();
    existing(out, "webapp archive", set.webappArchive());
    existing(out, "buildomatic archive", set.buildomaticArchive());
    existing(out, "full export", set.fullExport());
    try {
      set.externalExport()
          .ifPresent(e -> out.add(new Artefact("full export (adopted, not copied)", e.path())));
    } catch (IOException e) {
      out.add(new Artefact("adopted-export record (unreadable)", set.externalRecord()));
    }
    return List.copyOf(out);
  }

  private static void existing(List<Artefact> out, String label, Path file) {
    if (Files.isRegularFile(file)) {
      out.add(new Artefact(label, file));
    }
  }
}
