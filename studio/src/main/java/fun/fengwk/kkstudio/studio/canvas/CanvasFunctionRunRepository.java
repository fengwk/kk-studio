package fun.fengwk.kkstudio.studio.canvas;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Function 节点当前/最后一次 Run 的基础持久化端口。 */
public interface CanvasFunctionRunRepository {

  Optional<CanvasFunctionRun> findByNodeId(UUID nodeId);

  Optional<CanvasFunctionRun> findByNodeIdForUpdate(UUID nodeId);

  List<CanvasFunctionRun> findByCanvasId(UUID canvasId);

  List<CanvasFunctionRun> findRunning();

  void insertRunning(CanvasFunctionRun run);

  boolean replaceTerminalWithRunning(CanvasFunctionRun run);

  boolean checkpoint(
      UUID nodeId, UUID requestId, String stateJson, String stage, Instant updatedAt);

  boolean transitionTerminal(CanvasFunctionRun run);
}
