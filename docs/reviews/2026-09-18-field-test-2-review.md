# Second field test of v1.6.0 — findings checked against the code

Date: 2026-09-18. Tester: the JasperReports Server support engineer who ran the first field test (2026-09-16). Release
under test: `v1.6.0` (tag `a05d264`, 2026-09-17 10:13). Checked against `main` at `219d8c9` (1.7.0-SNAPSHOT).
Plan that acts on this review: `docs/superpowers/plans/2026-09-18-field-test-2.md`.

Two screenshots in the report (the overflow, the two export options) were not available for this review.

**The fact that reframes several findings:** nine commits landed on `main` after the tag, and three of them
answer findings directly: `f8feb44` official hotfix packages (ADR-0024), `70ac79b` newdb export after the stop
(ADR-0025), `0e32c21` mode-aware upgrade paths (matrix v2). None of that is in the tester's hands. Cutting 1.7.0
is therefore the first action of the plan.

Verdicts: **CONFIRMED** the code does what the tester says · **PARTLY** true with a qualification · **NOT
REPRODUCED** the code does not do it · **BY DESIGN** deliberate, with the decision named · **FIXED AFTER v1.6.0**
already on `main`, unreleased.

## General (guided menu)

| # | Finding | Verdict | Evidence |
|---|---|---|---|
| G1 | The menu hides the other modes | CONFIRMED (partly mitigated) | `GuidedMode.java:43-63` prints nothing about `--help`, `docs`, `console` or `--json`; the only teaching aid is `Running: jrsctl …` before each job (`:227`) and a `docs recovery-runbook` hint on a non-zero exit (`:233`). `--help` itself is unaffected (`JrsctlCommand.java:24,35-52`). |
| G2 | No tab completion on paths | CONFIRMED, BY DESIGN for 1.6.0 | Input is `System.console().readLine` or a `BufferedReader` on stdin (`Prompter.java:40-57,97-106`). No line-editing library is a dependency; JLine is not on the §13.3 list, and the jlink image (ADR-0008, 14 modules) omits `jdk.internal.le`. ADR-0023 chose option A for 1.6.0 and names "1.6.0 testers still ask" as the trigger for the JLine spike. That trigger is now met. |
| G3 | `~` is not expanded | CONFIRMED | No expansion anywhere. `ConfigLoader.path()` (`core/.../ConfigLoader.java:516-522`) is the one coercion point for every config-borne path; menu prompts, picocli `Path` options, `--home` (`Bootstrap.java:74`, `LogFile.java:58-61`) and `JRSCTL_HOME` (`JrsctlHomeResolver.java:40`) bypass it. |
| G4 | Cursor cannot move while typing | CONFIRMED, same cause as G2 | Canonical-mode tty read; arrow keys echo as `^[[D`. |
| G5 | No documentation entry in the menu | CONFIRMED | Top-level menu `GuidedMode.java:55-64` has seven jobs; `docs`, `--explain`, `smoke`, `customizations`, `keys`, `secrets`, `selfcheck`, `console` are absent. |
| G6 | "List every setting" duplicates "Change one setting" | CONFIRMED | `GuidedMode.java:101-105`: option 2 runs `config keys` then asks for a key; option 3 runs `config keys` alone. |
| G7 | One change per trip through the menu | CONFIRMED | `settings()` is a straight `switch` (`:83-110`); only `run()` loops (`:53-78`). The loop-until-Enter pattern already exists in `existingFile()` (`:242-250`). |
| G8 | Text overflows and garbles | PARTLY | No cursor repositioning exists (`Ansi.java:18-23` is SGR only), so a wrapped line cannot corrupt a redraw. Two real hazards: `TextTable` pads to the widest cell with no width bound (`TextTable.java:25-48`), and `config keys` prints four columns over 39 keys (`ConfigCommand.java:343-351`), well past 80 columns; `TerminalMarkdown` renders `docs` and `--explain` at a fixed 100 columns unless `COLUMNS` is exported (`TerminalMarkdown.java:23,50-56`), which bash does not do by default. Nothing reads the terminal size. |
| G9 | Non-existent paths accepted in settings | CONFIRMED | `config set` and `--set` validate schema and `Path.of` syntax only (`ConfigCommand.java:212`, schema `config.schema.json:15-17,50,57` are bare strings). Only interactive `init` checks directories (`InitCommand.java:331`), and not `database.driverDir`. The menu's "detect the installation" prompt (`GuidedMode.java:90-99`) does not use the `existingDirectory()` helper that sits twenty lines below it. `/zugzug/whatever` is written and surfaces at the next `doctor`. |
| G10 | Writes fingerprints, sidecars and a JSON per task | PARTLY | Plan JSON and fingerprints are rows in `state.db` (spec §5.4; `Plans.java:94`, `PlanJson.java`), not files. What is written next to the operator's own files is the export sidecar `<archive>.jrsctl.json` (`Sidecar.java:17`, `WriteSidecar.java`, both strategies), required by spec §9.3 for the keystore-fingerprint check at import. Inside the home: `snapshots/<runId>/<step>/manifest.json` and marker files under `runs/<runId>/`, all pruned by retention (§5.6). |

