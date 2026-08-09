package fun.fengwk.kkstudio.studio.canvas;

import java.util.Objects;

final class CanvasValidation {

  private CanvasValidation() {}

  static void requirePositive(long value, String name) {
    if (value <= 0L) {
      throw new IllegalArgumentException(name + " must be > 0");
    }
  }

  static String requireNonBlank(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }

  static <T> T requireNonNull(T value, String name) {
    return Objects.requireNonNull(value, name);
  }
}
