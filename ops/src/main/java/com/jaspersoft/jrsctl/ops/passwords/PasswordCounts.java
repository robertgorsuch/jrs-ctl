package com.jaspersoft.jrsctl.ops.passwords;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The distribution of stored passwords as the vendor's utility prints it (installation guide 10.1
 * pp.196-197): {@code Total users: N}, {@code Modern passwords: N}, {@code Legacy passwords: N},
 * or, in the dry run and the migration's opening statistics, {@code Users already modern: N} and
 * {@code Users needing migration: N}, each possibly behind a list dash. Invariants: the last
 * occurrence of each count wins, since {@code migrate-passwords} prints the statistics before and
 * after its work; {@link #complete()} is the guide's rule, read generously: at most one legacy
 * password (the {@code anonymousUser}) and every other user modern; a transcript without the three
 * counts yields {@link Optional#empty()}.
 */
public record PasswordCounts(int total, int modern, int legacy) {

  // the dry run and the migration's opening statistics spell the same three counts as
  // "Users already modern" and "Users needing migration" (installation guide 10.1 p.196)
  private static final Pattern TOTAL = Pattern.compile("(?i)total users:\\s*(\\d+)");
  private static final Pattern MODERN =
      Pattern.compile("(?i)(?:modern passwords|users already modern):\\s*(\\d+)");
  private static final Pattern LEGACY =
      Pattern.compile("(?i)(?:legacy passwords|users needing migration):\\s*(\\d+)");

  public static Optional<PasswordCounts> parse(List<String> lines) {
    Optional<Integer> total = Optional.empty();
    Optional<Integer> modern = Optional.empty();
    Optional<Integer> legacy = Optional.empty();
    for (String line : lines) {
      Optional<Integer> t = last(TOTAL, line);
      Optional<Integer> m = last(MODERN, line);
      Optional<Integer> l = last(LEGACY, line);
      total = t.isPresent() ? t : total;
      modern = m.isPresent() ? m : modern;
      legacy = l.isPresent() ? l : legacy;
    }
    if (total.isEmpty() || modern.isEmpty() || legacy.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(new PasswordCounts(total.get(), modern.get(), legacy.get()));
  }

  private static Optional<Integer> last(Pattern pattern, String line) {
    Matcher m = pattern.matcher(line);
    Optional<Integer> found = Optional.empty();
    while (m.find()) {
      try {
        found = Optional.of(Integer.parseInt(m.group(1)));
      } catch (NumberFormatException e) {
        // a count too large to be real; ignore the line
      }
    }
    return found;
  }

  /** The guide's rule: the legacy count is at most one (anonymousUser) and the rest are modern. */
  public boolean complete() {
    return legacy <= 1 && modern >= total - 1;
  }

  /** {@code "6 users: 5 modern, 1 legacy"}. */
  public String describe() {
    return String.format(Locale.ROOT, "%d users: %d modern, %d legacy", total, modern, legacy);
  }
}