## Hotfix

| # | Finding | Verdict | Evidence |
|---|---|---|---|
| H1 | Jaspersoft's package is refused over the manifest | CONFIRMED at v1.6.0; PARTLY at `main` | v1.6.0 has no official-package path: every ZIP goes to `HotfixBundle.extract` → `bundle has no manifest.json` → exit 7 (`HotfixBundle.java:117-119`, `BundleWorkspace.java:46-51`). ADR-0024 (`f8feb44`) is not in the tag. On `main`, `OfficialPackage.looksOfficial` (`:85-106`) needs an entry named exactly `readme.txt` and an inner zip named exactly `jasperserver[-pro].zip` or `js-install.zip`, case-sensitive, no directory prefix, no version in the name; and `Header.parse` refuses a readme without `Release Version:`/`Build version:`. Worse: `hotfix apply` short-circuits on `verify` and prints the generic "add the key with jrsctl keys add or pass --allow-unsigned" (`HotfixCommand.java:243-253`), so the official-package sentence in `DefaultHotfixOperations.java:198-208` is unreachable from the CLI; and the menu applies only when `verify` exits 0 and never passes `--allow-unsigned` (`GuidedMode.java:165-169`), so **through the menu an official package can never be applied, at v1.6.0 or on main**. Latent: `OfficialPackage` validates payload paths with `HotfixPaths.pathProblems` but the derived bundle is re-read through `HotfixBundle.ENTRY_NAME = [A-Za-z0-9._\-/]+` (`HotfixBundle.java:45`), so a package with a space or `+` in a path converts and then fails as "cannot read bundle". |
| H2 | `manifest.json` is never explained | CONFIRMED | The only `--help` mention is the hidden `hotfix build` (`HotfixCommand.java:69,79`). Error text introduces the term at failure time (`HotfixBundle.java:118,128`). The menu says nothing about manifests, official packages or `--allow-unsigned`. |
| H3 | Authoring docs dwarf the customer steps | CONFIRMED | Author-only material 280 lines (`docs/hotfix-authoring.md` 269 + `hotfix build` 11) against 18 customer lines (operator guide "Official Jaspersoft hotfix packages" 11 + README 7), and at v1.6.0 the customer lines were 0. `--explain hotfix` on `main` already excludes the hidden `build` and includes the official-package block (`Explain.java:79-124,143`), so that half is fixed. |
| H4 | Manifests, signing and the ledger are inventions | BY DESIGN, and the customer-facing promise is not yet delivered | Spec §8.1 defines the bundle; per-file hashes are what make per-file snapshot, ownership checks and per-file rollback possible (§8.2-8.3), which is why ADR-0024 derives a bundle from a vendor package instead of running a second engine. ADR-0024 §Decision 5 keeps `--allow-unsigned` mandatory. The tester's experience says the flag and its message are the residue that still shows. |
| H5 | "Remove an installed hotfix" is meaningless for cumulative hotfixes | PARTLY, BY DESIGN | `hotfix rollback <id>` restores the files the hotfix replaced and removes the ones it added, LIFO-constrained with `--cascade` (spec §8.3; `RollbackChain`, `RollbackSteps`). Meaningful for a cumulative package too (it returns the server to the pre-hotfix state), but the menu label does not say so and offers no `--cascade` (`GuidedMode.java:171-174`). |
| H6 | A ledger with no way to fill it | CONFIRMED | Only a `hotfix apply` run writes an `INSTALLED` row (`HotfixRecordSteps.java:96-110`); rollback and upgrade update rows. There is no way to record a hotfix applied by hand or before jrsctl existed, so a new install lists `no hotfixes recorded`. An official package applied on `main` does appear (`OfficialHotfixTest.java:166-167`). |

