package com.jaspersoft.jrsctl.app.console;

import java.time.Instant;
import java.util.List;

record CustomizationsDoc(List<Item> customizations) {

  record Item(
      String path,
      String originalSha256,
      String registeredSha256,
      String currentSha256,
      String snapshotRef,
      boolean identical,
      Instant registeredAt) {}

  record DiffDoc(
      String path,
      String originalSha256,
      String registeredSha256,
      String currentSha256,
      boolean identical,
      List<String> lines) {}
}
