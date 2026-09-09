package com.jaspersoft.jrsctl.app.console;

import com.jaspersoft.jrsctl.app.OwnerOnlyFiles;
import com.jaspersoft.jrsctl.core.JrsctlHome;
import com.jaspersoft.jrsctl.core.platform.Platform;
import com.jaspersoft.jrsctl.core.redact.Redactor;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The per-launch bearer token of the console (spec §11.2). Invariants: 32 bytes from {@link
 * SecureRandom}, rendered as unpadded base64url; written once to {@code $JRSCTL_HOME/console.token}
 * with owner-only permissions (an older file is replaced, never appended to); registered with the
 * redactor before it is returned so no log or response can carry it; compared in constant time; the
 * file is deleted when the token is {@linkplain #close() closed}, which the console's shutdown hook
 * guarantees. {@link #text()} exists only so the launch URL can be printed once.
 */
public final class ConsoleToken implements AutoCloseable {

  private static final Logger LOG = LoggerFactory.getLogger(ConsoleToken.class);
  private static final int BYTES = 32;

  private final byte[] value;
  private final Path file;

  private ConsoleToken(byte[] value, Path file) {
    this.value = value;
    this.file = file;
  }

  /** Generates, persists and registers a fresh token. */
  public static ConsoleToken issue(JrsctlHome home, Platform platform, Redactor redactor)
      throws IOException {
    Objects.requireNonNull(home, "home");
    Objects.requireNonNull(platform, "platform");
    Objects.requireNonNull(redactor, "redactor");
    byte[] random = new byte[BYTES];
    new SecureRandom().nextBytes(random);
    String text = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
    Path target = home.consoleToken();
    Files.deleteIfExists(target);
    OwnerOnlyFiles.write(platform, target, text + System.lineSeparator());
    redactor.register(text);
    return new ConsoleToken(text.getBytes(StandardCharsets.UTF_8), target);
  }

  /** The token text, for the launch URL only. */
  public String text() {
    return new String(value, StandardCharsets.UTF_8);
  }

  public Path file() {
    return file;
  }

  /** Constant-time comparison with a presented token. */
  public boolean matches(String presented) {
    if (presented == null) {
      return false;
    }
    return MessageDigest.isEqual(value, presented.getBytes(StandardCharsets.UTF_8));
  }

  @Override
  public void close() {
    try {
      Files.deleteIfExists(file);
    } catch (IOException e) {
      LOG.warn("could not delete {}: {}", file, e.getMessage());
    }
  }
}