## Upgrade

| # | Finding | Verdict | Evidence |
|---|---|---|---|
| U1 | The plan is strict and cannot be altered | PARTLY, mostly BY DESIGN | Variation points are `--mode`, `--reapply-hotfixes`, `--tomcat-dir` (post-tag), `--plan`, `--rollback-all` (`UpgradeCommand.java:47-99`). Spec §0 "plan then apply", §6.2 fingerprint and §10.2's fixed phase list make the plan immutable on purpose. The concrete need behind the complaint is U5b. |
| U2 | Stops, exports, starts, then stops again | CONFIRMED at v1.6.0 for both modes; FIXED AFTER v1.6.0 for newdb; BY DESIGN for samedb | v1.6.0 `DefaultUpgradeOperations.java:164-179` had no mode branch. `main` `:242-273`: newdb exports once, after the stop (ADR-0025); samedb keeps the export in the backup phase with a restart because the script migrates in place and the export is only a rollback aid. |
| U3 | The user home is the default temp storage and cannot be configured | PARTLY | Home precedence is `--home`, `JRSCTL_HOME`, `/var/lib/jrsctl` (`%ProgramData%\jrsctl`), then `~/.jrsctl` only when the system home does not exist (`JrsctlHomeResolver.java:21-53`, `DefaultHome.java:46-71`). Documented in the operator guide (`:7,43,80,98`) but never surfaced by `init`, the menu or `upgrade --help`. Real defects: `full-export` has no free-space precheck (`BackupSteps.java:131-150`) while `backup-webapp` has one (`:342-358`); `doctor disk` probes `server.installDir`'s volume, not the home's (`LocalChecks.java:271-295`); upgrade snapshot sets are never pruned (`docs/operator-guide.md:430`). There is no `backups.dir` key (`config.schema.json:111-116`). |
| U4 | Could not complete the upgrade | — | Consequence of U3. |
| U5a | samedb 9 → 10 should be possible | NOT REPRODUCED for v1.6.0; correct on `main` | v1.6.0's matrix had no mode dimension and refused nothing for 9→10 samedb, including 9.0→10.1 samedb, which the vendor does not offer. `main` allows 9.0→10.0.x samedb and refuses 9.0→10.1 samedb with exit 6 naming newdb (`matrix.yaml:98-99`, `UpgradePaths.java`), matching upgrade guide 10.1 pp.10-11. A v1.6.0 refusal, if seen, came from `vendor.javaHome` (single Java major per line in matrix v1). `docs/compatibility.md:15-17,43-45` is stale against matrix v2. |
| U5b | Upgrade with an export taken earlier | CONFIRMED | No option (`UpgradeCommand.java:47-99`, `UpgradeOptions`). The export path is derived from the run id (`VendorSteps.java:458-466`, `SnapshotSet.java:30-40`); `FullExport` reuses a file only at that exact path with its `.sha256` (`BackupSteps.java:156-162`), and the run id is not known in advance. |

## Export

| # | Finding | Verdict | Evidence |
|---|---|---|---|
| E1 | Two whole-repository exports with look-alike labels | PARTLY | `GuidedMode.java:114-116`: "The whole repository (the server keeps running)" runs `export --out` (REST when the probe passes), "Everything, with users, roles and settings" runs `--full-server` (vendor `js-export`). Different words, but neither names its mechanism or cost. The CLI has `--strategy rest|vendor` (`ExportCommand.java:76-80`); the menu never passes it. |
| E2 | The third option also keeps the server running | CONFIRMED (label misleading) | ADR-0021: `export` never stops the service by default with either strategy; `--stop-service` opts in (`VendorCliStrategy.java:72-79`). Only option 1's label says "keeps running", which implies option 3 does not; the menu cannot ask for `--stop-service`. |
| E3 | A missing folder is not checked and the failure says nothing | CONFIRMED | No URI check on either path (`StartExport.java:71-81`, `RunJsExport.java:61-74`, `DefaultExportImportOperations.java:80-133`), though `JrsAdapter.listFolder` exists (`RestJrsAdapter.java:743-756`). An unknown URI usually **exits 0 with an archive holding only `resources/`** (spec §9.4 line 531, `RestoreFromPreImportSnapshot.holdsNoResources`). When the task does fail, `Wire.AsyncState` has no `errorDescriptor` field, so the operator reads `export failed without a message` (`PollExport.java:102-106,122-126`). |
| E4 | No choice of export key | CONFIRMED | No key field on `ExportRequest`, `ExportOptions` or the REST body (`RestJrsAdapter.java:495-500`); `VendorFlags.KEYALIAS` is a dead constant. REST reference 10.1 p.110 documents `keyAlias` on export; review §4.1 already recommends `export --portable` / `--key-alias`. The sidecar stores a fingerprint only, no key material. |
| E5 | No organization export | CONFIRMED | Export flags are `--uri`, `--users-roles`, four event/settings switches, `--full-server`, `--stop-service`, `--strategy`, `--out` (`ExportCommand.java:43-90`); `users`/`roles` are always sent empty. Review §4.2 already calls this blocking for multi-tenant servers. |

