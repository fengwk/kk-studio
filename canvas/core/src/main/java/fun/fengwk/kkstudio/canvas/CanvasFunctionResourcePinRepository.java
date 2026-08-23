package fun.fengwk.kkstudio.canvas;

import java.util.List;
import java.util.UUID;

/** Function Run 资源 pin 的持久化端口。 */
public interface CanvasFunctionResourcePinRepository {

  void addAll(List<CanvasFunctionResourcePin> refs);

  List<CanvasFunctionResourcePin> findByRun(UUID canvasId, UUID nodeId, UUID requestId);

  List<CanvasFunctionResourcePin> findByNode(UUID canvasId, UUID nodeId);

  int countByResource(UUID canvasId, UUID resourceId);

  List<CanvasFunctionResourcePin> findRunningOutputPins(UUID canvasId, UUID resourceId);

  boolean deleteByRun(UUID canvasId, UUID nodeId, UUID requestId);

  int deleteByNode(UUID canvasId, UUID nodeId);

  int deleteByCanvas(UUID canvasId);
}
