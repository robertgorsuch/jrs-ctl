package com.jaspersoft.jrsctl.ops.hotfix;

import static org.assertj.core.api.Assertions.assertThat;

import com.jaspersoft.jrsctl.jrs.api.ServerIdentity;
import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ApplicabilityTest {

  private static Manifest manifest(
      List<String> versions, List<String> editions, List<String> tenancy) {
    return new Manifest(
        "JRS-8.2.0-HF-0001",
        "1",
        "t",
        Optional.empty(),
        new Manifest.Applies(versions, editions, tenancy),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        Manifest.Restart.NONE,
        List.of(),
        List.of(),
        Manifest.Rollback.SNAPSHOT,
        Optional.empty());
  }

  private static ServerIdentity server(
      String version, ServerIdentity.Edition edition, ServerIdentity.Tenancy tenancy) {
    return new ServerIdentity(
        URI.create("http://localhost:8080/jasperserver-pro"),
        version,
        edition,
        tenancy,
        Set.of(),
        "b",
        "yyyy-MM-dd");
  }

  @Test
  void should_pass_when_version_edition_and_tenancy_match() {
    assertThat(
            Applicability.check(
                manifest(List.of(">=10.0.0 <10.1.0"), List.of("PRO"), List.of("MULTI")),
                server("10.0.5", ServerIdentity.Edition.PRO, ServerIdentity.Tenancy.MULTI)))
        .isEmpty();
  }

  @Test
  void should_fail_when_version_outside_range() {
    assertThat(
            Applicability.check(
                manifest(List.of(">=10.0.0 <10.1.0"), List.of(), List.of()),
                server("10.1.0", ServerIdentity.Edition.PRO, ServerIdentity.Tenancy.MULTI)))
        .singleElement()
        .asString()
        .contains("outside applies.versions");
  }

  @Test
  void should_fail_when_edition_not_listed() {
    assertThat(
            Applicability.check(
                manifest(List.of(">=8.0.0"), List.of("PRO"), List.of()),
                server("8.2.0", ServerIdentity.Edition.CE, ServerIdentity.Tenancy.SINGLE)))
        .singleElement()
        .asString()
        .contains("edition CE");
  }

  @Test
  void should_fail_when_tenancy_not_listed() {
    assertThat(
            Applicability.check(
                manifest(List.of(">=8.0.0"), List.of(), List.of("MULTI")),
                server("8.2.0", ServerIdentity.Edition.PRO, ServerIdentity.Tenancy.SINGLE)))
        .singleElement()
        .asString()
        .contains("tenancy SINGLE");
  }

  @Test
  void should_accept_any_of_several_ranges_when_one_matches() {
    assertThat(
            Applicability.check(
                manifest(List.of("8.1.x", ">=9.0.0 <9.1.0"), List.of(), List.of()),
                server("9.0.2", ServerIdentity.Edition.PRO, ServerIdentity.Tenancy.SINGLE)))
        .isEmpty();
  }
}