## Import

| # | Finding | Verdict | Evidence |
|---|---|---|---|
| I1 | No choice of import key | CONFIRMED | REST import query has no `keyAlias`/`secretKey` (`RestJrsAdapter.java:554-573`); the vendor path only swaps the whole keystore with `--source-keystore` (`VendorTools.java:239-250`). REST reference 10.1 p.117 documents `keyAlias` on import. |
| I2 | The "pre-import snapshot" exports the whole repository | PARTLY | Scope comes from the archive's sidecar; without one, or with `--update` at the root, it falls back to `/` and to `--everything` (`DefaultExportImportOperations.java:257-291`). The menu prompts for an arbitrary zip and says nothing about the snapshot (`GuidedMode.java:142-152`); the plan warning exists (`:298-303`). Under the vendor strategy the snapshot still stops the service (ADR-0021 point 4). |
| I3 | Rollback re-imports the snapshot and leaves added resources behind | CONFIRMED, BY DESIGN, documented | `RestoreFromPreImportSnapshot` is a plain re-import with `update=true`; `BEST_EFFORT_WARNING` is on every import plan; spec §9.4 line 530, operator guide `:115,:293`, recovery runbook `:56`. A true rollback would need a resource listing of the target subtree before the import and a delete of the delta. |
| I4 | Cannot pass skip-themes, organization or a granular selection | PARTLY on the CLI, CONFIRMED in the menu | `--skip-themes` exists and is sent on both strategies (`ImportCommand.java:67-68`), but the guided menu's restore flow passes nothing except `--update` (`GuidedMode.java:142-152`), so from the menu, which the tester was using, no import option is reachable. `organization`, `mergeOrganization` and any per-resource selection exist on no layer; the operator guide's own error table sends the operator to the raw vendor tools for `import.organizations.not.match` (`:609`). |

## Server health

| # | Finding | Verdict | Evidence |
|---|---|---|---|
| D1 | Doctor requires the password | CONFIRMED, two causes | `LocalChecks.secrets` resolves every configured secret to prove it resolves, which unlocks `secrets.enc` and prompts for the passphrase (`LocalChecks.java:88-110`, `EncryptedSecretStore.java:217-220`), although `Bootstrap` deliberately avoids that prompt at startup. `RestJrsAdapterFactory.connect` resolves the admin password before any request (`:31-33`), so even the serverInfo probe needs it. Without the password: `secrets` FAIL, `server` FAIL as "unexpected SecretException … check server.baseUrl" (`ServerProbe.java:94-101`), and six items SKIP as "server unreachable" although it is reachable. Fourteen of the 24 items are local and need nothing. No test covers the missing-password case. |
| D2 | The failure is one line followed by many green ones | CONFIRMED | Items are already sorted FAIL, WARN, PASS, SKIP (`Report.java:47-51`), which is what produces the symptom on a short terminal. Only the status cell is coloured (`ReportPrinter.java:21-37`, `Ansi.java:88-107`); the remediation under a FAIL is dimmed; the summary `21 pass 0 warn 1 fail 2 skip` is uncoloured and does not name the failing item; the menu's closing line names the exit code only (`GuidedMode.java:230-233`). |

## Cross-cutting documentation gaps found on the way

