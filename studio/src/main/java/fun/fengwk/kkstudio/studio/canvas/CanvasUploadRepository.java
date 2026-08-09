package fun.fengwk.kkstudio.studio.canvas;

import java.util.Optional;

/** Upload reserve/finalize 的持久化端口。 */
public interface CanvasUploadRepository {

  void add(CanvasUpload upload);

  Optional<CanvasUpload> findById(long canvasId, long uploadId);

  Optional<CanvasUpload> findByIdForUpdate(long canvasId, long uploadId);

  boolean delete(long canvasId, long uploadId);
}
