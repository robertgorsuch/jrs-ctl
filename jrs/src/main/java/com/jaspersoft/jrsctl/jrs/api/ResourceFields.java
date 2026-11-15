package com.jaspersoft.jrsctl.jrs.api;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The descriptor fields {@code import verify --content} compares for a resource that is not a file
 * (issue #307, ADR-0060): its label, its description, and for a report unit the URI of its data
 * source and of each input control. Invariants: no field is null; a field the server or the archive
 * leaves out is the empty string, or empty; {@code inputControls} is sorted, so two descriptors
 * compare by value; nothing here holds a version, a time, a permission or a password.
 */
public record ResourceFields(
    String label, String description, Optional<String> dataSource, List<String> inputControls) {

  public ResourceFields {
    label = Objects.requireNonNullElse(label, "");
    description = Objects.requireNonNullElse(description, "");
    dataSource = dataSource == null ? Optional.empty() : dataSource.filter(d -> !d.isBlank());
    inputControls = inputControls == null ? List.of() : inputControls.stream().sorted().toList();
  }
}
