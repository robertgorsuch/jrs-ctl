package com.jaspersoft.jrsctl.app.console;

import com.jaspersoft.jrsctl.core.config.Config;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.eclipse.jetty.util.ssl.SslContextFactory;

/**
 * Turns {@code console.tls} into a Jetty {@link SslContextFactory} using nothing but the JDK (spec
 * §11.2, §13.3: no BouncyCastle). Invariants: {@code certPath} is one or more PEM {@code
 * CERTIFICATE} blocks, leaf first; {@code keyPath} is an unencrypted PKCS#8 PEM ({@code BEGIN
 * PRIVATE KEY}; RSA, EC or Ed25519), the form every current OpenSSL and every ACME client writes;
 * the in-memory keystore is protected by a random password that never leaves this class; a PKCS#1
 * ({@code BEGIN RSA PRIVATE KEY}) or encrypted key is refused with the conversion command in the
 * message rather than being parsed here.
 */
final class ConsoleTls {

  private static final String ALIAS = "console";
  private static final String PKCS8_HEADER = "-----BEGIN PRIVATE KEY-----";

  private ConsoleTls() {}

  static SslContextFactory.Server sslContextFactory(Config.Tls tls) {
    Path certPath =
        tls.certPath()
            .orElseThrow(
                () ->
                    new ConsoleRefusedException("console.tls.enabled needs console.tls.certPath"));
    Path keyPath =
        tls.keyPath()
            .orElseThrow(
                () -> new ConsoleRefusedException("console.tls.enabled needs console.tls.keyPath"));
    char[] password = randomPassword();
    try {
      KeyStore store = KeyStore.getInstance("PKCS12");
      store.load(null, password);
      store.setKeyEntry(ALIAS, readPkcs8(keyPath), password, readChain(certPath));
      SslContextFactory.Server factory = new SslContextFactory.Server();
      factory.setKeyStore(store);
      factory.setKeyStorePassword(new String(password));
      factory.setKeyManagerPassword(new String(password));
      return factory;
    } catch (IOException | GeneralSecurityException e) {
      throw new ConsoleRefusedException("cannot load console TLS material: " + e.getMessage(), e);
    }
  }

  private static Certificate[] readChain(Path certPath)
      throws IOException, GeneralSecurityException {
    List<Certificate> chain = new ArrayList<>();
    CertificateFactory certificates = CertificateFactory.getInstance("X.509");
    try (InputStream in = Files.newInputStream(certPath)) {
      chain.addAll(certificates.generateCertificates(in));
    }
    if (chain.isEmpty()) {
      throw new GeneralSecurityException("no CERTIFICATE block in " + certPath);
    }
    return chain.toArray(new Certificate[0]);
  }

  static PrivateKey readPkcs8(Path keyPath) throws IOException, GeneralSecurityException {
    StringBuilder base64 = new StringBuilder();
    boolean inBlock = false;
    try (BufferedReader reader = Files.newBufferedReader(keyPath, StandardCharsets.US_ASCII)) {
      String line;
      while ((line = reader.readLine()) != null) {
        String trimmed = line.strip();
        if (trimmed.startsWith("-----BEGIN ")) {
          if (!trimmed.equals(PKCS8_HEADER)) {
            throw new GeneralSecurityException(
                keyPath
                    + " holds "
                    + trimmed
                    + "; only an unencrypted PKCS#8 key (BEGIN PRIVATE KEY) is supported, convert"
                    + " with `openssl pkcs8 -topk8 -nocrypt -in key.pem -out key.pk8`");
          }
          inBlock = true;
        } else if (trimmed.startsWith("-----END ")) {
          break;
        } else if (inBlock) {
          base64.append(trimmed);
        }
      }
    }
    if (base64.isEmpty()) {
      throw new GeneralSecurityException("no PRIVATE KEY block in " + keyPath);
    }
    PKCS8EncodedKeySpec spec =
        new PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64.toString()));
    InvalidKeySpecException last = null;
    for (String algorithm : List.of("RSA", "EC", "Ed25519")) {
      try {
        return KeyFactory.getInstance(algorithm).generatePrivate(spec);
      } catch (InvalidKeySpecException e) {
        last = e;
      }
    }
    throw new GeneralSecurityException("unsupported private key in " + keyPath, last);
  }

  private static char[] randomPassword() {
    byte[] bytes = new byte[24];
    new SecureRandom().nextBytes(bytes);
    return Base64.getEncoder().encodeToString(bytes).toCharArray();
  }
}
