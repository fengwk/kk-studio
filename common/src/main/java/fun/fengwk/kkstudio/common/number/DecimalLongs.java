package fun.fengwk.kkstudio.common.number;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Canonical ASCII 十进制 long 文本。
 *
 * <p>只接受无前后空白、无符号、无前导零的十进制数字，且结果落在 {@code long} 范围内。{@code 0} 是唯一合法的零；正数形态必须从 {@code 1}–{@code 9}
 * 开始。
 */
public final class DecimalLongs {

  private static final Pattern NON_NEGATIVE = Pattern.compile("0|[1-9][0-9]*");
  private static final Pattern POSITIVE = Pattern.compile("[1-9][0-9]*");

  private DecimalLongs() {}

  /** 解析非负 canonical 十进制，范围为 {@code 0} 到 {@link Long#MAX_VALUE}。 */
  public static long parseNonNegative(String value, String field) {
    return parse(value, field, true);
  }

  /** 解析正 canonical 十进制，范围为 {@code 1} 到 {@link Long#MAX_VALUE}。 */
  public static long parsePositive(String value, String field) {
    return parse(value, field, false);
  }

  private static long parse(String value, String field, boolean allowZero) {
    Objects.requireNonNull(field, "field");
    if (value == null) {
      throw new IllegalArgumentException(field + " must not be null");
    }
    if (!(allowZero ? NON_NEGATIVE : POSITIVE).matcher(value).matches()) {
      String expectation =
          allowZero ? "a canonical non-negative decimal" : "a canonical positive decimal";
      throw new IllegalArgumentException(field + " must be " + expectation + ": " + value);
    }
    try {
      return Long.parseLong(value);
    } catch (NumberFormatException error) {
      throw new IllegalArgumentException(field + " exceeds long range: " + value, error);
    }
  }
}
