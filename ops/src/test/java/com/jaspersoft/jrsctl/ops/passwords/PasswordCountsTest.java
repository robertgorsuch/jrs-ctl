package com.jaspersoft.jrsctl.ops.passwords;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** The counts the vendor's utility prints (installation guide 10.1 pp.196-197). */
class PasswordCountsTest {

  @Test
  void should_read_the_three_counts_behind_list_dashes_and_take_the_last_occurrence() {
    List<String> transcript =
        List.of(
            "migrate-passwords:",
            "Migration statistics:",
            "- Total users: 6",
            "- Users needing migration: 6",
            "- Users already modern: 0",
            "Final migration statistics:",
            "- Total users: 6",
            "- Modern passwords: 5",
            "- Legacy passwords: 1",
            "Migration completed successfully!");

    PasswordCounts counts = PasswordCounts.parse(transcript).orElseThrow();

    assertThat(counts).isEqualTo(new PasswordCounts(6, 5, 1));
    assertThat(counts.complete()).isTrue();
    assertThat(counts.describe()).isEqualTo("6 users: 5 modern, 1 legacy");
  }

  @Test
  void should_not_be_complete_while_users_beyond_anonymous_are_legacy() {
    PasswordCounts counts =
        PasswordCounts.parse(
                List.of("Total users: 6", "Modern passwords: 0", "Legacy passwords: 6"))
            .orElseThrow();

    assertThat(counts.complete()).isFalse();
    assertThat(new PasswordCounts(6, 6, 0).complete()).isTrue();
    assertThat(new PasswordCounts(1, 0, 1).complete()).isTrue();
  }

  /** The dry run prints the same three counts under other names (installation guide p.196). */
  @Test
  void should_read_the_dry_run_statistics_as_the_same_counts() {
    PasswordCounts counts =
        PasswordCounts.parse(
                List.of(
                    "Migration statistics:",
                    "- Total users: 6",
                    "- Users needing migration: 6",
                    "- Users already modern: 0",
                    "- Excluded users: 0",
                    "- Dry run: true"))
            .orElseThrow();

    assertThat(counts).isEqualTo(new PasswordCounts(6, 0, 6));
  }

  @Test
  void should_be_empty_when_a_count_is_missing() {
    assertThat(PasswordCounts.parse(List.of("BUILD SUCCESSFUL"))).isEmpty();
    assertThat(PasswordCounts.parse(List.of("Total users: 6", "Modern passwords: 5"))).isEmpty();
  }
}
