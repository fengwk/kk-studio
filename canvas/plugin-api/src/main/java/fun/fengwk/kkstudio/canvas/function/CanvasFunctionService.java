package fun.fengwk.kkstudio.canvas.function;

import fun.fengwk.kkstudio.canvas.CanvasFunctionRun;

import java.util.UUID;

/** Canvas Function run 的 Web 可用应用服务端口。 */
public interface CanvasFunctionService {

  CanvasFunctionRun start(UUID canvasId, UUID nodeId, String requestId);

  CanvasFunctionRun get(UUID canvasId, UUID nodeId);

  CanvasFunctionRun cancel(UUID canvasId, UUID nodeId, String requestId);

  /**
   * 人工核查 {@code UNKNOWN} Run 后解除：把 Run 收敛为继续查询原任务或确定的失败/取消终态。
   *
   * <p>{@code verification} 是人工核查事实的文本记录，必须非空；RESUME 只回到 {@code READY} 并复用已持久化的提交事实，不会重新提交。
   */
  CanvasFunctionRun resolve(
      UUID canvasId,
      UUID nodeId,
      String requestId,
      CanvasFunctionUnknownResolution resolution,
      String verification);
}
