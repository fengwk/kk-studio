package fun.fengwk.kkstudio.harness.runtime.thread.command;

import java.util.Objects;

/** 用于稳定名称与 client command ID 的小型 command 包工具类。 */
final class CommandValueValidation {

  private CommandValueValidation() {}

  static String requireCanonicalName(String value, String field) {
    Objects.requireNonNull(value, field);
    if (value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    if (!value.equals(value.strip())) {
      throw new IllegalArgumentException(field + " must not contain surrounding whitespace");
    }
    if (value.length() > 128) {
      throw new IllegalArgumentException(field + " must be <= 128 characters");
    }
    return value;
  }
}
