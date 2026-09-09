package com.jaspersoft.jrsctl.core.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.jaspersoft.jrsctl.core.JrsctlHome;
import com.jaspersoft.jrsctl.core.platform.Platform;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JrsctlHomeResolverTest {

  private final Platform platform = mock(Platform.class);

  @Test
  void should_use_env_var_when_jrsctl_home_is_set() {
    Path custom = Path.of("custom-home").toAbsolutePath();

    JrsctlHome home = JrsctlHomeResolver.resolve(Map.of("JRSCTL_HOME", "custom-home"), platform);

    assertThat(home.root()).isEqualTo(custom.normalize());
    assertThat(home.configFile()).isEqualTo(custom.normalize().resolve("config.yaml"));
  }

  @Test
  void should_fall_back_to_platform_default_when_env_var_is_absent_or_blank() {
    Path dflt = Path.of("platform-default").toAbsolutePath();
    when(platform.defaultHome()).thenReturn(dflt);

    assertThat(JrsctlHomeResolver.resolve(Map.of(), platform).root()).isEqualTo(dflt);
    assertThat(JrsctlHomeResolver.resolve(Map.of("JRSCTL_HOME", "  "), platform).root())
        .isEqualTo(dflt);
  }
}
