# ADR-0041: move the jrsctl home with a redirect file

Status: accepted · Date: 2026-09-25 · Spec: §5.1 · Field test 3

## Context

The third field test (v2.0.0) could not get past hotfix, restore and upgrade because the tester's jrsctl home sat on a small `/home` partition and they found no setting to move it. The capability existed (`--home`, `JRSCTL_HOME`), and `init`, `doctor` and the upgrade preflight named it, but:

- the home cannot be a key in `config.yaml`, because `config.yaml` lives inside the home;
- `--home` has to be repeated on every command, and `JRSCTL_HOME` exported in every shell, service account and scheduler, which a tester does not discover and an operator forgets;
- the hotfix and snapshot space failures printed raw bytes and "free disk space", without naming the home or how to move it.

Moving the data with the home is not an option either. The state store records snapshots by absolute path (`snapshots.path`, `PathKeys`), so a copied home would still point every rollback at the old directory, and rewriting those rows would be a migration of its own.

## Options

1. **Document `JRSCTL_HOME` better.** Cheap, but leaves the per-shell burden and the discoverability problem.
2. **A per-user pointer file** (for example `~/.config/jrsctl/home`). Different operators on one host could end up in different homes, with two journals and two run locks, which is exactly what spec §5.1 (#50) forbids.
3. **A redirect file inside the home that would otherwise be used.** Everyone who reaches that home, whichever way, follows it to the same place.
4. **Move the data too.** Needs rewriting absolute paths in `state.db`; out of scope for a field-test fix.

## Decision

Option 3.

- `home.redirect` in a home names an absolute directory. `Bootstrap` (for `--home`), `JrsctlHomeResolver` (for `JRSCTL_HOME` and the default) and `LogFile` (the logging bootstrap) all follow it, one hop only, so a chain or a loop cannot form. An unreadable, empty or relative redirect counts as none.
- `jrsctl home show` prints the home, what chose it, the redirect, free space and usage, without needing a configuration or a server. `jrsctl home set <dir>` writes the redirect atomically after checking `<dir>` is writable; `jrsctl home reset` removes it. The guided menu's settings entry offers both.
- Nothing is copied. Because the old home keeps its snapshots at their recorded paths, `set` and `reset` refuse (exit 2) while the home being left holds installed hotfixes, registered customizations or runs pending recovery, unless `--force`.
- Every space failure found by the hotfix preflight, the bundle unpack, the official package conversion, the snapshot store and the upgrade backup now names the home and gives one remedy (`DiskSpace.remedy`): free space, `jrsctl runs prune`, or `jrsctl home set <dir>`. A disk-full error (`DiskSpace.outOfSpace`) is reported as such instead of as a bad bundle or download. `doctor`'s `disk` item says which volume is short and gives the advice that fits it.
- Converted official hotfix packages (`runs/hotfix-official-*.jrsctl.zip` and their notes) are pruned by age: they are a planning cache, rebuilt from the download when needed.

## Consequences

- An operator moves the home once, and every later command, scheduled task and service account that reaches the old home follows.
- The old home keeps what it holds until the operator deletes it; `home set` says so.
- Moving the data itself, and the import's pre-import snapshot free-space check, remain open.
