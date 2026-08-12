package fun.fengwk.kkstudio.studio.canvas;

import java.util.Objects;
import java.util.UUID;

/**
 * Function Run 生命周期内对资源的 pin：INPUT 为启动时冻结的引用资源，OUTPUT 为预分配的目标资源。
 *
 * <p>Run 启动时 pin 其 manifest 与目标资源，Run 被覆盖或节点删除时按 {@code (canvasId, nodeId, requestId)}
 * 释放，不引入通用引用计数。身份为 {@code (canvasId, nodeId, requestId, role, resourceId)}，便于持久化层按 canvas 组合外键约束。
 */
public record CanvasFunctionResourceRef(
    UUID canvasId, UUID nodeId, UUID requestId, UUID resourceId, Role role) {

  public CanvasFunctionResourceRef {
    Objects.requireNonNull(canvasId, "canvasId");
    Objects.requireNonNull(nodeId, "nodeId");
    Objects.requireNonNull(requestId, "requestId");
    Objects.requireNonNull(resourceId, "resourceId");
    Objects.requireNonNull(role, "role");
  }

  /** pin 的角色。 */
  public enum Role {
    INPUT,
    OUTPUT
  }
}
