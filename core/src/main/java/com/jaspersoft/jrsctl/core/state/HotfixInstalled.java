package com.jaspersoft.jrsctl.core.state;

import java.time.Instant;
import java.util.Optional;

/**
 * A row of {@code hotfixes_installed} (spec §5.4, §8.4). Invariant: {@code id} is the manifest id
 * and the primary key, so a hotfix can be installed at most once per state store.
 */
public record HotfixInstalled(
    String id,
    String version,
    String title,
    String installedRunId,
    Optional<String> snapshotRef,
    HotfixState state,
    Instant installedAt) {}
