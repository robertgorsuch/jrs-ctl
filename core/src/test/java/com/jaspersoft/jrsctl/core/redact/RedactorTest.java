package com.jaspersoft.jrsctl.core.redact;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jaspersoft.jrsctl.core.secrets.Secret;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RedactorTest {

  private final Redactor redactor = new Redactor();

  @Test
  void should_replace_raw_base64_and_url_encoded_forms_when_value_is_registered() {
    String secret = "p@ss w0rd/+=";
    redactor.register(secret);
    String b64 = Base64.getEncoder().encodeToString(secret.getBytes(StandardCharsets.UTF_8));
    String url = URLEncoder.encode(secret, StandardCharsets.UTF_8);

    String out = redactor.redact("raw=" + secret + " b64=" + b64 + " url=" + url + " end");

    assertThat(out).isEqualTo("raw=[redacted] b64=[redacted] url=[redacted] end");
  }

  @Test
  void should_replace_the_json_escaped_form_when_value_holds_a_quote_or_backslash() {
    String secret = "ab\"cd\\ef";
    redactor.register(secret);
    // What Jackson writes for that value inside a JSON string, redacted after serialisation.
    String serialised = "{\"detail\":\"password ab\\\"cd\\\\ef rejected\"}";

    String out = redactor.redact(serialised);

    assertThat(out).isEqualTo("{\"detail\":\"password [redacted] rejected\"}");
    assertThat(out).doesNotContain("ab");
  }

  @Test
  void should_register_from_secret_without_keeping_a_reference_when_secret_is_closed() {
    Secret s = Secret.fromString("hunter22");
    assertThat(redactor.register(s)).isTrue();
    s.close();

    assertThat(redactor.redact("pw hunter22!")).isEqualTo("pw [redacted]!");
  }

  @Test
  void should_ignore_values_shorter_than_four_chars_when_registering() {
    assertThat(redactor.register("abc")).isFalse();
    assertThat(redactor.size()).isZero();

    assertThat(redactor.redact("abc abc")).isEqualTo("abc abc");
  }

  @Test
  void should_mask_longer_value_first_when_one_secret_contains_another() {
    redactor.register("hunter2");
    redactor.register("hunter2-extended");

    assertThat(redactor.redact("a hunter2-extended b")).isEqualTo("a [redacted] b");
  }

  @Test
  void should_not_recreate_secret_when_mask_boundary_would_spell_it() {
    redactor.register("cted]Q");

    String out = redactor.redact("x cted]Q Q y");

    assertThat(out).doesNotContain("cted]Q");
  }

  @Test
  void should_mask_password_style_pairs_when_nothing_is_registered() {
    assertThat(redactor.redact("password=hunter2&user=admin"))
        .isEqualTo("password=[redacted]&user=admin");
    assertThat(redactor.redact("passwd: hunter2")).isEqualTo("passwd: [redacted]");
    assertThat(redactor.redact("-storepass \"my store\" -keypass k"))
        .isEqualTo("-storepass \"[redacted]\" -keypass [redacted]");
    assertThat(redactor.redact("keystorepasswd=abc;")).isEqualTo("keystorepasswd=[redacted];");
    assertThat(redactor.redact("JRS_PASSWORD=hunter2")).isEqualTo("JRS_PASSWORD=[redacted]");
    assertThat(redactor.redact("console.token: 0123abcd")).isEqualTo("console.token: [redacted]");
    assertThat(redactor.redact("{\"password\": \"hunter2\"}"))
        .isEqualTo("{\"password\": \"[redacted]\"}");
  }

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  void should_mask_a_quoted_attribute_when_a_json_value_quotes_a_file() throws Exception {
    // a context.xml diff line inside a JSON document (issue #369)
    Map<String, Object> doc = new LinkedHashMap<>();
    doc.put("diff", "+  username=\"postgres\" password=\"s3cr\\et!\" driver=\"x\"\n+ next line");

    String out = redactor.redactJson(MAPPER, doc);

    assertThat(out).doesNotContain("s3cr");
    assertThat(MAPPER.readTree(out).get("diff").asText())
        .isEqualTo("+  username=\"postgres\" password=\"[redacted]\" driver=\"x\"\n+ next line");
  }

  @Test
  void should_mask_a_property_with_the_escape_xml_suffix_when_the_key_names_a_password() {
    assertThat(redactor.redact("metadata.jdbc.password.escapeXML=hunter2"))
        .isEqualTo("metadata.jdbc.password.escapeXML=[redacted]");
  }

  @Test
  void should_leave_password_settings_readable_when_the_dotted_key_is_not_a_secret() {
    // review of 2.9.1: passwords status prints these, and they are not secrets
    assertThat(redactor.redact("set password.strategy=modern and restart"))
        .isEqualTo("set password.strategy=modern and restart");
    assertThat(redactor.redact("password.algorithm=PBKDF2")).isEqualTo("password.algorithm=PBKDF2");
  }

  @Test
  void should_mask_a_whole_bare_value_when_it_holds_a_backslash() {
    // review of 2.9.1: the tail after a backslash leaked in plain-text logs
    assertThat(redactor.redact("password=abc\\test end")).isEqualTo("password=[redacted] end");
    assertThat(redactor.redact("-storepass ab\\nc -keypass k\\/x"))
        .isEqualTo("-storepass [redacted] -keypass [redacted]");
  }

  @Test
  void should_mask_each_line_on_its_own_when_a_json_value_holds_an_unclosed_quote()
      throws Exception {
    // review of 2.9.1: an unclosed quote ran on to the next line's value
    Map<String, Object> doc = Map.of("diff", "- a password=\"open\n+ x password=\"new\"");

    String out = redactor.redactJson(MAPPER, doc);

    assertThat(out).doesNotContain("open").doesNotContain("\"new");
    assertThat(MAPPER.readTree(out).get("diff").asText()).endsWith("password=\"[redacted]\"");
  }

  @Test
  void should_mask_a_json_password_field_whole_when_its_value_holds_an_escaped_quote()
      throws Exception {
    String out = redactor.redactJson(MAPPER, Map.of("password", "ab\"cd", "user", "u1"));

    JsonNode tree = MAPPER.readTree(out);
    assertThat(tree.get("password").asText()).isEqualTo("[redacted]");
    assertThat(tree.get("user").asText()).isEqualTo("u1");
    assertThat(out).doesNotContain("cd");
  }

  @Test
  void should_mask_secret_fields_by_name_but_leave_references_and_flags_when_redacting_a_tree() {
    ObjectNode node = MAPPER.createObjectNode();
    node.put("dbPassword", "hunter2");
    node.put("Authorization", "Bearer abc.def");
    node.put("password.escapeXML", "hunter2");
    node.put("passwordRef", "env:JRS_PASSWORD");
    node.put("passwordSet", true);
    node.putArray("passwords").add("status ok");

    JsonNode out = redactor.redact(node);

    assertThat(out.get("dbPassword").asText()).isEqualTo("[redacted]");
    assertThat(out.get("Authorization").asText()).isEqualTo("[redacted]");
    assertThat(out.get("password.escapeXML").asText()).isEqualTo("[redacted]");
    assertThat(out.get("passwordRef").asText()).isEqualTo("env:JRS_PASSWORD");
    assertThat(out.get("passwordSet").asBoolean()).isTrue();
    assertThat(out.get("passwords").get(0).asText()).isEqualTo("status ok");
  }

  @Test
  void should_mask_registered_values_in_field_names_and_nested_values_when_redacting_a_tree() {
    redactor.register("Zq9-secret");
    ObjectNode node = MAPPER.createObjectNode();
    node.putObject("Zq9-secret").put("note", "value Zq9-secret here");

    String out = redactor.redact(node).toString();

    assertThat(out).doesNotContain("Zq9").contains("[redacted]");
  }

  @Test
  void should_mask_a_json_log_line_through_its_tree_and_plain_text_otherwise() throws Exception {
    String logLine =
        MAPPER.writeValueAsString(
            Map.of("message", "attr password=\"x1y2\" done", "level", "INFO"));

    String out = redactor.redactJsonLine(logLine);

    assertThat(MAPPER.readTree(out).get("message").asText())
        .isEqualTo("attr password=\"[redacted]\" done");
    assertThat(redactor.redactJsonLine("plain password=x1y2"))
        .isEqualTo("plain password=[redacted]");
    assertThat(redactor.redactJsonLine("{not json; password=x1y2 }"))
        .isEqualTo("{not json; password=[redacted] }");
  }

  @Test
  void should_leave_secret_references_visible_when_they_are_not_values() {
    assertThat(redactor.redact("passwordRef: env:JRS_PASSWORD"))
        .isEqualTo("passwordRef: env:JRS_PASSWORD");
  }

  @Test
  void should_mask_headers_cookies_and_bearer_tokens_when_present() {
    assertThat(redactor.redact("Authorization: Basic amFzcGVyYWRtaW46amFzcGVyYWRtaW4="))
        .isEqualTo("Authorization: [redacted]");
    assertThat(redactor.redact("{\"Authorization\": \"Bearer abc.def\", \"x\": 1}"))
        .isEqualTo("{\"Authorization\": \"[redacted]\", \"x\": 1}");
    assertThat(redactor.redact("Cookie: JSESSIONID=1A2B3C4D; path=/"))
        .isEqualTo("Cookie: JSESSIONID=[redacted]; path=/");
    assertThat(redactor.redact("token Bearer eyJhbGciOi.xx-yy_zz== sent"))
        .isEqualTo("token Bearer [redacted] sent");
  }

  @Test
  void should_return_input_unchanged_when_nothing_matches() {
    redactor.register("hunter2");

    assertThat(redactor.redact("plain progress line 3/7")).isEqualTo("plain progress line 3/7");
    assertThat(redactor.redact("")).isEmpty();
  }

  @Test
  void should_leave_larger_words_readable_when_a_word_secret_occurs_inside_them() {
    // issue #199: the database password was a word that also occurs in the driver's package name
    redactor.register("postgres");

    assertThat(
            redactor.redact(
                "type: postgresql url: jdbc:postgresql://127.0.0.1:5433/jasperserver"
                    + " org.postgresql.util.PSQLException username: jrs_postgres Postgres"))
        .isEqualTo(
            "type: postgresql url: jdbc:postgresql://127.0.0.1:5433/jasperserver"
                + " org.postgresql.util.PSQLException username: jrs_postgres Postgres");
  }

  @Test
  void should_mask_a_word_secret_when_it_stands_alone() {
    redactor.register("postgres");

    assertThat(
            redactor.redact(
                "password=postgres jdbc:postgresql://user:postgres@db/js?pw=postgres&x=1"
                    + " \"postgres\" (postgres) postgres."))
        .isEqualTo(
            "password=[redacted] jdbc:postgresql://user:[redacted]@db/js?pw=[redacted]&x=1"
                + " \"[redacted]\" ([redacted]) [redacted].");
  }

  @Test
  void should_mask_encoded_forms_of_a_word_secret_even_inside_other_text() {
    redactor.register("postgres");
    String b64 = Base64.getEncoder().encodeToString("postgres".getBytes(StandardCharsets.UTF_8));

    assertThat(redactor.redact("h=x" + b64 + "y")).isEqualTo("h=x[redacted]y");
  }

  @Test
  void should_mask_a_secret_that_is_not_a_word_even_inside_other_text() {
    redactor.register("hunter22");

    assertThat(redactor.redact("abchunter22def")).isEqualTo("abc[redacted]def");
  }

  @Test
  void should_treat_only_all_letter_values_as_words_when_registering() {
    assertThat(Redactor.isWord("postgres")).isTrue();
    assertThat(Redactor.isWord("contraseña")).isTrue();
    assertThat(Redactor.isWord("postgres1")).isFalse();
    assertThat(Redactor.isWord("post gres")).isFalse();
    assertThat(Redactor.isWord("")).isFalse();
  }

  @Test
  void should_share_registrations_when_using_global_instance() {
    Redactor g = Redactor.global();
    g.register("global-secret-value");
    try {
      assertThat(Redactor.global().redact("x global-secret-value y")).isEqualTo("x [redacted] y");
    } finally {
      g.clear();
    }
  }
}
