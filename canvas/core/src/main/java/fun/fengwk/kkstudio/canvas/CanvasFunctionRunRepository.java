package fun.fengwk.kkstudio.canvas;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Function 节点当前/最后一次 Run 的基础持久化端口。 */
public interface CanvasFunctionRunRepository {

  Optional<CanvasFunctionRun> findByNodeId(UUID nodeId);

  Optional<CanvasFunctionRun> findByNodeIdForUpdate(UUID nodeId);

  List<CanvasFunctionRun> findByCanvasId(UUID canvasId);

  void insertReady(CanvasFunctionRun run);

  boolean replaceTerminalWithReady(CanvasFunctionRun run);

  boolean checkpoint(
      UUID nodeId,
      UUID requestId,
      String leaseToken,
      String stateJson,
      String stage,
      Instant updatedAt);

  boolean transitionTerminal(CanvasFunctionRun run, String leaseToken);

  /**
   * 外部提交结果不明时把 RUNNING Run 收敛为 UNKNOWN：清租约、保留冻结计划与 pin，退出自动调度。
   *
   * <p>CAS 同时要求 {@code request_id}、{@code status='RUNNING'}、{@code lease_token} 与未过期租约。
   */
  boolean markUnknown(CanvasFunctionRun run, String leaseToken);

  /** 人工核查后让 UNKNOWN Run 回到 READY，只允许继续查询已持久化的外部任务。 */
  boolean resumeUnknown(CanvasFunctionRun run);

  /** 人工核查后把 UNKNOWN Run 收敛为确定的 FAILED/CANCELLED 终态。 */
  boolean resolveUnknownTerminal(CanvasFunctionRun run);

  boolean cancelActive(CanvasFunctionRun run);

  boolean deleteByNodeId(UUID nodeId);

  boolean deleteByCanvasId(UUID canvasId);
}
