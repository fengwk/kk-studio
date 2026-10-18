package fun.fengwk.kkstudio.canvas.function;

import fun.fengwk.kkstudio.canvas.CanvasFunctionRun;

import java.util.UUID;

/** Canvas Function run 的 Web 可用应用服务端口。 */
public interface CanvasFunctionService {

  /**
   * 接受一次 Function 执行；{@code expectedVersion} 是客户端从目录看到的函数版本，必须非空。
   *
   * <p>精确 {@code requestId} 重放即使 expectedVersion 过期也直接返回已接受结果；非重放在同一目录租用内比较该函数定义 fingerprint，不一致返回
   * 409，绝不按函数名静默换成当前版本。
   */
  CanvasFunctionRun start(
      UUID canvasId, UUID nodeId, String requestId, CanvasFunctionVersion expectedVersion);

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
