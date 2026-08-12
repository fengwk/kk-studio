package fun.fengwk.kkstudio.studio.canvas;

final class CanvasValidation {

  private CanvasValidation() {}

  static String requireNonBlank(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }
}
