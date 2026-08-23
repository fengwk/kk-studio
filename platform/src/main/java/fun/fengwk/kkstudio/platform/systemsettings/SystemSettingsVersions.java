package fun.fengwk.kkstudio.platform.systemsettings;

import java.util.regex.Pattern;

/**
 * System settings 上乐观锁版本的十进制字符串表示。
 *
 * <p>版本是非负十进制字符串（"0"、"1"、"2"、……），在 HTTP / DTO 边界暴露。负数、空白、非十进制或越界值作为校验失败拒绝， 保证 CAS 永远看不到不可解析的令牌。
 */
public final class SystemSettingsVersions {

  private static final Pattern DECIMAL = Pattern.compile("^(0|[1-9][0-9]*)$");

  private SystemSettingsVersions() {}

  /** 解析十进制字符串版本。成功时返回解析后的 long。 */
  public static long parse(String value, String field) {
    if (value == null) {
      throw new SystemSettingsValidationException(
          field, field + " must not be null (expectedVersion is required)");
    }
    if (!value.equals(value.trim())) {
      throw new SystemSettingsValidationException(
          field, field + " must be a canonical non-negative decimal string: " + value);
    }
    if (value.isEmpty()) {
      throw new SystemSettingsValidationException(field, field + " must not be blank");
    }
    if (!DECIMAL.matcher(value).matches()) {
      throw new SystemSettingsValidationException(
          field, field + " must be a non-negative decimal string: " + value);
    }
    try {
      return Long.parseLong(value);
    } catch (NumberFormatException error) {
      throw new SystemSettingsValidationException(
          field, field + " exceeds long range: " + value, error);
    }
  }

  /** 把非负内部版本格式化为其 canonical 十进制字符串。 */
  public static String format(long value) {
    if (value < 0) {
      throw new IllegalStateException("system settings version must not be negative: " + value);
    }
    return Long.toString(value);
  }
}
