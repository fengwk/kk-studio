package fun.fengwk.kkstudio.studio.canvas;

import java.util.Optional;

/** Upload finalize 实现可复用的基础持久化端口；本切片不暴露上传 API。 */
public interface CanvasUploadRepository {

  void add(CanvasUpload upload);

  Optional<CanvasUpload> findById(long canvasId, long uploadId);
}
