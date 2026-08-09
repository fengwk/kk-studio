package fun.fengwk.kkstudio.core.studio.resource;

import fun.fengwk.kkstudio.studio.canvas.CanvasResourceKind;

import java.io.InputStream;

/** 流式落盘、真实媒体 probe 与 preview 生成端口。 */
public interface CanvasMediaProcessor {

  CanvasProcessedMedia process(CanvasResourceKind kind, InputStream content, long expectedSize);
}
