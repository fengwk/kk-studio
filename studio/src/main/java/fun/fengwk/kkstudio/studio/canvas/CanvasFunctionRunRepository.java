package fun.fengwk.kkstudio.studio.canvas;

import java.util.List;
import java.util.Optional;

/** Function 节点当前/最后一次 Run 的基础持久化端口。 */
public interface CanvasFunctionRunRepository {

  Optional<CanvasFunctionRun> findByNodeId(long nodeId);

  List<CanvasFunctionRun> findByCanvasId(long canvasId);

  void save(CanvasFunctionRun run);
}
