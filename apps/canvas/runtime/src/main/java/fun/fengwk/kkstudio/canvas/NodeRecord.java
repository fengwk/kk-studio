package fun.fengwk.kkstudio.canvas;

import java.util.Objects;
import java.util.UUID;

/** 可变 node 行状态。 */
public record NodeRecord(
    UUID id,
    UUID canvasId,
    String name,
    CanvasTransform transform,
    UUID groupId,
    CanvasFunction function) {

  public NodeRecord {
    id = Objects.requireNonNull(id, "id");
    canvasId = Objects.requireNonNull(canvasId, "canvasId");
    name = Objects.requireNonNull(name, "name");
    transform = Objects.requireNonNull(transform, "transform");
  }
}
