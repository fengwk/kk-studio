package fun.fengwk.kkstudio.canvas.function;

/** Frozen 计划共用的参数与文本校验支撑。 */
final class CanvasFunctionFrozenSupport {

  private CanvasFunctionFrozenSupport() {}

  static void requireText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
  }
}
