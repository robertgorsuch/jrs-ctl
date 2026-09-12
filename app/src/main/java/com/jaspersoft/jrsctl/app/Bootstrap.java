package com.jaspersoft.jrsctl.app;

import com.jaspersoft.jrsctl.core.JrsctlHome;
import com.jaspersoft.jrsctl.core.compat.CompatMatrix;
import com.jaspersoft.jrsctl.core.config.Config;
import com.jaspersoft.jrsctl.core.config.ConfigLoader;
import com.jaspersoft.jrsctl.core.config.JrsctlHomeResolver;
import com.jaspersoft.jrsctl.core.platform.NativeTempDir;
import com.jaspersoft.jrsctl.core.platform.OperatorPrompt;
import com.jaspersoft.jrsctl.core.platform.Platform;
import com.jaspersoft.jrsctl.core.platform.Platforms;
import com.jaspersoft.jrsctl.core.redact.Redactor;
import com.jaspersoft.jrsctl.core.secrets.EncryptedSecretStore;
import com.jaspersoft.jrsctl.core.secrets.PassphraseSource;
import com.jaspersoft.jrsctl.core.secrets.Secret;
import com.jaspersoft.jrsctl.core.secrets.SecretException;
import com.jaspersoft.jrsctl.core.secrets.SecretRef;
import com.jaspersoft.jrsctl.core.secrets.SecretResolver;
import com.jaspersoft.jrsctl.core.state.StateStore;
import com.jaspersoft.jrsctl.jrs.api.JrsAdapter;
import com.jaspersoft.jrsctl.jrs.api.JrsAdapterFactory;
import com.jaspersoft.jrsctl.ops.Lazy;
import com.jaspersoft.jrsctl.ops.Services;
import java.io.Console;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Builds the {@link Services} bundle from the global options (spec §5.1, §5.2, §5.5). Invariants:
 * the home is {@code --home}, else {@code JRSCTL_HOME}, else the platform default; configuration
 * precedence is flag &gt; env &gt; file &gt; default; every {@code env:} and {@code file:} secret
 * reference in the configuration is resolved once here and registered with the global redactor
 * before any command output is written ({@code enc:} references too when a non-interactive
 * passphrase is available, so no command prompts merely to redact); the state store and adapter are
 * opened lazily and the store is closed with this object; a {@link
 * com.jaspersoft.jrsctl.core.config.ConfigException} propagates so the command exits 2 without
 * touching anything.
 */
final class Bootstrap implements AutoCloseable {

  static final String PASSPHRASE_ENV = "JRSCTL_PASSPHRASE";
  private static final Logger LOG = LoggerFactory.getLogger(Bootstrap.class);

  private final Services services;
  private final Lazy<StateStore> store;
  private final EncryptedSecretStore secretStore;

  private Bootstrap(Services services, Lazy<StateStore> store, EncryptedSecretStore secretStore) {
    this.services = services;
    this.store = store;
    this.secretStore = secretStore;
  }

