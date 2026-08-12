package fun.fengwk.kkstudio.studio.canvas;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Canvas documents 的读端口。 */
public interface CanvasQueryService {

  Optional<CanvasSnapshot> findSnapshot(UUID canvasId);

  List<CanvasDocument> listDocuments();
}
