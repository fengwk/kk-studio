package fun.fengwk.kkstudio.canvas.infra.function;

import fun.fengwk.kkstudio.canvas.CanvasDocument;
import fun.fengwk.kkstudio.canvas.CanvasFunctionRun;
import fun.fengwk.kkstudio.canvas.CanvasFunctionRunStatus;
import fun.fengwk.kkstudio.canvas.NodeRecord;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionFrozenRun;
import fun.fengwk.kkstudio.canvas.infra.postgresql.PostgresqlCanvasFunctionRunRepository;
import fun.fengwk.kkstudio.canvas.infra.postgresql.PostgresqlCanvasStore;

import java.time.Clock;
import java.util.Objects;
import java.util.UUID;

/**
 * FunctionRun 短事务共用的持久化原语：固定锁序、行构造、租约匹配、状态写入与 revision 推进。
 *
 * <p>调用方必须已经开启事务；本类不声明 {@code @Transactional}，也不做业务判断：它只把一个已决定的状态变化写成 Run 行，或读取加锁后的权威行。 锁顺序与 {@code
 * CanvasCommandService} 共用同一协议——document → node → run。
 */
final class CanvasFunctionRunPersistence {

  private final PostgresqlCanvasStore canvasStore;
  private final PostgresqlCanvasFunctionRunRepository runRepository;
  private final CanvasFunctionRunStateCodec stateCodec;
  private final Clock clock;

  CanvasFunctionRunPersistence(
      PostgresqlCanvasStore canvasStore,
      PostgresqlCanvasFunctionRunRepository runRepository,
      CanvasFunctionRunStateCodec stateCodec,
      Clock clock) {
    this.canvasStore = Objects.requireNonNull(canvasStore, "canvasStore");
    this.runRepository = Objects.requireNonNull(runRepository, "runRepository");
    this.stateCodec = Objects.requireNonNull(stateCodec, "stateCodec");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /** 锁 document 行；所有写入都从这里开始，保证同一画布的写入串行。 */
  CanvasDocument requireDocumentForUpdate(UUID canvasId) {
    return canvasStore
        .lockDocument(canvasId)
        .orElseThrow(() -> CanvasFunctionRunErrors.notFound("Canvas document not found"));
  }

  /** 锁 node 行并要求它仍是 Function 节点；消失视为内部取消，通知 Worker 停止写回。 */
  void requireLiveFunctionNode(UUID canvasId, UUID nodeId) {
    NodeRecord node = canvasStore.lockNode(canvasId, nodeId).orElse(null);
    if (node == null || node.function() == null) {
      throw new CanvasFunctionInternalCancellation(
          "Canvas Function node disappeared during FunctionRun write");
    }
  }

  boolean functionNodePresent(UUID canvasId, UUID nodeId) {
    NodeRecord node = canvasStore.lockNode(canvasId, nodeId).orElse(null);
    return node != null && node.function() != null;
  }

  /** 锁 Run 行并要求当前租约仍持有它；不满足时抛内部取消。 */
  CanvasFunctionRun requireClaim(UUID nodeId, UUID requestId, UUID leaseToken) {
    CanvasFunctionRun run = runRepository.findByNodeIdForUpdate(nodeId).orElse(null);
    if (!matchesClaim(run, requestId, leaseToken)) {
      throw new CanvasFunctionInternalCancellation(
          "FunctionRun is no longer RUNNING under the current lease");
    }
    return run;
  }

  boolean matchesClaim(CanvasFunctionRun run, UUID requestId, UUID leaseToken) {
    return run != null
        && run.status() == CanvasFunctionRunStatus.RUNNING
        && run.requestId().equals(requestId)
        && runRepository.holdsLease(run.nodeId(), leaseToken);
  }

  /** 只从持久 state 解码冻结计划；恢复时不重新查询当前目录，避免按名称重新解析已冻结的任务。 */
  CanvasFunctionFrozenRun decode(CanvasFunctionRun run) {
    return stateCodec.decode(run.stateJson());
  }

  /**
   * 以冻结执行状态构造终态记录，并清空可调度时间与租约字段。
   *
   * <p>{@code error} 为终态的对外说明；外部提交结果不明时它是核查依据。
   */
  CanvasFunctionRun terminal(
      CanvasFunctionRun current,
      CanvasFunctionRunStatus status,
      CanvasFunctionFrozenRun frozen,
      String error) {
    return new CanvasFunctionRun(
        current.nodeId(),
        current.requestId(),
        status,
        current.attempt(),
        frozen.stage(),
        stateCodec.encode(frozen),
        error,
        clock.instant(),
        current.createdAt());
  }

  /** 写入 RUNNING 行；CAS 失败视为租约已失效，抛内部取消终止 adapter。 */
  void persistRunningState(
      CanvasFunctionRun current, CanvasFunctionFrozenRun next, UUID leaseToken) {
    CanvasFunctionRun updated =
        new CanvasFunctionRun(
            current.nodeId(),
            current.requestId(),
            CanvasFunctionRunStatus.RUNNING,
            current.attempt(),
            next.stage(),
            stateCodec.encode(next),
            null,
            clock.instant(),
            current.createdAt());
    if (!runRepository.checkpoint(
        updated.nodeId(),
        updated.requestId(),
        leaseToken,
        updated.stateJson(),
        updated.stage(),
        updated.updatedAt())) {
      throw new CanvasFunctionInternalCancellation(
          "FunctionRun checkpoint CAS failed because the run is no longer RUNNING");
    }
  }

  /** 在已持有文档行锁的事务内推进同步位置，失败视为持久化不变量破坏。 */
  void bumpVersion(CanvasDocument document) {
    if (!canvasStore.advanceRevision(
        document.id(), document.revision(), document.revision() + 1L)) {
      throw new IllegalStateException("canvas revision CAS failed under row lock");
    }
  }
}
