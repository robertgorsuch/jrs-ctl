# Security policy

## Status

jrsctl is actively maintained; the current release is 2.5.0. Report a vulnerability privately
through GitHub's private vulnerability reporting for this repository (Security tab, "Report a
vulnerability"), not as a public issue; `docs/security.md`, "Reporting a vulnerability", says what
to include. `docs/security.md` also describes the security model: secrets, redaction, least
privilege, key management.

## Verifying a release download

Every release from 1.0.1 on carries, next to each archive, a `.sha256` sidecar and a `.sig`
signature; the SBOM and both `.sha256` files are signed too. The `v1.0.0` pre-release predates the
key and has no signatures.

### Checksum

- Windows: `certutil -hashfile jrsctl-<version>-windows-x64.zip SHA256` and compare with the first
  field of `jrsctl-<version>-windows-x64.zip.sha256`.
- Linux: `sha256sum -c jrsctl-<version>-linux-x64.tar.gz.sha256`.

After unpacking, `sha256sum -c MANIFEST.sha256` (Linux) checks every file in the directory.

### Signature

The release signing key is Ed25519, fingerprint `245731f29b662027`. Its public half is
`core/src/main/resources/keys/jaspersoft-publisher.pub` in this repository and is bundled in every
release; `jrsctl keys list` prints it with its fingerprint. The fingerprint is the first 16 hex
digits of the SHA-256 of the decoded key.

A `.sig` file is one line: the Base64 Ed25519 signature over the raw bytes of the file it names.
The public key file is a Base64 X.509 (SubjectPublicKeyInfo) key. Any Ed25519 tool can check it;
with OpenSSL 3.0 or later (Git for Windows includes one):

```sh
openssl base64 -d -A -in jaspersoft-publisher.pub -out publisher.der
sha256sum publisher.der          # must start with 245731f29b662027
openssl base64 -d -A -in jrsctl-<version>-linux-x64.tar.gz.sig -out archive.sig
openssl pkeyutl -verify -pubin -inkey publisher.der -keyform DER -rawin \
  -in jrsctl-<version>-linux-x64.tar.gz -sigfile archive.sig
```

The last command prints `Signature Verified Successfully` and exits 0; any other result means the
file did not come from the release job. Take the public key from this repository or from a
release you already trust, not from the same download you are checking.

jrsctl has no command that verifies its own release archives. `jrsctl hotfix verify` checks hotfix
bundles, not releases.
