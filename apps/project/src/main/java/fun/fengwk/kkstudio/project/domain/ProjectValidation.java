package fun.fengwk.kkstudio.project.domain;

import java.util.Objects;

/** Project 领域值对象的共享边界校验：显示名、正文与 Agent/Environment 规范名。 */
final class ProjectValidation {

  /** agent_definition.name 与 environment.name 的列宽约束。 */
  private static final int MAX_CANONICAL_NAME_LENGTH = 64;

  private ProjectValidation() {}

  /** 显示名：非空白且无首尾空白。 */
  static String requireDisplayName(String value, String field) {
    Objects.requireNonNull(value, field);
    if (value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    if (!value.equals(value.strip())) {
      throw new IllegalArgumentException(field + " must not have surrounding whitespace");
    }
    return value;
  }

  /** 正文：非空白；null 表示没有该事实，由调用方通过 optional 变体表达。 */
  static String requireText(String value, String field) {
    Objects.requireNonNull(value, field);
    if (value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    return value;
  }

  /** Agent/Environment 规范名：非空白、无首尾空白、不含 {@code '/'}，长度符合名称列。 */
  static String requireCanonicalName(String value, String field) {
    requireDisplayName(value, field);
    if (value.length() > MAX_CANONICAL_NAME_LENGTH) {
      throw new IllegalArgumentException(
          field + " must contain at most " + MAX_CANONICAL_NAME_LENGTH + " characters");
    }
    if (value.indexOf('/') >= 0) {
      throw new IllegalArgumentException(field + " must not contain '/'");
    }
    return value;
  }

  /** 可空正文：null 原样返回，否则必须是正文。 */
  static String optionalText(String value, String field) {
    return value == null ? null : requireText(value, field);
  }

  /** 可空规范名：null 原样返回，否则必须是规范名。 */
  static String optionalCanonicalName(String value, String field) {
    return value == null ? null : requireCanonicalName(value, field);
  }
}
