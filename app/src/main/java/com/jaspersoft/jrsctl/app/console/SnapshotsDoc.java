package com.jaspersoft.jrsctl.app.console;

import java.time.Instant;
import java.util.List;

record SnapshotsDoc(
    int totalCount,
    long totalBytes,
    int retentionDays,
    int maxSnapshots,
    List<SnapshotItem> snapshots) {

  record SnapshotItem(
      String id,
      String runId,
      String stepId,
      Instant createdAt,
      long bytes,
      int fileCount,
      List<String> protectionReasons,
      List<FileItem> files) {}

  record FileItem(String path, String sha256, long bytes) {}

  record PruneResult(
      int prunedCount, long reclaimedBytes, int remainingCount, int protectedCount) {}
}
