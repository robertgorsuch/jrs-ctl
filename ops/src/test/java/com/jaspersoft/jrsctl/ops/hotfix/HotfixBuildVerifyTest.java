package com.jaspersoft.jrsctl.ops.hotfix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jaspersoft.jrsctl.ops.hotfix.HotfixOperations.VerifyReport;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HotfixBuildVerifyTest {

  @TempDir Path tmp;

  @Test
  void should_verify_ok_when_bundle_built_and_signer_trusted() throws IOException {
    try (HotfixFixture f = HotfixFixture.create(tmp)) {
      Path zip = f.buildWebInf();
      List<String> entries = Zips.entries(zip);
      assertThat(entries.get(0)).isEqualTo("manifest.json");
      assertThat(entries.get(1)).isEqualTo("SIGNATURE");
      assertThat(entries).contains("payload/" + HotfixFixture.FOO, "payload/" + HotfixFixture.FIX);

      VerifyReport report = f.ops().verify(zip);
      assertThat(report.ok()).isTrue();
      assertThat(report.signedBy()).contains(HotfixFixture.SIGNER);
      assertThat(report.hashProblems()).isEmpty();
      assertThat(report.applicabilityProblems()).isEmpty();
      assertThat(report.manifestId()).isEqualTo(HotfixFixture.ID);
      assertThat(report.title()).isEqualTo("Fix scheduler NPE");
    }
  }

  @Test
  void should_report_hash_problem_when_payload_tampered() throws IOException {
    try (HotfixFixture f = HotfixFixture.create(tmp)) {
      Path zip = f.buildWebInf();
      Map<String, Optional<byte[]>> replacements = new HashMap<>();
      replacements.put(
          "payload/" + HotfixFixture.FOO, Optional.of("evil".getBytes(StandardCharsets.UTF_8)));
      Path tampered = Zips.rewrite(zip, tmp.resolve("tampered.zip"), replacements, Map.of());

      VerifyReport report = f.ops().verify(tampered);
      assertThat(report.signatureValid()).isTrue();
      assertThat(report.hashesValid()).isFalse();
      assertThat(report.hashProblems())
          .singleElement()
          .asString()
          .contains("sha256 mismatch for payload/" + HotfixFixture.FOO);
    }
  }

  @Test
  void should_report_unlisted_file_when_extra_entry_present() throws IOException {
    try (HotfixFixture f = HotfixFixture.create(tmp)) {
      Path zip = f.buildWebInf();
      Path extra =
          Zips.rewrite(
              zip,
              tmp.resolve("extra.zip"),
              Map.of(),
              Map.of("payload/extra.txt", "x".getBytes(StandardCharsets.UTF_8)));
      VerifyReport report = f.ops().verify(extra);
      assertThat(report.hashProblems()).containsExactly("unlisted file payload/extra.txt");
    }
  }

  @Test
  void should_report_unsigned_when_signature_missing() throws IOException {
    try (HotfixFixture f = HotfixFixture.create(tmp)) {
      Path zip = f.buildWebInf();
      Path unsigned =
          Zips.rewrite(
              zip, tmp.resolve("unsigned.zip"), Map.of("SIGNATURE", Optional.empty()), Map.of());
      VerifyReport report = f.ops().verify(unsigned);
      assertThat(report.signatureValid()).isFalse();
      assertThat(report.signedBy()).isEmpty();
      assertThat(report.hashesValid()).isTrue();
    }
  }

  @Test
  void should_report_server_unreachable_as_applicability_problem_when_adapter_throws()
      throws IOException {
    try (HotfixFixture f = HotfixFixture.create(tmp)) {
      Path zip = f.buildWebInf();
      f.fake.unreachable = true;
      VerifyReport report = f.ops().verify(zip);
      assertThat(report.signatureValid()).isTrue();
      assertThat(report.applicable()).isFalse();
      assertThat(report.applicabilityProblems())
          .singleElement()
          .asString()
          .startsWith("server unreachable:");
    }
  }

  @Test
  void should_report_applicability_problems_when_server_version_outside_range() throws IOException {
    try (HotfixFixture f = HotfixFixture.create(tmp)) {
      Path zip = f.buildWebInf();
      f.fake.adapter.identity = com.jaspersoft.jrsctl.ops.FakeJrsAdapter.identity("10.0.0");
      VerifyReport report = f.ops().verify(zip);
      assertThat(report.applicable()).isFalse();
      assertThat(report.applicabilityProblems()).anyMatch(p -> p.contains("10.0.0"));
    }
  }

  @Test
  void should_refuse_build_when_directory_holds_unknown_file() throws IOException {
    try (HotfixFixture f = HotfixFixture.create(tmp)) {
      Map<String, String> files = new HashMap<>(HotfixFixture.WEBINF_FILES);
      files.put("payload/notes.txt", "stray");
      Path dir = f.bundleDir("stray", HotfixFixture.WEBINF_MANIFEST, files);
      assertThatThrownBy(() -> f.build(dir))
          .isInstanceOf(HotfixException.class)
          .hasMessageContaining("payload/notes.txt");
    }
  }

  @Test
  void should_refuse_build_when_web_inf_payload_declares_restart_none() throws IOException {
    try (HotfixFixture f = HotfixFixture.create(tmp)) {
      Path dir =
          f.bundleDir(
              "none-webinf",
              HotfixFixture.WEBINF_MANIFEST.replace(
                  "\"restart\": \"required\"", "\"restart\": \"none\""),
              HotfixFixture.WEBINF_FILES);
      assertThatThrownBy(() -> f.build(dir))
          .isInstanceOf(HotfixException.class)
          .hasMessageContaining("restart 'none' is not allowed");
    }
  }

  @Test
  void should_refuse_build_when_payload_file_missing() throws IOException {
    try (HotfixFixture f = HotfixFixture.create(tmp)) {
      Path dir =
          f.bundleDir(
              "missing",
              HotfixFixture.WEBINF_MANIFEST,
              Map.of("payload/" + HotfixFixture.FOO, HotfixFixture.NEW_FOO));
      assertThatThrownBy(() -> f.build(dir))
          .isInstanceOf(HotfixException.class)
          .hasMessageContaining("payload file missing");
    }
  }

  @Test
  void should_refuse_build_when_private_key_is_not_ed25519() throws IOException {
    try (HotfixFixture f = HotfixFixture.create(tmp)) {
      f.fake.env.put("HF_KEY", "bm90IGEga2V5");
      HotfixFixture g = f;
      Path dir = g.bundleDir("badkey", HotfixFixture.WEBINF_MANIFEST, HotfixFixture.WEBINF_FILES);
      // the fixture's Services were built before the env changed; build fresh services
      com.jaspersoft.jrsctl.ops.Services fresh = g.fake.build();
      assertThatThrownBy(
              () ->
                  new HotfixBuilder(fresh.platform().files(), fresh.secrets())
                      .build(dir, g.keyRef, tmp.resolve("badkey.zip")))
          .isInstanceOf(HotfixException.class)
          .hasMessageContaining("Ed25519");
    }
  }
}
