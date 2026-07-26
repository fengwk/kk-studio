package fun.fengwk.kkstudio.studio.canvas;

import java.util.Objects;

/**
 * Canvas node. {@code canvasId} lives outside this record because it is the parent aggregate handle
 * managed outside the value object. {@code dataJson} carries the FUNCTION/RESOURCE subtype payload.
 *
 * <p>Invariants enforced by the canonical constructor:
 *
 * <ul>
 *   <li>{@code id > 0}
 *   <li>{@code kind} is non-null; {@code nodeType}, {@code name}, {@code dataJson} are non-blank
 *   <li>{@code transform} is non-null (its own fields are validated separately)
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