- Guided mode has no section in `docs/spec.md`; the changelog cites §17, which is "Documentation deliverables".
- Spec §5.1 says the home falls back to `~/.jrsctl` "if not writable"; the code falls back only when the system home does not exist and refuses when it exists but is unwritable (`JrsctlHomeResolver.java:43-52`).
- `JrsctlHome` Javadoc says nothing is written outside the home; the export archive and its sidecar are.
- `docs/compatibility.md` still describes matrix v1.

## Second round of feedback (2026-09-18)

The tester read the verdicts above and pushed back on four "by design" items. Each changed the plan:

- **The sidecar's purpose was not explained.** It scopes the pre-import snapshot and catches a keystore mismatch before the server does; the plan now adds `--no-sidecar` and a closing line on `export` that names the second file (D16).
- **An upgrade is not always linear** (an archive from another environment, a different keystore). `upgrade --export` now accepts an archive from anywhere with a warning instead of a refusal, and decrypts it through the vendor's own routes: an alias in the staged buildomatic properties, or the source keystore staged and then adopted (D9, Task 7). Checked on the local 10.0.0 buildomatic: the newdb script takes only the export path and one option word; `import-export.xml` reads `deprecatedImportExportEncSecret.keyalias`/`.keypass` from the properties.
- **The rollback is questionable.** For newdb it was: files only, against a rebuilt database. `upgrade rollback --restore-database` rebuilds the old database from the point-B export with the restored old buildomatic (D18, Task 7b, ADR-0029).
- **Asked for a backup, then took one.** For newdb the two are the same thing once the rollback above exists, so the question goes for newdb and stays for samedb with the reason (D19, Task 7b). The vendor's `test` option becomes `upgrade --test` (D17, Task 7c).
- **`--skip-themes`** exists on the CLI, but the tester was in the menu, where nothing but `--update` is reachable (I4 above, Task 8).

## Status of each finding (2026-09-18, after the plan's code tasks)

| Finding | Status |
|---|---|
| G1 | fixed by Task 8 (PR #95): footer names `--help`, `console` and `--json` |
| G2, G4 | handed off (Task 14 issue: ADR-0023 JLine spike) |
| G3 | fixed by Task 9 (PR #96) |
| G5 | fixed by Task 8 (PR #95): entry 8 lists the documentation |
| G6, G7 | fixed by Task 8 (PR #95): one settings entry, changed in a loop |
| G8 | fixed by Task 10 (PR #97): output wraps at the terminal width |
| G9 | fixed by Task 9 (PR #96): a missing directory is refused when written |
| G10 | by design, documented (D16, Task 13): the sidecar's purpose is stated on `export` |
| H1 | fixed in 1.7.0 (ADR-0024) and by Task 1 (PR #85, ADR-0027) |
| H2, H3 | fixed by Task 2 (PR #86): the customer path first, authoring last |
| H4 | by design (D2); the customer-facing path no longer names manifests or signatures |
| H5 | fixed by Task 2 (PR #86): the rollback entry says what it does and offers `--cascade` |
| H6 | fixed by Task 2 (PR #86) for the wording; the adopt command is handed off (Task 14 issue) |
| U1 | by design (spec §0, §6.2), stated on `upgrade` (Task 13); the concrete needs are Tasks 7, 7c |
| U2 | fixed in 1.7.0 (ADR-0025) |
| U3 | fixed by Task 6 (PR #90): the space is budgeted and the home is shown; a `backups.dir` key is handed off (Task 14 issue) |
| U4 | consequence of U3 |
| U5a | fixed in 1.7.0 (matrix v2 modes) |
| U5b | fixed by Task 7 (PR #91, ADR-0028) |
| Second round: rollback, backup question, rehearsal | fixed by Tasks 7b and 7c (PRs #92, #94; ADR-0029) |
| E1, E2 | fixed by Task 8 (PR #95): entries labelled by mechanism, stop question for the vendor export |
| E3 | fixed by Task 5 (PR #89) |
| E4 | fixed by Task 11 (PR #98) |
| E5 | fixed by Task 12 |
| I1 | fixed by Task 11 (PR #98) |
| I2 | fixed by Task 8 (PR #95): the restore entry says what the snapshot is; the scope comes from the sidecar (Task 5) |
| I3 | by design, documented in four places; a true rollback is handed off (Task 14 issue) |
| I4 | fixed by Task 8 (PR #95) for the menu and by Task 12 for the organisation |
| D1 | fixed by Task 3 (PR #87) |
| D2 | fixed by Task 4 (PR #88) |