  static Bootstrap open(GlobalOptions options, Map<String, String> env, Clock clock) {
    Objects.requireNonNull(options, "options");
    Objects.requireNonNull(env, "env");
    boolean interactive = !options.nonInteractive() && Terminal.present();
    OperatorPrompt prompt = interactive ? new ConsolePrompt() : OperatorPrompt.nonInteractive();
    Platform platform = Platforms.detect(prompt);
    JrsctlHome home =
        options
            .home()
            .map(h -> new JrsctlHome(h.toAbsolutePath().normalize()))
            .orElseGet(() -> JrsctlHomeResolver.resolve(env, platform));
    // review 4.1: the home holds secrets.enc, console.token, state.db and the snapshots, so a
    // home jrsctl creates is private to its owner from the start. An existing home is left as the
    // operator set it up; it is only read, never re-permissioned.
    createHome(platform, home);
    // review 3.4: the SQLite driver runs its native library from java.io.tmpdir, which a
    // CIS-hardened Linux host mounts noexec; the home is the tool's own writable directory
    NativeTempDir.use(home.nativeTemp());
    Config config = new ConfigLoader().load(home, env, options.set());

    List<PassphraseSource> sources = new ArrayList<>();
    options.passphraseFile().ifPresent(f -> sources.add(new PassphraseSource.FromFile(f)));
    sources.add(new PassphraseSource.FromEnv(env));
    if (interactive) {
      sources.add(new PassphraseSource.FromConsole());
    }
    boolean passphraseWithoutPrompt =
        options.passphraseFile().isPresent() || env.containsKey(PASSPHRASE_ENV);
    EncryptedSecretStore secretStore =
        new EncryptedSecretStore(home.secretsFile(), new PassphraseSource.Chain(sources));
    SecretResolver secrets = new SecretResolver(env, platform.files(), secretStore);
    Redactor redactor = Redactor.global();
    registerSecrets(config, secrets, redactor, passphraseWithoutPrompt);

    Lazy<StateStore> store = Lazy.of(() -> StateStore.open(home, clock));
    Lazy<JrsAdapter> adapter =
        Lazy.of(() -> JrsAdapterFactory.load().connect(config, secrets, redactor, platform));
    Services services =
        new Services(
            home,
            config,
            platform,
            secrets,
            redactor,
            CompatMatrix.load(),
            store,
            adapter,
            clock,
            interactive);
    return new Bootstrap(services, store, secretStore);
  }

  /** Creates a missing home directory owner-only; an existing one is left alone (review 4.1). */
  static void createHome(Platform platform, JrsctlHome home) {
    java.nio.file.Path root = home.root();
    if (java.nio.file.Files.isDirectory(root)) {
      return;
    }
    try {
      if (platform.os() == Platform.OsFamily.LINUX
          && java.nio.file.FileSystems.getDefault()
              .supportedFileAttributeViews()
              .contains("posix")) {
        java.nio.file.Files.createDirectories(
            root,
            java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
                java.nio.file.attribute.PosixFilePermissions.fromString("rwx------")));
      } else {
        java.nio.file.Files.createDirectories(root);
      }
    } catch (java.io.IOException e) {
      LOG.debug("cannot create {}: {}", root, e.getMessage());
    }
  }

  Services services() {
    return services;
  }

  /**
   * The {@code secrets.enc} store behind every {@code enc:} reference, with the passphrase chain.
   */
  EncryptedSecretStore secretStore() {
    return secretStore;
  }

  @Override
  public void close() {
    store.peek().ifPresent(StateStore::close);
  }

  /** Registers every resolvable configured secret so no output stream can leak it. */
  static void registerSecrets(
      Config config, SecretResolver secrets, Redactor redactor, boolean encWithoutPrompt) {
    List<SecretRef> refs = new ArrayList<>();
    config.server().auth().passwordRef().ifPresent(refs::add);
    config.database().passwordRef().ifPresent(refs::add);
    config.network().proxy().passwordRef().ifPresent(refs::add);
    config.network().trustStore().passwordRef().ifPresent(refs::add);
    config.console().auth().passwordRef().ifPresent(refs::add);
    for (SecretRef ref : refs) {
      if (ref instanceof SecretRef.Enc && !encWithoutPrompt) {
        continue;
      }
      try (Secret secret = secrets.resolve(ref)) {
        redactor.register(secret);
      } catch (SecretException e) {
        LOG.debug("secret {} not resolvable at startup: {}", ref.render(), e.getMessage());
      }
    }
  }

  /** Console-backed operator prompt: prints the instruction and waits for Enter. */
  static final class ConsolePrompt implements OperatorPrompt {
    @Override
    public void instruct(String message) {
      Console console =
          Terminal.console()
              .orElseThrow(
                  () -> new IllegalStateException("no console for operator prompt: " + message));
      console.printf("%s%n", Redactor.global().redact(message));
      console.printf("Press Enter when done... ");
      console.readLine();
    }

    @Override
    public boolean interactive() {
      return true;
    }
  }
}
