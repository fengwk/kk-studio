package fun.fengwk.kkstudio.harness.environment.daemon;

import tools.jackson.databind.JsonNode;

import java.util.regex.Pattern;

/** Wire 上规范十进制字符串与安全整数的共享校验。 */
final class DaemonWireNumbers {

  private static final Pattern POSITIVE_DECIMAL = Pattern.compile("^[1-9][0-9]*$");

  private DaemonWireNumbers() {}

  static long positiveLong(String value, String field) {
    if (value == null || !POSITIVE_DECIMAL.matcher(value).matches()) {
      throw new IllegalArgumentException(field + " must be a canonical positive decimal string");
    }
    try {
      return Long.parseLong(value);
    } catch (NumberFormatException error) {
      throw new IllegalArgumentException(field + " exceeds long range", error);
    }
  }

  static long safeNonNegative(JsonNode value, String field) {
    if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
      throw new IllegalArgumentException(field + " must be an integer");
    }
    long number = value.longValue();
    if (number < 0 || number > DaemonUpdateProgress.MAX_SAFE_BYTE_COUNT) {
      throw new IllegalArgumentException(field + " is outside the safe integer range");
    }
    return number;
  }
}
