package fun.fengwk.kkstudio.harness.runtime.thread.command;

import java.util.Objects;

/** Small command-package helper for stable names and client command IDs. */
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
