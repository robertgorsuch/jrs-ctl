package com.jaspersoft.jrsctl.jrs.api;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Where the server keystore lives and its fingerprint (spec §9.3). Invariant: {@code present} is
 * false when the server has no {@code KEYSTORE_ENCRYPTION} capability or the files cannot be read
 * from {@code server.runAsUser}'s home; the fingerprint is the SHA-256 of {@code .jrsks}.
 */
public record KeystoreInfo(
    boolean present,
    Optional<Path> keystoreFile,
    Optional<Path> propertiesFile,
    Optional<String> fingerprint,
    Optional<String> reason) {

  public static KeystoreInfo absent(String reason) {
    return new KeystoreInfo(
        false, Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(reason));
  }
}
