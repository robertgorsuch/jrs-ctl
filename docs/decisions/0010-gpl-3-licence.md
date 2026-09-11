# ADR-0010: jrsctl is licensed under GPL-3.0-only

Status: accepted, pending Actian legal review · Date: 2026-09-11 · Spec: §13.3, §20

## Context
The repository carried two licences. `LICENSE` (commit `ea4c870`, added through GitHub on 2026-09-10) is the GNU General Public License, version 3. The README's "License" section, generated with the first build and never reviewed, said "Copyright (c) 2026 Actian Corporation. All rights reserved. Licensed under the Apache License, Version 2.0." The root pom, the shipped `README.txt`, `LICENSE-THIRD-PARTY.txt` and `CONTRIBUTING.md` said nothing; the spec has no licence section. GitHub, the SBOM and everyone who cloned the repository saw GPL-3.0; anyone who read the README saw Apache-2.0 under an all-rights-reserved line that contradicts both. The portable image shipped the third-party notices but not the product's own licence text, which GPL-3.0 §4 and §5 require with every copy. Found as the licence contradiction in `docs/reviews/2026-09-10-codebase-assessment.md`.

## Decision
jrsctl is licensed under the GNU General Public License, version 3 only (SPDX `GPL-3.0-only`), the text in `LICENSE`. That file is the one deliberate act on record: the maintainer chose it, the repository has been published under it since, and every clone already received the code on those terms. "Or any later version" is not granted; widening to `GPL-3.0-or-later` is a one-line change to this ADR and the notices if the holder prefers it. The copyright line stays "Copyright (c) 2026 Actian Corporation", as the README and the vendor line already state. Contributions are accepted under the same licence, inbound equal to outbound; there is no contributor licence agreement.

Every place that names the licence names this one by its SPDX identifier: the README, the root pom (`<licenses>`, which the CycloneDX SBOM copies onto the root component), `CONTRIBUTING.md`, and the shipped `README.txt` and `LICENSE-THIRD-PARTY.txt`. The portable image carries `LICENSE` byte for byte beside `LICENSE-THIRD-PARTY.txt` (ADR-0008 layout amended). `Phase0SkeletonTest` fails when any of those files stops saying `GPL-3.0-only` or reintroduces "Apache License, Version 2.0" or "All rights reserved"; `Phase7DistributionTest` fails when the image lacks the licence text.

Bundled libraries that offer a choice of licence are used under their GPL-compatible arm and the notice says which: Jetty under Apache-2.0 rather than EPL-2.0, Logback under LGPL-2.1 rather than EPL-1.0. Apache-2.0, MIT and LGPL-2.1 are compatible with GPL-3.0; EPL-1.0 is not, so a future dependency licensed under EPL alone is refused by spec §13.3's approval rule. The jlink runtime is OpenJDK under GPLv2 with the Classpath Exception, shipped as a separate program under `runtime/` with its own `legal/` directory.

## Alternatives
Apache-2.0, as the README claimed, is a permissive licence that would let the tool be embedded in proprietary tooling without source obligations. It is workable with the same dependency set. It is not what was published, and adopting it now would mean the copyright holder relicensing code that is already public under GPL-3.0; that is the holder's decision to make, not a contradiction to paper over. Keeping both statements was not an option.

## Consequences
The repository says one thing everywhere, and two tests keep it so. No per-file licence headers are added: `LICENSE` and the pom govern, and 430 headers would be noise the tests do not need. The status "pending Actian legal review" is deliberate: this ADR records the maintainer's choice and makes the repository consistent with it. If Actian's legal review chooses differently, this ADR is superseded and the same five files, the pom, the image and the two tests change together.
