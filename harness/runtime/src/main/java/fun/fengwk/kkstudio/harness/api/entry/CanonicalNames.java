package fun.fengwk.kkstudio.harness.api.entry;

import java.util.Objects;

/**
 * 稳定名称（Agent / Model / Variant / 人工输入 actor）的 canonical 校验，是这条规则唯一的实现。
 *
 * <p>名称必须非 null、非 blank、无首尾空白且不超过 {@link #MAX_NAME_CHARS} 个字符；超长直接拒绝，绝不截断。Agent name、 command
 * payload 与 durable 回执共用本规则，否则同一值会通过一处却在另一处失败。
 */
public final class CanonicalNames {

  /** 稳定名称的字符上限。 */
  public static final int MAX_NAME_CHARS = 128;

  private CanonicalNames() {}

  /**
   * 校验并返回 canonical 名称。
   *
   * @param value 原始名称，不能为 null
   * @param field 出现在异常文案中的字段名
   */
  public static String requireName(String value, String field) {
    Objects.requireNonNull(value, field);
    if (value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    if (!value.equals(value.strip())) {
      throw new IllegalArgumentException(field + " must not contain surrounding whitespace");
    }
    if (value.length() > MAX_NAME_CHARS) {
      throw new IllegalArgumentException(field + " must be <= " + MAX_NAME_CHARS + " characters");
    }
    return value;
  }
}
