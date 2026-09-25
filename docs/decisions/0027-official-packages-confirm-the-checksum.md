# ADR-0027: An official package is accepted on a confirmed checksum, not a flag

Status: accepted, 2026-09-18. Amends ADR-0024 §Decision 5.

## Context

ADR-0024 made `hotfix apply` take an official Jaspersoft cumulative hotfix as support publishes it, deriving a jrsctl bundle from it so the ordinary snapshot, staging and per-file rollback cover it. Because the package carries no jrsctl signature, §Decision 5 kept `--allow-unsigned` mandatory and audited, and the plan printed the package's SHA-256 for comparison with the support portal.

The second field test of v1.6.0 (`docs/reviews/2026-09-18-field-test-2-review.md`, H1, H2, H4) showed what that residue does to the one job a customer has. The command line printed "add the key with `jrsctl keys add` or pass --allow-unsigned", because `HotfixCommand.Apply` returned before the official-package sentence in `DefaultHotfixOperations` could be reached. The guided menu could never apply an official package at all: it ran `hotfix verify` first and applied only on exit 0, and an unsigned package never exits 0 there. The tester's words: the manifest, signing and key vocabulary "is adding extra complexity where we expect things to be as simple as doors".

The same test showed the detector was narrower than support's packages: exact, case-sensitive names for the readme and the inner archives, no directory prefix, no version suffix, and a bundle reader that refused a space or a `+` in a path the package had converted without complaint.

## Decision

1. **Interactively, the operator confirms the checksum instead of passing a flag.** `hotfix apply <package.zip>` prints `Official Jaspersoft package <id>, SHA-256 <hex>.` and asks `Does this match the checksum on the support portal? [y/N]`. A yes is audited as `hotfix.apply.official-confirmed` with the id, hash and path, and the plan proceeds exactly as with `--allow-unsigned`. A no, an empty answer or end of input refuses with exit 7 and nothing changes.
2. **Without a terminal the flag stays.** `--yes`, `--non-interactive` and `--json` never prompt; there an official package without `--allow-unsigned` is refused with exit 7, and the message names the package, its SHA-256 and the flag, never a signing key.
3. **`hotfix verify` reports an official package ok** when its hashes and applicability pass; it says "official Jaspersoft package, no jrsctl signature" and exits 0. Exit 7 keeps its meaning of a signature that failed.
4. **The guided menu runs `hotfix apply` directly.** Apply verifies, shows the plan and asks its two questions; the separate verify gate is gone.
5. **Detection is by shape on base names, case-insensitively.** The readme may be `README.TXT`; the package may sit under one directory of prefix; the inner archive may carry a version or build suffix (`jasperserver-pro-10.0.0-hotfix.zip`, `js-install-10.0.0.zip`); the webapp may be shipped unpacked under `jasperserver[-pro]/` with its own readme. A `manifest.json` at the top still makes the file a jrsctl bundle.
6. **The bundle reader's entry-name rule is a denylist:** traversal, absolute and drive-relative names and control characters are refused; spaces, `+`, parentheses and non-ASCII are allowed, since the vendor ships them and the derived bundle is read back through the same reader.
7. **A ZIP that is neither shape is refused with exit 2** naming both: "neither an official Jaspersoft hotfix package (readme.txt beside jasperserver[-pro].zip, js-install.zip or an unpacked jasperserver[-pro]/ tree) nor a jrsctl hotfix bundle (manifest.json at the root)".

## Consequences

- A customer applying a vendor hotfix from a terminal never meets the words key, signature or manifest. The only question is the one support's own instructions ask them to answer.
- The trust decision is unchanged in substance: ADR-0024 refused to trust a package on shape alone, and still does. What the flag expressed, a human vouching for the download, is now expressed by the answer to a question, and audited the same way.
- `Confirm.ask` reads through `Prompter`'s one reader, so two questions in one process (the checksum, then "Run this plan?") read the same stdin. A second buffered reader over the same stream would have found the bytes the first buffered ahead already gone.
- Unattended runs are unchanged, so schedulers that pass `--allow-unsigned` keep working.

## Alternatives

- **Keep the flag and fix the messages.** Rejected: the tester had already read the message; the objection was to the concept, not the wording.
- **Trust the package on shape, no question.** Rejected, as in ADR-0024: it would install unverified code from any ZIP in that layout.
- **Verify the package against a checksum jrsctl fetches from the support portal.** Not possible: the portal needs a login and publishes no machine-readable checksum list.

## Amendment (#159)

A confirmed checksum used to be carried into the plan as `--allow-unsigned`, so the plan warning, the `verify-signature` step and the run's audit row all named a flag the operator never gave. The plan options now record how an unsigned package was accepted (refused, checksum confirmed, or `--allow-unsigned`); the plan says *checksum confirmed by the operator against the support portal*, and the run audits `hotfix.checksum-confirmed` instead of `hotfix.allow-unsigned`. The stored arguments `runs recover` rebuilds from carry the same value, and arguments stored by 2.0.0 and earlier are read as before. Every audit row, from a command or from a step, now names the operating-system account that ran jrsctl (`AuditActor`), where commands used to write `operator`.
