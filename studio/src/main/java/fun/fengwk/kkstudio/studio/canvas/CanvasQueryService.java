package fun.fengwk.kkstudio.studio.canvas;

import java.util.List;
import java.util.Optional;

/** Canvas documents 的读端口。 */
public interface CanvasQueryService {

  Optional<CanvasSnapshot> findSnapshot(long canvasId);

  List<CanvasDocument> listDocuments();
}
