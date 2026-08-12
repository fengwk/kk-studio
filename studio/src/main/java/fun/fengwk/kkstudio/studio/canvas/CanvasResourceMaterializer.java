package fun.fengwk.kkstudio.studio.canvas;

import java.io.InputStream;
import java.util.UUID;

/**
 * 将 provider/adapters 产出的媒体流物化为不可变 Canvas Resource。
 *
 * <p>调用方预先分配 {@code resourceId}；相同 id 的重复调用返回既有 Resource。内容由全局 Storage 持有，物化后资源以 blobId 引用。
 */
public interface CanvasResourceMaterializer {

  CanvasResource materialize(UUID canvasId, UUID resourceId, String name, InputStream content);
}
