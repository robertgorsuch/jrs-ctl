package com.jaspersoft.jrsctl.ops.hotfix;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class HotfixPathsTest {

  /**
   * Review finding 1.15: the check was case-sensitive, so {@code web-inf/lib/x.jar} with {@code
   * restart: none} passed validation although Tomcat serves that directory whatever its case on
   * Windows and the swap would then land under a running server.
   */
  @Test
  void should_require_a_service_stop_for_web_inf_lib_and_classes_in_any_letter_case() {
    assertThat(HotfixPaths.requiresServiceStop("webapps/js/web-inf/lib/x.jar")).isTrue();
    assertThat(HotfixPaths.requiresServiceStop("webapps/js/Web-Inf/Classes/x.properties")).isTrue();
    assertThat(HotfixPaths.requiresServiceStop("webapps\\js\\WEB-INF\\lib\\x.jar")).isTrue();
    assertThat(HotfixPaths.requiresServiceStop("webapps/js/scripts/x.js")).isFalse();
  }
}
