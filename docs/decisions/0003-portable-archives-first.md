# ADR-0003: Portable archives are the v1 distribution; installers deferred

Status: accepted · Date: 2026-09-08 · Spec: §1.3, §14 Phase 7

## Context
Draft 1.0 required MSI, EXE, DEB, RPM and a portable ZIP, plus a reproducible-build check.

## Decision
Phase 7 delivers a portable ZIP (Windows) and tar.gz (Linux) containing the jlink runtime, with SHA-256 checksums and an SBOM. Native installers and reproducible builds are deferred to a post-v1 phase.

## Consequences
Air-gapped customers need the portable archive most and can deploy it without elevated installers. jlink and jpackage embed timestamps, so reproducibility would have been a long investigation with little payoff; signed checksums give the integrity guarantee operators actually verify.
