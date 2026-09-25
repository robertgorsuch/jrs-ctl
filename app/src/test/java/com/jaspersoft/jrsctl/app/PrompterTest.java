package com.jaspersoft.jrsctl.app;

import static org.assertj.core.api.Assertions.assertThat;

import org.jline.reader.CompletingParsedLine;
import org.jline.reader.ParsedLine;
import org.jline.reader.Parser.ParseContext;
import org.junit.jupiter.api.Test;

class PrompterTest {

  /**
   * JLine logs "does not support the CompletingParsedLine interface" on every path prompt unless
   * the parser's lines implement it; the whole line is one path, so nothing is quoted or escaped.
   */
  @Test
  void should_parse_the_whole_line_as_one_unescaped_path_when_completing() {
    String typed = "C:\\Program Files\\hot fix.zip";

    ParsedLine parsed = Prompter.WHOLE_LINE_PARSER.parse(typed, 11, ParseContext.COMPLETE);

    assertThat(parsed).isInstanceOf(CompletingParsedLine.class);
    CompletingParsedLine line = (CompletingParsedLine) parsed;
    assertThat(line.word()).isEqualTo(typed);
    assertThat(line.rawWordCursor()).isEqualTo(11);
    assertThat(line.rawWordLength()).isEqualTo(typed.length());
    assertThat(line.escape(typed, true)).hasToString(typed);
  }
}
