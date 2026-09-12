package com.jaspersoft.jrsctl.app.console;

import com.jaspersoft.jrsctl.core.state.HotfixFile;
import com.jaspersoft.jrsctl.core.state.HotfixInstalled;
import com.jaspersoft.jrsctl.core.state.HotfixState;
import com.jaspersoft.jrsctl.core.state.StateStore;
import com.jaspersoft.jrsctl.ops.Services;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.IntSupplier;

/**
 * What the console shows about installed hotfixes (spec §13.1).
 *
 * <p>One area of the console per file (roadmap item 17). The shared state-store access and the
 * dashboard live in {@link ConsoleViews}, which owns this object and is the only caller.
 */
final class HotfixViews {

  private final Services services;

  HotfixViews(
      Services services, RunManager runs, DoctorCache doctor, String bind, IntSupplier port) {
    this.services = services;
  }

  private StateStore store() {
    return services.stateStore().get();
  }

  HotfixesDoc hotfixes() {
    StateStore store = store();
    List<HotfixInstalled> all = store.hotfixes();
    Map<String, Set<String>> files = new HashMap<>();
    for (HotfixInstalled h : all) {
      Set<String> paths = new HashSet<>();
      for (HotfixFile f : store.hotfixFiles(h.id())) {
        paths.add(f.path().toString());
      }
      files.put(h.id(), paths);
    }
    List<HotfixesDoc.Row> rows = new ArrayList<>();
    for (int i = 0; i < all.size(); i++) {
      HotfixInstalled h = all.get(i);
      List<String> blockedBy = new ArrayList<>();
      if (h.state() == HotfixState.INSTALLED) {
        for (int j = i + 1; j < all.size(); j++) {
          HotfixInstalled later = all.get(j);
          if (later.state() == HotfixState.INSTALLED
              && files.get(later.id()).stream().anyMatch(files.get(h.id())::contains)) {
            blockedBy.add(later.id());
          }
        }
      }
      rows.add(
          new HotfixesDoc.Row(
              h.id(),
              h.title(),
              h.installedAt(),
              files.get(h.id()).size(),
              h.state().name().toLowerCase(Locale.ROOT),
              blockedBy));
    }
    return new HotfixesDoc(rows);
  }
}
