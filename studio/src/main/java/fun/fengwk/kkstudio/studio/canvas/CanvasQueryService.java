package fun.fengwk.kkstudio.studio.canvas;

import java.util.List;
import java.util.Optional;

/** Read port for Canvas documents. */
public interface CanvasQueryService {

  Optional<CanvasSnapshot> findSnapshot(long canvasId);

  List<CanvasDocument> listDocuments();
}
