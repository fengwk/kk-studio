package fun.fengwk.kkstudio.studio.canvas;

import java.io.InputStream;

/**
 * 将 provider/adapters 产出的媒体流物化为不可变 Canvas Resource。
 *
 * <p>调用方预先分配 {@code resourceId}；相同 id 的重复调用返回既有 Resource。
 */
public interface CanvasResourceMaterializer {

  CanvasResource materialize(
      long canvasId,
      long resourceId,
      CanvasResourceKind kind,
      String name,
      String mediaType,
      long size,
      InputStream content);
}
