package fun.fengwk.kkstudio.canvas;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Canvas Resource owner、Function pin 与 Blob 引用的事务内生命周期端口。
 *
 * <p>调用方必须已经锁定 {@code canvas_document}；pin 只决定无 owner Resource 是否保留，不额外修改 Blob 引用计数。
 */
public interface CanvasResourceLifecycle {

  /** 释放指定 Run 的全部 pin，并回收因此失去最后一个 pin 的无 owner Resource。 */
  void releaseRunPins(UUID canvasId, UUID nodeId, UUID requestId);

  /** 释放节点全部 Run 的 pin，并回收因此失去最后一个 pin 的无 owner Resource。 */
  void releaseNodePins(UUID canvasId, UUID nodeId);

  /** 清空画布全部 pin；随后的 Resource 由 {@link #deleteCanvasResources} 回收。 */
  void releaseCanvasPins(UUID canvasId);

  /**
   * Function 成功时按冻结顺序把全部输出挂到节点：slot {@code i} 的 resource 挂到 index {@code i}。旧 owned 资源仍被其他 Run pin
   * 则解除挂接，否则删除。返回实际挂接后的 Resource 列表。
   */
  List<CanvasResource> replaceOwnedWithTargets(
      UUID canvasId, UUID nodeId, List<UUID> orderedTargetResourceIds);

  /** 失败、取消或迟到结果清理：只有无 owner 的目标 Resource 会被删除，保留 OUTPUT pin 本身。 */
  void discardUnownedTargets(UUID canvasId, Collection<UUID> targetResourceIds);

  /** 删除指定 Resource 行并释放其 Blob 引用；调用方须已确认它不再被节点持有且无 pin。 */
  void discardResource(UUID canvasId, UUID resourceId);

  /** 画布深删除：调用方须先删除全部 pin，随后删除每个 Resource 行并释放其 Blob 引用。 */
  void deleteCanvasResources(UUID canvasId);
}
