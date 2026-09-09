# ADR-0004: One capability-driven REST adapter instead of per-version adapters

Status: accepted · Date: 2026-09-08 · Spec: §7.2

## Context
Draft 1.0 defined `Jrs7Adapter`, `Jrs8Adapter`, `Jrs9Adapter`, `Jrs10Adapter` and a `VendorCliAdapter` implementing the same interface.

## Decision
Implement a single `RestJrsAdapter` whose behaviour is selected by probed capabilities (`EXPORT_ASYNC`, `IMPORT_ASYNC`, `KEYSTORE_ENCRYPTION`, `ORGS`, `TOKEN_AUTH`, `PREAUTH`, `REST_LOGIN`). Vendor CLI tooling becomes an `ExportImportStrategy`, not an adapter. Version-specific quirk classes are added only when the nightly contract suite demonstrates drift, each with its own ADR.

## Consequences
The REST v2 export/import API is stable from 7.1 through 10.x; what varies is authentication and keystore presence, both already probed. Four speculative classes were code to write and test before any drift was observed. The vendor strategy could never satisfy `login()` or `health()`, so separating it removes an interface-segregation violation.
