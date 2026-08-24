package fun.fengwk.kkstudio.canvas;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Function 节点当前/最后一次 Run 的基础持久化端口。 */
public interface CanvasFunctionRunRepository {

  Optional<CanvasFunctionRun> findByNodeId(UUID nodeId);

  Optional<CanvasFunctionRun> findByNodeIdForUpdate(UUID nodeId);

  List<CanvasFunctionRun> findByCanvasId(UUID canvasId);

  void insertReady(CanvasFunctionRun run);

  boolean replaceTerminalWithReady(CanvasFunctionRun run);

  boolean checkpoint(
      UUID nodeId,
      UUID requestId,
      String leaseToken,
      String stateJson,
      String stage,
      Instant updatedAt);

  boolean transitionTerminal(CanvasFunctionRun run, String leaseToken);

  boolean cancelActive(CanvasFunctionRun run);

  boolean deleteByNodeId(UUID nodeId);

  boolean deleteByCanvasId(UUID canvasId);
}
