# ADR-0020: The source keystore password stays on the vendor command line

Status: accepted, 2026-09-15. Amends spec §11 (security). Issue #51.

## Context

`jrsctl import --source-keystore <path> --source-keystore-password-ref <ref>` runs the vendor
`js-import` with `--keystore <path> --storepass <password>`. `VendorTools.keystoreArgs` turns the
password into a `String` argument of the child process. The value is registered with the redactor
before the command line is logged, so jrsctl's own output never shows it, but while `js-import`
runs any local account that can list processes and their command lines can read it.

The issue also named the form-login password, built through `new String(char[])`. That part is
fixed separately: `FormBodies` encodes the body from the `char[]` (same issue, no ADR needed).

The 10.0.0 buildomatic (`conf_source/iePro/applicationContext-export-import.xml`, checked on
2026-09-15 against the installation on the development host) lists `storepass` only as a command
argument of the import tool. There is no option to read it from a file, from standard input, or
from an environment variable, and `keystore.init.properties` configures the target server's own
keystore, not the source keystore being imported.

## Decision

1. The password keeps reaching `js-import` as `--storepass`, the only interface the vendor offers.
2. It is still registered with the redactor first, and still read only from an `env:`, `file:` or
   `enc:` reference, never given on jrsctl's own command line.
3. The operator guide's `import` section and `docs/security.md` state the exposure: the value is
   visible in the process list for the duration of the vendor import.
4. Revisit when a vendor release accepts the password from a file or the environment.

## Alternatives

- **Write a temporary owner-only properties file.** Not possible: the import tool reads the
  source keystore password from nowhere but its arguments.
- **Refuse `--source-keystore`.** Rejected: importing an archive exported with another server's
  keystore has no other supported path, and the operator would type the same password on the
  vendor command line by hand.

## Consequences

- On a host that other people log in to, an operator should run an import with
  `--source-keystore` from a session no other user can inspect, or rotate that keystore password
  afterwards.
- Only the vendor strategy with `--source-keystore` is affected; REST imports and every other
  command pass no secret on a command line.
