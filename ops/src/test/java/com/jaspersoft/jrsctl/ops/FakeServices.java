package com.jaspersoft.jrsctl.ops;

import com.jaspersoft.jrsctl.core.JrsctlHome;
import com.jaspersoft.jrsctl.core.compat.CompatMatrix;
import com.jaspersoft.jrsctl.core.config.Config;
import com.jaspersoft.jrsctl.core.config.ConfigLoader;
import com.jaspersoft.jrsctl.core.platform.Platform;
import com.jaspersoft.jrsctl.core.redact.Redactor;
import com.jaspersoft.jrsctl.core.secrets.EncryptedSecretStore;
import com.jaspersoft.jrsctl.core.secrets.PassphraseSource;
import com.jaspersoft.jrsctl.core.secrets.Secret;
import com.jaspersoft.jrsctl.core.secrets.SecretResolver;
import com.jaspersoft.jrsctl.core.state.StateStore;
import com.jaspersoft.jrsctl.jrs.api.JrsAdapter;
import com.jaspersoft.jrsctl.jrs.api.JrsUnreachableException;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Builder for a {@link Services} bundle over a throw-away home directory, a {@link FakePlatform}
 * and a {@link FakeJrsAdapter}. The configuration is given as YAML text and loaded through the real
 * {@link ConfigLoader}, so tests exercise the schema too.
 */
public final class FakeServices implements AutoCloseable {

  public static final String PASSPHRASE = "test-passphrase";

  public final JrsctlHome home;
  public final FakePlatform platform;
  public final FakeJrsAdapter adapter = new FakeJrsAdapter();
  public final Map<String, String> env = new HashMap<>();
  public final Clock clock = Clock.fixed(Instant.parse("2026-09-08T10:00:00Z"), ZoneOffset.UTC);
  public Config config = Config.defaults();
  public boolean unreachable;
  public boolean interactive;

  private Lazy<StateStore> store;

  private FakeServices(Path root, Platform.OsFamily os) throws IOException {
    this.home = new JrsctlHome(Files.createDirectories(root));
    this.platform = new FakePlatform(os, root);
    this.env.put("JRS_PASSWORD", "s3cret-pass");
  }

  public static FakeServices in(Path root) throws IOException {
    return new FakeServices(root, Platform.OsFamily.LINUX);
  }

  public static FakeServices in(Path root, Platform.OsFamily os) throws IOException {
    return new FakeServices(root, os);
  }

  /** Writes {@code yaml} as the home's config.yaml and loads it. */
  public FakeServices yaml(String yaml) throws IOException {
    Files.writeString(home.configFile(), yaml, StandardCharsets.UTF_8);
    this.config = new ConfigLoader().load(home, env, Map.of());
    return this;
  }

  public EncryptedSecretStore secretStore() {
    return new EncryptedSecretStore(
        home.secretsFile(), new PassphraseSource.Fixed(Secret.fromString(PASSPHRASE)));
  }

  public Services build() {
    store = Lazy.of(() -> StateStore.open(home, clock));
    Supplier<JrsAdapter> adapterSupplier =
        () -> {
          if (unreachable) {
            throw new JrsUnreachableException(
                URI.create("http://localhost:1/jasperserver-pro"),
                "connection refused: http://localhost:1/jasperserver-pro",
                "start the server or fix server.baseUrl",
                null);
          }
          return adapter;
        };
    return new Services(
        home,
        config,
        platform,
        new SecretResolver(env, platform.files(), secretStore()),
        new Redactor(),
        CompatMatrix.load(),
        store,
        adapterSupplier,
        clock,
        interactive);
  }

  public StateStore stateStore() {
    return store.get();
  }

  @Override
  public void close() {
    if (store != null) {
      store.peek().ifPresent(StateStore::close);
    }
  }
}
