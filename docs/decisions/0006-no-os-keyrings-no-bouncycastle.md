# ADR-0006: No OS keyring integration and no BouncyCastle in v1

Status: accepted · Date: 2026-09-08 · Spec: §5.2, §11.1, §13.3

## Context
Draft 1.0 resolved `keyring:` secrets through Windows Credential Manager or Linux Secret Service, and listed BouncyCastle for Ed25519 and AES-GCM.

## Decision
Secret sources are `env:`, `file:` (permission-checked) and `enc:` (AES-GCM file with PBKDF2 key from a passphrase; non-interactive unlock via `JRSCTL_PASSPHRASE` or `--passphrase-file`). Ed25519 and AES-GCM use the JDK's built-in providers. BouncyCastle and JNA are not approved dependencies.

## Consequences
Windows Credential Manager needs JNA; Linux Secret Service needs a D-Bus session that headless servers lack. JDK 15+ provides Ed25519 natively, so the crypto dependency disappears and the jlink image shrinks. Keyring support can return later behind the same `SecretRef` interface.
