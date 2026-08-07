package fun.fengwk.kkstudio.studio.canvas;

/**
 * Canvas aggregate：id、title、revision 与默认 viewport。
 *
 * <p>由规范构造函数强制的不变量：
 *
 * <ul>
 *   <li>{@code id > 0}
 *   <li>{@code title} 非空白
 *   <li>{@code revision >= 0}
 *   <li>{@code homeViewportJson} 非空白
 * </ul>
 */
public record CanvasDocument(long id, String title, long revision, String homeViewportJson) {

  public CanvasDocument {
    if (id <= 0L) {
      throw new IllegalArgumentException("id must be > 0");
    }
    requireNonBlank(title, "title");
    if (revision < 0L) {
      throw new IllegalArgumentException("revision must be >= 0");
    }
    requireNonBlank(homeViewportJson, "homeViewportJson");
  }

  private static void requireNonBlank(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
  }
}
