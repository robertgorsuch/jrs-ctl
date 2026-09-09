package com.jaspersoft.jrsctl.app.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Guards the static console assets served from the JAR (spec §13.2): they must exist on the
 * classpath, reference nothing outside the JAR (isolated mode), and carry no emoji.
 */
class WebAssetsTest {

  private static final List<String> ASSETS =
      List.of(
          "/web/index.html",
          "/web/console.css",
          "/web/app.js",
          "/web/api.js",
          "/web/mock.js",
          "/web/brand.css");

  private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);
  private static final Pattern HTML_COMMENT = Pattern.compile("<!--.*?-->", Pattern.DOTALL);
  // A line comment starts at the line start or after whitespace, so "http://x" inside code is kept.
  private static final Pattern LINE_COMMENT = Pattern.compile("(?m)(^|\\s)//.*$");
  private static final Pattern REMOTE_SCHEME = Pattern.compile("https?://");
  private static final Pattern SRC_OR_HREF =
      Pattern.compile("\\b(?:src|href)\\s*=\\s*\"([^\"]*)\"", Pattern.CASE_INSENSITIVE);

  @Test
  void should_find_every_console_asset_on_classpath() {
    for (String asset : ASSETS) {
      assertThat(read(asset)).as(asset).isNotEmpty();
    }
  }

  @Test
  void should_not_reference_remote_urls_outside_comments() {
    for (String asset : ASSETS) {
      String code = stripComments(read(asset));
      assertThat(REMOTE_SCHEME.matcher(code).find())
          .as("%s must not reference http:// or https:// outside comments", asset)
          .isFalse();
    }
  }

  @Test
  void should_reference_only_local_files_from_index_html() {
    String html = HTML_COMMENT.matcher(read("/web/index.html")).replaceAll("");
    Matcher m = SRC_OR_HREF.matcher(html);
    int refs = 0;
    while (m.find()) {
      refs++;
      String ref = m.group(1);
      assertThat(ref).as("index.html reference %s", ref).doesNotStartWith("//");
      assertThat(ref).as("index.html reference %s", ref).doesNotContain("://");
    }
    assertThat(refs).as("index.html should reference its stylesheets and script").isGreaterThan(0);
    assertThat(html).contains("brand.css").contains("console.css").contains("app.js");
  }

  @Test
  void should_not_contain_emoji() {
    for (String asset : ASSETS) {
      String text = read(asset);
      assertThat(text.codePoints().anyMatch(cp -> cp >= 0x1F300))
          .as("%s must not contain emoji", asset)
          .isFalse();
    }
  }

  private static String stripComments(String text) {
    String noBlock = BLOCK_COMMENT.matcher(text).replaceAll("");
    String noHtml = HTML_COMMENT.matcher(noBlock).replaceAll("");
    return LINE_COMMENT.matcher(noHtml).replaceAll("");
  }

  private static String read(String path) {
    try (InputStream in = WebAssetsTest.class.getResourceAsStream(path)) {
      assertThat(in).as("classpath resource %s", path).isNotNull();
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException("cannot read " + path, e);
    }
  }
}
