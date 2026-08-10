package fun.fengwk.kkstudio.studio.canvas;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Function 节点当前/最后一次 Run 的基础持久化端口。 */
public interface CanvasFunctionRunRepository {

  Optional<CanvasFunctionRun> findByNodeId(long nodeId);

  Optional<CanvasFunctionRun> findByNodeIdForUpdate(long nodeId);

  List<CanvasFunctionRun> findByCanvasId(long canvasId);

  List<CanvasFunctionRun> findRunning();

  void insertRunning(CanvasFunctionRun run);

  boolean replaceTerminalWithRunning(CanvasFunctionRun run);

  boolean checkpoint(
      long nodeId, String requestId, String stateJson, String stage, Instant updatedAt);

  boolean transitionTerminal(CanvasFunctionRun run);
}
