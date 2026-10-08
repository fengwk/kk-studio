package fun.fengwk.kkstudio.harness.runtime.input;

import java.util.Objects;

/** 人工输入值对象的 canonical 文本校验：非空、无首尾空白；不设人为字符上限。 */
final class HumanInputTexts {

  private HumanInputTexts() {}

  /** 必填文本：非 null、strip 后非空且等于 strip 结果。 */
  static String requireText(String value, String field) {
    Objects.requireNonNull(value, field);
    if (!value.equals(value.strip())) {
      throw new IllegalArgumentException(field + " must not contain surrounding whitespace");
    }
    if (value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    return value;
  }

  /** 可空文本：null 原样返回，非 null 走 {@link #requireText}。 */
  static String nullableText(String value, String field) {
    if (value == null) {
      return null;
    }
    return requireText(value, field);
  }
}
