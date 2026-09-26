package fun.fengwk.kkstudio.canvas;

import java.util.Objects;
import java.util.UUID;

/**
 * 命令批规划出的持久化步骤，按声明顺序执行。
 *
 * <p>规划在 Core 内完成并产出可比较的步骤序列，外层只负责按顺序落到 document 锁内的短事务：document → node/run → resource → blob。
 * 步骤只描述行级变化，不携带冲突语义。
 */
public sealed interface CanvasMutation
    permits CanvasMutation.InsertNode,
        CanvasMutation.UpdateNode,
        CanvasMutation.DeleteNode,
        CanvasMutation.InsertResource,
        CanvasMutation.AttachResource,
        CanvasMutation.DetachResource,
        CanvasMutation.DeleteResource,
        CanvasMutation.InsertGroup,
        CanvasMutation.UpdateGroup,
        CanvasMutation.DeleteGroup,
        CanvasMutation.ReleaseNodePins,
        CanvasMutation.DeleteFunctionRun {

  /** 插入节点行（含可空 Function）。 */
  record InsertNode(
      UUID nodeId, String name, CanvasTransform transform, UUID groupId, CanvasFunction function)
      implements CanvasMutation {

    public InsertNode {
      Objects.requireNonNull(nodeId, "nodeId");
      Objects.requireNonNull(name, "name");
      Objects.requireNonNull(transform, "transform");
    }
  }

  /** 写入节点的名称、几何、分组与 Function 全行值。 */
  record UpdateNode(
      UUID nodeId, String name, CanvasTransform transform, UUID groupId, CanvasFunction function)
      implements CanvasMutation {

    public UpdateNode {
      Objects.requireNonNull(nodeId, "nodeId");
      Objects.requireNonNull(name, "name");
      Objects.requireNonNull(transform, "transform");
    }
  }

  /** 删除节点行；调用方须先完成 pin、Run 与 Resource 的清理。 */
  record DeleteNode(UUID nodeId) implements CanvasMutation {

    public DeleteNode {
      Objects.requireNonNull(nodeId, "nodeId");
    }
  }

  /** 插入已归属于节点的 Resource 行（内容与 owner/index 在同一条语句中固定）。 */
  record InsertResource(CanvasResource resource) implements CanvasMutation {

    public InsertResource {
      Objects.requireNonNull(resource, "resource");
    }
  }

  /** 把无 owner 的既有 Resource 挂接到节点槽位。 */
  record AttachResource(UUID resourceId, UUID nodeId, int resourceIndex) implements CanvasMutation {

    public AttachResource {
      Objects.requireNonNull(resourceId, "resourceId");
      Objects.requireNonNull(nodeId, "nodeId");
      if (resourceIndex < 0) {
        throw new IllegalArgumentException("resourceIndex must be >= 0");
      }
    }
  }

  /** 解除 Resource 的节点的挂接但保留行与内容：仍被 Run pin 的历史资源不被删除。 */
  record DetachResource(UUID resourceId, UUID nodeId) implements CanvasMutation {

    public DetachResource {
      Objects.requireNonNull(resourceId, "resourceId");
      Objects.requireNonNull(nodeId, "nodeId");
    }
  }

  /** 删除不再被节点持有且无 pin 的 Resource 行，并释放其 Blob 引用。 */
  record DeleteResource(UUID resourceId) implements CanvasMutation {

    public DeleteResource {
      Objects.requireNonNull(resourceId, "resourceId");
    }
  }

  /** 插入分组行。 */
  record InsertGroup(CanvasGroup group) implements CanvasMutation {

    public InsertGroup {
      Objects.requireNonNull(group, "group");
    }
  }

  /** 写入分组的标题与几何全行值。 */
  record UpdateGroup(CanvasGroup group) implements CanvasMutation {

    public UpdateGroup {
      Objects.requireNonNull(group, "group");
    }
  }

  /** 删除分组行；调用方须先解除成员的归属。 */
  record DeleteGroup(UUID groupId) implements CanvasMutation {

    public DeleteGroup {
      Objects.requireNonNull(groupId, "groupId");
    }
  }

  /** 释放节点全部 Run 的 pin，并回收因此失去最后一个 pin 的无 owner Resource。 */
  record ReleaseNodePins(UUID nodeId) implements CanvasMutation {

    public ReleaseNodePins {
      Objects.requireNonNull(nodeId, "nodeId");
    }
  }

  /** 删除节点当前/最后一次 Run 行。 */
  record DeleteFunctionRun(UUID nodeId) implements CanvasMutation {

    public DeleteFunctionRun {
      Objects.requireNonNull(nodeId, "nodeId");
    }
  }
}
