package fun.fengwk.kkstudio.studio.canvas;

/**
 * World-space geometry for a Canvas node.
 *
 * <p>Invariants enforced by the canonical constructor:
 *
 * <ul>
 *   <li>{@code x, y} are finite numbers (no NaN / ±∞)
 *   <li>{@code width, height} are finite and strictly positive
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
