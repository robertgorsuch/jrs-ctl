#!/usr/bin/env sh
# Signs the release artifacts in a directory (default dist/target) with the Jaspersoft publisher key.
# Spec section 0 rule 11 / section 11.1: runs in CI only. Without JRSCTL_SIGNING_KEY it prints a
# notice and exits 0 so local builds stay unsigned and green.
# JRSCTL_SIGNING_KEY = Base64 PKCS#8 Ed25519 private key (the format of `jrsctl keys generate`).
set -eu
if [ -z "${JRSCTL_SIGNING_KEY:-}" ]; then
  echo "signing skipped: no key (CI only)"
  exit 0
fi
dir="${1:-$(dirname "$0")/../dist/target}"
java_home="${JAVA_HOME:-${JRSCTL_JDK:-}}"
if [ -n "$java_home" ]; then
  java="$java_home/bin/java"
else
  java=java
fi
exec "$java" "$(dirname "$0")/SignArtifacts.java" "$dir"
