# Phase 3 acceptance — Hotfix

Run: `scripts\mvn.cmd verify -Dphase=3`.

Criteria (spec §14, Phase 3). Unit suites in `ops` (`DefaultHotfixOperations`) and `app` (`HotfixCommandTest`, `RunsCommandTest`, `KeysCommandTest`, `SecretsCommandTest`) cover the detail; `Phase3HotfixTest` proves the packaged jar end to end against a fake Tomcat layout and a WireMock JasperReports Server 8.2.0 PRO. Its steps share one home and one bundle and therefore run in order:

1. `jrsctl keys generate customer --private-out <tmp>/customer.key` writes an owner-only private key and trusts the public key; `jrsctl hotfix build <fixture> --key file:<tmp>/customer.key --out <bundle>` signs a fixture manifest (`JRS-8.2.0-HF-0001`, applies `>=8.2.0 <8.3.0` / PRO, `restart: none`, one `replace` of `webapps/jasperserver-pro/scripts/jrsctl-fix.js`, one `add` of `webapps/jasperserver-pro/jrsctl/added.txt`, `rollback: snapshot`, a `fileExists` precheck, no http checks). The ZIP contains `manifest.json`, `SIGNATURE` and the payload.
2. `jrsctl hotfix verify <bundle>` exits 0; a copy with one payload byte flipped exits 7.
3. `jrsctl hotfix apply <bundle> --plan` exits 0, prints the phases `verify`, `backup`, `apply`, `record` and "nothing has changed"; the target files are untouched.
4. `jrsctl hotfix apply <bundle> --yes` exits 0; the replaced file carries the new hash, the added file exists, `hotfix list --json` shows `INSTALLED` with 2 files, `runs list --json` shows a `SUCCEEDED` `hotfix.apply` run and `runs show <id>` prints its transitions.
5. While the test JVM holds `RunLock` on the same home, `hotfix apply ... --yes` exits 9 and names the holder run id and pid (cross-process lock contention, deferred from Phase 1).
6. `jrsctl hotfix rollback JRS-8.2.0-HF-0001 --yes` exits 0; the original hash is back, the added file is gone, the hotfix is `ROLLED_BACK`.
7. A copy without `SIGNATURE` is refused by `verify` and `apply --yes` with exit 7 and nothing changes; `apply --yes --allow-unsigned` applies it (the override is audited).

Not covered here (release gates or later phases): SQL scripts (no database on the build machine), `restart: required` with a real service controller, cascade rollback across several hotfixes (unit-tested in `ops`), Ctrl-C cancellation (Phase 8 crash-injection suite).
