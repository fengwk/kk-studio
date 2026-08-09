package fun.fengwk.kkstudio.studio.canvas;

import java.util.List;
import java.util.Objects;

/** Canvas 中唯一的业务节点形态。 */
public record CanvasResourceNode(
    long id,
    long canvasId,
    String name,
    CanvasTransform transform,
    Long groupId,
    List<CanvasResource> resources,
    CanvasFunction function,
    CanvasFunctionRun run) {

  public CanvasResourceNode {
    CanvasValidation.requirePositive(id, "id");
    CanvasValidation.requirePositive(canvasId, "canvasId");
    CanvasValidation.requireNonBlank(name, "name");
    Objects.requireNonNull(transform, "transform");
    if (groupId != null) {
      CanvasValidation.requirePositive(groupId, "groupId");
    }
    Objects.requireNonNull(resources, "resources");
    resources = List.copyOf(resources);
    if (function == null && resources.isEmpty()) {
      throw new IllegalArgumentException("ordinary resource node must contain resources");
    }
    CanvasResourceKind resourceKind = null;
    for (CanvasResource resource : resources) {
      if (resource.canvasId() != canvasId) {
        throw new IllegalArgumentException("node resources must belong to the same canvas");
      }
      if (resourceKind == null) {
        resourceKind = resource.kind();
      } else if (resourceKind != resource.kind()) {
        throw new IllegalArgumentException("node resources must have the same kind");
      }
    }
    if (run != null && run.nodeId() != id) {
      throw new IllegalArgumentException("run must belong to the node");
    }
    if (run != null && function == null) {
      throw new IllegalArgumentException("ordinary resource node cannot have a run");
    }
  }
}
