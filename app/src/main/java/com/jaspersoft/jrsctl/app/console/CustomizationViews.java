package com.jaspersoft.jrsctl.app.console;

import com.jaspersoft.jrsctl.core.snapshot.SnapshotStore;
import com.jaspersoft.jrsctl.core.state.Customization;
import com.jaspersoft.jrsctl.ops.Services;
import com.jaspersoft.jrsctl.ops.customizations.CustomizationOperations;
import com.jaspersoft.jrsctl.ops.customizations.DefaultCustomizationOperations;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.IntSupplier;

final class CustomizationViews {

  private final CustomizationOperations operations;

  CustomizationViews(
      Services services, RunManager runs, DoctorCache doctor, String bind, IntSupplier port) {
    this.operations =
        new DefaultCustomizationOperations(
            Objects.requireNonNull(services, "services"),
            new SnapshotStore(services.home(), services.platform().files(), services.clock()));
  }

  CustomizationsDoc customizations() {
    List<Customization> list = operations.list();
    List<CustomizationsDoc.Item> items = new ArrayList<>();
    for (Customization c : list) {
      String currentSha = "";
      boolean identical = false;
      try {
        CustomizationOperations.Diff diff = operations.diff(c.path());
        currentSha = diff.currentSha256();
        identical = diff.identical();
      } catch (RuntimeException e) {
        // file might be missing or unreadable
      }
      items.add(
          new CustomizationsDoc.Item(
              c.path().toString().replace('\\', '/'),
              c.originalSha256(),
              c.originalSha256(),
              currentSha,
              c.snapshotRef().orElse(""),
              identical,
              c.registeredAt()));
    }
    return new CustomizationsDoc(items);
  }

  CustomizationsDoc.DiffDoc diff(String pathStr) {
    Path path = Path.of(pathStr);
    CustomizationOperations.Diff diff = operations.diff(path);
    return new CustomizationsDoc.DiffDoc(
        diff.path().toString().replace('\\', '/'),
        diff.originalSha256(),
        diff.registeredSha256(),
        diff.currentSha256(),
        diff.identical(),
        diff.lines());
  }

  Customization register(String pathStr, Optional<String> pristineCopyStr) {
    Path path = Path.of(pathStr);
    Optional<Path> pristine = pristineCopyStr.filter(s -> !s.isBlank()).map(Path::of);
    return operations.register(path, pristine);
  }

  boolean unregister(String pathStr) {
    Path path = Path.of(pathStr);
    return operations.unregister(path);
  }
}
