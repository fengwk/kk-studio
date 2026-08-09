package fun.fengwk.kkstudio.studio.canvas;

import java.util.List;
import java.util.Optional;

/** Immutable Canvas Resource 的持久化端口。 */
public interface CanvasResourceRepository {

  void add(CanvasResource resource);

  Optional<CanvasResource> findById(long canvasId, long resourceId);

  List<CanvasResource> findByIds(long canvasId, List<Long> resourceIds);
}
