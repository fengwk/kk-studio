package fun.fengwk.kkstudio.studio.canvas;

/** Canvas/world 绝对坐标几何。 */
public record CanvasTransform(double x, double y, double width, double height) {

  public CanvasTransform {
    requireFinite(x, "x");
    requireFinite(y, "y");
    requireFinite(width, "width");
    requireFinite(height, "height");
    if (width <= 0d) {
      throw new IllegalArgumentException("width must be > 0");
    }
    if (height <= 0d) {
      throw new IllegalArgumentException("height must be > 0");
    }
  }

  private static void requireFinite(double value, String name) {
    if (!Double.isFinite(value)) {
      throw new IllegalArgumentException(name + " must be finite");
    }
  }
}
