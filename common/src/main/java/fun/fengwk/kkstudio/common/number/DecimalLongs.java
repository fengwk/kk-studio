package fun.fengwk.kkstudio.common.number;

import java.util.Objects;

/**
 * Canonical ASCII 十进制 long 文本。
 *
 * <p>只接受无前后空白、无符号、无前导零的十进制数字，且结果落在 {@code long} 范围内。{@code 0} 是唯一合法的零；正数形态必须从 {@code 1}–{@code 9}
 * 开始。
 */
public final class DecimalLongs {

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
    if (value.isEmpty()) {
      throw invalid(field, value, allowZero);
    }
    int start = allowZero && value.charAt(0) == '0' ? 1 : 0;
    if (start == 1) {
      if (value.length() != 1) {
        throw invalid(field, value, allowZero);
      }
      return 0L;
    }
    if (value.charAt(0) < '1' || value.charAt(0) > '9') {
      throw invalid(field, value, allowZero);
    }
    long parsed = 0L;
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (c < '0' || c > '9') {
        throw invalid(field, value, allowZero);
      }
      int digit = c - '0';
      if (parsed > (Long.MAX_VALUE - digit) / 10L) {
        throw new IllegalArgumentException(field + " exceeds long range: " + value);
      }
      parsed = parsed * 10L + digit;
    }
    return parsed;
  }

  private static IllegalArgumentException invalid(String field, String value, boolean allowZero) {
    String expectation =
        allowZero ? "a canonical non-negative decimal" : "a canonical positive decimal";
    return new IllegalArgumentException(field + " must be " + expectation + ": " + value);
  }
}
