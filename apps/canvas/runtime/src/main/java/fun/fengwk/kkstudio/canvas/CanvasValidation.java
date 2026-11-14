package fun.fengwk.kkstudio.canvas;

import fun.fengwk.kkstudio.common.error.DomainException;

import java.util.List;
import java.util.UUID;

/** 领域与命令的共享校验，保证领域各处对名称、正文与集合的处理一致。 */
final class CanvasValidation {

  /** 节点、group 与 function 名称的最大长度（与数据库列一致）。 */
  static final int MAX_NAME_LENGTH = 256;

  /** Resource 名称的最大长度（与数据库列一致）。 */
  static final int MAX_RESOURCE_NAME_LENGTH = 512;

  private CanvasValidation() {}

  static String requireNonBlank(String value, String field) {
    if (value == null || value.isBlank()) {
      throw DomainException.validation(field, field + " must not be blank");
    }
    return value;
  }

  /**
   * 名称校验：去除首尾空白、非空、无控制字符且不超过 {@link #MAX_NAME_LENGTH}。
   *
   * <p>显示名保留大小写与字符形态，唯一性由 {@link CanvasNames#key(String)} 归一化后判断。
   */
  static String requireName(String value, String field) {
    return requireText(value, field, MAX_NAME_LENGTH);
  }

  /** Resource 名称校验，长度上限为 {@link #MAX_RESOURCE_NAME_LENGTH}。 */
  static String requireResourceName(String value, String field) {
    return requireText(value, field, MAX_RESOURCE_NAME_LENGTH);
  }

  static String requireText(String value, String field, int maxLength) {
    if (value == null) {
      throw DomainException.validation(field, field + " must not be null");
    }
    String trimmed = value.strip();
    if (trimmed.isEmpty()) {
      throw DomainException.validation(field, field + " must not be blank");
    }
    if (trimmed.length() > maxLength) {
      throw DomainException.validation(
          field, field + " must not exceed " + maxLength + " characters");
    }
    for (int index = 0; index < trimmed.length(); index++) {
      if (Character.isISOControl(trimmed.charAt(index))) {
        throw DomainException.validation(field, field + " must not contain control characters");
      }
    }
    return trimmed;
  }

  static <T> List<T> requireList(List<T> values, String field) {
    if (values == null) {
      throw DomainException.validation(field, field + " must not be null");
    }
    for (T value : values) {
      if (value == null) {
        throw DomainException.validation(field, field + " must not contain null elements");
      }
    }
    return List.copyOf(values);
  }

  static void requireDistinct(List<UUID> values, String field) {
    if (values.stream().distinct().count() != values.size()) {
      throw DomainException.validation(field, field + " must not contain duplicates");
    }
  }
}
