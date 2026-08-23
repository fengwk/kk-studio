package fun.fengwk.kkstudio.harness.plugin.api;

import java.util.Objects;
import java.util.regex.Pattern;

/** 插件 API 共用的 canonical 小写 dotted/dashed 标识符校验。 */
final class Identifiers {

  /** canonical 标识符的最大字符数。 */
  static final int MAX_LENGTH = 64;

  private static final Pattern CANONICAL = Pattern.compile("[a-z0-9]+(?:[.-][a-z0-9]+)*");

  private Identifiers() {}

  static String requireCanonical(String value, String context) {
    Objects.requireNonNull(value, context);
    if (value.length() > MAX_LENGTH || !CANONICAL.matcher(value).matches()) {
      throw new IllegalArgumentException(
          context
              + " must be a lowercase dotted/dashed identifier of at most "
              + MAX_LENGTH
              + " chars: "
              + value);
    }
    return value;
  }
}
