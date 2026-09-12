package com.jaspersoft.jrsctl.ops.hotfix;

import com.jaspersoft.jrsctl.core.state.HotfixFile;
import com.jaspersoft.jrsctl.core.state.HotfixInstalled;
import com.jaspersoft.jrsctl.core.state.StateStore;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Which hotfixes have to come off, and in what order, to roll one of them back (spec §8.3).
 * Invariants: the order is strictly last-installed-first, because a file owned by a later hotfix
 * must be restored by that hotfix before the earlier one can put its own version back; a hotfix
 * blocked by a later one is refused unless {@code --cascade} was given, and the refusal names every
 * direct blocker so the operator can decide; the walk is transitive, so a blocker's own blockers
 * are included, and a cycle cannot loop because a hotfix is only visited once.
 */
final class RollbackChain {

  private RollbackChain() {}

  /**
   * The ids to roll back, newest first, starting at {@code target}.
   *
   * @param order installation order by hotfix id; a missing id sorts before every known one
   * @param cascade whether later hotfixes owning the same files may be taken off too
   * @throws HotfixException when the target is blocked and {@code cascade} is false
   */
  static List<String> of(
      StateStore store, HotfixInstalled target, Map<String, Integer> order, boolean cascade) {
    Set<String> selected = new LinkedHashSet<>();
    List<String> pending = new ArrayList<>();
    pending.add(target.id());
    List<String> directBlockers = new ArrayList<>();
    while (!pending.isEmpty()) {
      String id = pending.remove(pending.size() - 1);
      if (!selected.add(id)) {
        continue;
      }
      int position = order.getOrDefault(id, -1);
      List<Path> paths = store.hotfixFiles(id).stream().map(HotfixFile::path).toList();
      Set<String> blockers = new LinkedHashSet<>();
      for (HotfixFile owned : store.filesOwnedBy(paths)) {
        String other = owned.hotfixId();
        if (!other.equals(id) && order.getOrDefault(other, -1) > position) {
          blockers.add(other);
        }
      }
      if (id.equals(target.id())) {
        directBlockers.addAll(blockers);
      }
      pending.addAll(blockers);
    }
    if (!directBlockers.isEmpty() && !cascade) {
      throw new HotfixException(
          HotfixException.PRECHECK,
          "rollback of "
              + target.id()
              + " is blocked by later hotfixes owning the same files: "
              + String.join(", ", directBlockers),
          "roll those back first, or re-run with --cascade");
    }
    List<String> ordered = new ArrayList<>(selected);
    ordered.sort((a, b) -> Integer.compare(order.getOrDefault(b, -1), order.getOrDefault(a, -1)));
    return List.copyOf(ordered);
  }
}
