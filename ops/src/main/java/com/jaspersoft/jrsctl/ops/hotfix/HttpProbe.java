package com.jaspersoft.jrsctl.ops.hotfix;

import com.jaspersoft.jrsctl.jrs.rest.RestClient;
import com.jaspersoft.jrsctl.ops.Services;

/**
 * Issues the {@code GET {baseUrl}{url}} of a manifest {@code http} check and reports the status
 * code (spec §8.1). Invariants: the request goes through {@link RestClient}, so isolated network
 * mode, proxy and trust store settings apply; no authentication is attached because hotfix checks
 * target anonymous pages such as {@code /login.html}; failures to connect surface as exceptions,
 * never as a fabricated status.
 */
@FunctionalInterface
public interface HttpProbe {

  /** Status code of {@code GET} on the server-relative {@code path}. */
  int status(String path);

  /** Probe backed by a {@link RestClient} built from the configuration. */
  static HttpProbe rest(Services services) {
    return path ->
        RestClient.builder(services.config(), services.secrets(), services.redactor())
            .build()
            .get(path, "*/*")
            .status();
  }
}
