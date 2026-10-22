package fun.fengwk.kkstudio.canvas;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Canvas 中唯一的业务节点形态。
 *
 * <p>普通资源节点持有当前资源数组；Function 节点额外持有 {@code {name,args}} 配置与当前/最后一次 Run。资源内容不可变，历史行与 pin 不因节点编辑而改写。
 */
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
      throw new CanvasValidationException("plain resource node must contain resources");
    }
    for (int index = 0; index < resources.size(); index++) {
      CanvasResource resource = resources.get(index);
      if (!resource.canvasId().equals(canvasId)) {
        throw new CanvasValidationException("node resources must belong to the same canvas");
      }
      if (!Objects.equals(resource.ownerNodeId(), id)) {
        throw new CanvasValidationException("node resources must be owned by the node");
      }
      if (!Integer.valueOf(index).equals(resource.resourceIndex())) {
        throw new CanvasValidationException("node resources must occupy consecutive slots");
      }
      if (resource.isText() != resources.get(0).isText()) {
        throw new CanvasValidationException("node resources must have the same content kind");
      }
    }
    if (run != null && !run.nodeId().equals(id)) {
      throw new CanvasValidationException("run must belong to the node");
    }
    if (run != null && function == null) {
      throw new CanvasValidationException("plain resource node cannot have a run");
    }
  }
}
