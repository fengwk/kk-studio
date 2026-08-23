package fun.fengwk.kkstudio.canvas.function;

import fun.fengwk.kkstudio.canvas.CanvasFunctionRun;

import java.util.UUID;

/** Canvas Function run 的 Web 可用应用服务端口。 */
public interface CanvasFunctionService {

  CanvasFunctionRun start(UUID canvasId, UUID nodeId, String requestId);

  CanvasFunctionRun get(UUID canvasId, UUID nodeId);

  CanvasFunctionRun cancel(UUID canvasId, UUID nodeId, String requestId);
}
