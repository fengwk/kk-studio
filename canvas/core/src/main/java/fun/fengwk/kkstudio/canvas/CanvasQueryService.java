package fun.fengwk.kkstudio.canvas;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Canvas documents 的读端口：返回摘要、当前事实与由 args 投影出的引用连线。 */
public interface CanvasQueryService {

  Optional<CanvasSnapshot> findSnapshot(UUID canvasId);

  List<CanvasDocument> listDocuments();
}
