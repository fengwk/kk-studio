package fun.fengwk.kkstudio.studio.canvas;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Canvas 中唯一的业务节点形态。 */
public record CanvasResourceNode(
    UUID id,
    UUID canvasId,
    String name,
    CanvasTransform transform,
    UUID groupId,
    List<CanvasResource> resources,
    CanvasFunction function,
    CanvasFunctionRun run) {

  public CanvasResourceNode {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(canvasId, "canvasId");
    CanvasValidation.requireNonBlank(name, "name");
    Objects.requireNonNull(transform, "transform");
    Objects.requireNonNull(resources, "resources");
    resources = List.copyOf(resources);
    if (function == null && resources.isEmpty()) {
      throw new IllegalArgumentException("ordinary resource node must contain resources");
    }
    for (CanvasResource resource : resources) {
      if (!resource.canvasId().equals(canvasId)) {
        throw new IllegalArgumentException("node resources must belong to the same canvas");
      }
      if (!Objects.equals(resource.ownerNodeId(), id)) {
        throw new IllegalArgumentException("node resources must be owned by the node");
      }
      if (resource.isText() != resources.get(0).isText()) {
        throw new IllegalArgumentException("node resources must have the same content kind");
      }
    }
    if (run != null && !run.nodeId().equals(id)) {
      throw new IllegalArgumentException("run must belong to the node");
    }
    if (run != null && function == null) {
      throw new IllegalArgumentException("ordinary resource node cannot have a run");
    }
  }
}
