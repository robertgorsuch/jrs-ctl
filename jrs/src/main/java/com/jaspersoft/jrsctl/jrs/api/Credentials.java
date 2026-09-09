package com.jaspersoft.jrsctl.jrs.api;

import com.jaspersoft.jrsctl.core.secrets.Secret;
import java.util.Optional;

/**
 * Login material. Invariant: the password is a {@link Secret} that is never rendered by {@code
 * toString}; {@code organization} is required only on multi-tenant servers when the user is not a
 * superuser.
 */
public record Credentials(String username, Secret password, Optional<String> organization) {

  @Override
  public String toString() {
    return "Credentials[" + username + organization.map(o -> "|" + o).orElse("") + "]";
  }
}
