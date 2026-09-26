package fun.fengwk.kkstudio.harness.runtime.input;

import java.util.Objects;

/** 人工输入值对象的 canonical 文本校验：非空、无首尾空白、有界长度。 */
final class HumanInputTexts {

  private HumanInputTexts() {}

  /** 必填文本：非 null、strip 后非空、等于 strip 结果且长度有界。 */
  static String requireText(String value, String field, int maxCharacters) {
    Objects.requireNonNull(value, field);
    if (!value.equals(value.strip())) {
      throw new IllegalArgumentException(field + " must not contain surrounding whitespace");
    }
    if (value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    if (value.length() > maxCharacters) {
      throw new IllegalArgumentException(field + " must be <= " + maxCharacters + " characters");
    }
    return value;
  }

  /** 可空文本：null 原样返回，非 null 走 {@link #requireText}。 */
  static String nullableText(String value, String field, int maxCharacters) {
    if (value == null) {
      return null;
    }
    return requireText(value, field, maxCharacters);
  }
}
