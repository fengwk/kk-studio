package fun.fengwk.kkstudio.canvas;

import java.util.Objects;
import java.util.UUID;

/**
 * 读取投影出的引用连线：由消费节点 args 中指向上游节点输出位置的引用产生，不单独持久化。
 *
 * <p>{@code sourceNodeId} 是提供输出的上游节点，{@code targetNodeId} 是持有引用的消费节点，{@code index} 是上游节点从零开始的输出位置。
 */
public record CanvasReference(UUID canvasId, UUID sourceNodeId, UUID targetNodeId, int index) {

  public CanvasReference {
    Objects.requireNonNull(canvasId, "canvasId");
    Objects.requireNonNull(sourceNodeId, "sourceNodeId");
    Objects.requireNonNull(targetNodeId, "targetNodeId");
    if (index < 0) {
      throw new IllegalArgumentException("index must be >= 0");
    }
    if (sourceNodeId.equals(targetNodeId)) {
      throw new IllegalArgumentException("sourceNodeId must differ from targetNodeId");
    }
  }
}
