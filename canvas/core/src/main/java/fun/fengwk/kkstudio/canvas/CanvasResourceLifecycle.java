package fun.fengwk.kkstudio.canvas;

import java.util.UUID;

/**
 * Canvas Resource owner 与 Function pin 的事务内生命周期端口。
 *
 * <p>调用方必须已经锁定 {@code canvas_document}；具体 Blob 引用释放由外层实现负责。
 */
public interface CanvasResourceLifecycle {

  void releaseRunPins(UUID canvasId, UUID nodeId, UUID requestId);

  void releaseNodePins(UUID canvasId, UUID nodeId);

  void releaseCanvasPins(UUID canvasId);

  void deleteOwnedResources(UUID canvasId, UUID nodeId);

  CanvasResource replaceOwnedWithTarget(UUID canvasId, UUID nodeId, UUID targetResourceId);

  void discardUnownedTarget(UUID canvasId, UUID targetResourceId);

  void deleteCanvasResources(UUID canvasId);
}
