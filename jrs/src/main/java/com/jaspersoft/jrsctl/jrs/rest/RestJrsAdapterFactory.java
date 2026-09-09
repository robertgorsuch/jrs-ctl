package com.jaspersoft.jrsctl.jrs.rest;

import com.jaspersoft.jrsctl.core.compat.CompatMatrix;
import com.jaspersoft.jrsctl.core.config.Config;
import com.jaspersoft.jrsctl.core.platform.Platform;
import com.jaspersoft.jrsctl.core.redact.Redactor;
import com.jaspersoft.jrsctl.core.secrets.Secret;
import com.jaspersoft.jrsctl.core.secrets.SecretResolver;
import com.jaspersoft.jrsctl.jrs.api.Credentials;
import com.jaspersoft.jrsctl.jrs.api.JrsAdapter;
import com.jaspersoft.jrsctl.jrs.api.JrsAdapterFactory;
import java.util.Optional;

/**
 * The registered {@link JrsAdapterFactory} ({@code META-INF/services}). Invariants: {@link
 * #connect} sends nothing to the server; identity and capabilities are fetched lazily on first use
 * so {@code doctor} can report "server unreachable" as a check item instead of failing to start;
 * the password behind {@code server.auth.passwordRef} is resolved once, registered with the {@link
 * Redactor} and kept open for the life of the adapter (basic auth re-sends it, form auth may need
 * to log in again); proxy and trust-store secrets are handled the same way by {@link
 * RestClient#builder(Config, SecretResolver, Redactor)}.
 */
public final class RestJrsAdapterFactory implements JrsAdapterFactory {

  @Override
  public JrsAdapter connect(
      Config config, SecretResolver secrets, Redactor redactor, Platform platform) {
    RestClient client = RestClient.builder(config, secrets, redactor).build();
    Config.Auth auth = config.server().auth();
    Optional<Credentials> credentials = Optional.empty();
    if (auth.passwordRef().isPresent()) {
      Secret password = secrets.resolve(auth.passwordRef().get());
      redactor.register(password);
      String username = auth.username().orElse(auth.mode() == Config.AuthMode.TOKEN ? "token" : "");
      if (!username.isEmpty()) {
        credentials = Optional.of(new Credentials(username, password, Optional.empty()));
        switch (auth.mode()) {
          case BASIC -> client.useBasic(username, password);
          case TOKEN -> client.useToken(password);
          case FORM -> {
            // the adapter logs in lazily before the first authenticated call
          }
        }
      }
    }
    return new RestJrsAdapter(client, config, platform, CompatMatrix.load(), credentials);
  }
}
