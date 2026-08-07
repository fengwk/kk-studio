package fun.fengwk.kkstudio.studio.canvas;

/**
 * Canvas node 的世界空间几何。
 *
 * <p>由规范构造函数强制的不变量：
 *
 * <ul>
 *   <li>{@code x, y} 为有限数值（不允许 NaN / ±∞）
 *   <li>{@code width, height} 为有限且严格为正
 * </ul>
 */
public record NodeTransform(double x, double y, double width, double height) {

  public NodeTransform {
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
    if (Double.isNaN(value) || Double.isInfinite(value)) {
      throw new IllegalArgumentException(name + " must be finite");
    }
  }
}
