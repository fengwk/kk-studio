package fun.fengwk.kkstudio.studio.canvas;

import java.util.Objects;

/**
 * Canvas node。{@code canvasId} 位于本 record 之外，因为它是父 aggregate 的句柄，由 value object 外部管理。{@code
 * dataJson} 承载 FUNCTION/RESOURCE 子类型的负载。
 *
 * <p>由规范构造函数强制的不变量：
 *
 * <ul>
 *   <li>{@code id > 0}
 *   <li>{@code kind} 非 null；{@code nodeType}、{@code name}、{@code dataJson} 非空白
 *   <li>{@code transform} 非 null（其自身字段会被单独校验）
 * </ul>
 */
public record CanvasNode(
    long id,
    CanvasNodeKind kind,
    String nodeType,
    String name,
    NodeTransform transform,
    String dataJson) {

  public CanvasNode {
    if (id <= 0L) {
      throw new IllegalArgumentException("id must be > 0");
    }
    Objects.requireNonNull(kind, "kind");
    requireNonBlank(nodeType, "nodeType");
    requireNonBlank(name, "name");
    Objects.requireNonNull(transform, "transform");
    requireNonBlank(dataJson, "dataJson");
  }

  private static void requireNonBlank(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
  }
}
