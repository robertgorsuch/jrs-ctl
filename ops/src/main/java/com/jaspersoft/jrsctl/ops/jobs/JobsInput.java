package com.jaspersoft.jrsctl.ops.jobs;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * What a {@code jobs pause} or {@code jobs resume} step works from (issue #304): the record file,
 * or none for {@code pause --all}. Invariant: a present path is absolute and normalised, so a plan
 * rebuilt by {@code runs recover} from another working directory reads the same file.
 */
public record JobsInput(Optional<Path> from) {

  public JobsInput {
    Objects.requireNonNull(from, "from");
    from = from.map(p -> p.toAbsolutePath().normalize());
  }
}
