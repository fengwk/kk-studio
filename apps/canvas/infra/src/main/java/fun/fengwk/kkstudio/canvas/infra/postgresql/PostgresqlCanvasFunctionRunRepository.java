package fun.fengwk.kkstudio.canvas.infra.postgresql;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import fun.fengwk.kkstudio.canvas.CanvasFunctionRun;
import fun.fengwk.kkstudio.canvas.CanvasFunctionRunRepository;
import fun.fengwk.kkstudio.canvas.CanvasFunctionRunStateCodecPort;
import fun.fengwk.kkstudio.canvas.CanvasFunctionRunStatus;
import fun.fengwk.kkstudio.work.DurableWorkQueue;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code canvas_function_run} 的持久化实现。
 *
 * <p>Run 进入 READY 时在同一事务内请求一次 CANVAS_RUN Work；离开可执行状态（终态、UNKNOWN、取消、删除）时删除对应 Work 行，使调度事实与业务状态一起提交。
 */
@Repository
public class PostgresqlCanvasFunctionRunRepository implements CanvasFunctionRunRepository {

  private final CanvasFunctionRunJdbcStore runStore;
  private final CanvasFunctionRunStateCodecPort stateCodec;
  private final DurableWorkQueue canvasRunWorkQueue;

  public PostgresqlCanvasFunctionRunRepository(
      CanvasFunctionRunJdbcStore runStore,
      CanvasFunctionRunStateCodecPort stateCodec,
      DurableWorkQueue canvasRunWorkQueue) {
    this.runStore = Objects.requireNonNull(runStore, "runStore");
    this.stateCodec = Objects.requireNonNull(stateCodec, "stateCodec");
    this.canvasRunWorkQueue = Objects.requireNonNull(canvasRunWorkQueue, "canvasRunWorkQueue");
  }

  @Override
  public Optional<CanvasFunctionRun> findByNodeId(UUID nodeId) {
    return runStore.findByNodeId(nodeId).map(this::toDomain);
  }

  @Override
  public Optional<CanvasFunctionRun> findByNodeIdForUpdate(UUID nodeId) {
    return runStore.findByNodeIdForUpdate(nodeId).map(this::toDomain);
  }

  @Override
  public boolean ownsRunningRequest(UUID canvasId, UUID nodeId, UUID requestId, String leaseToken) {
    return runStore.ownsRunningRequest(canvasId, nodeId, requestId, leaseToken);
  }

  @Override
  public boolean holdsLease(UUID nodeId, String leaseToken) {
    return runStore.holdsLease(nodeId, leaseToken);
  }

  @Override
  public List<CanvasFunctionRun> findByCanvasId(UUID canvasId) {
    List<CanvasFunctionRun> runs = new ArrayList<>();
    for (CanvasFunctionRunDO run : runStore.listByCanvas(canvasId)) {
      runs.add(toDomain(run));
    }
    return List.copyOf(runs);
  }

  @Override
  @Transactional
  public void insertReady(CanvasFunctionRun run) {
    requireStatus(run, CanvasFunctionRunStatus.READY);
    if (runStore.insert(toData(run)) != 1) {
      throw new IllegalStateException("insert canvas function run failed: " + run.nodeId());
    }
    canvasRunWorkQueue.request(run.nodeId(), Duration.ZERO);
  }

  @Override
  @Transactional
  public boolean replaceTerminalWithReady(CanvasFunctionRun run) {
    requireStatus(run, CanvasFunctionRunStatus.READY);
    if (runStore.replaceTerminalWithReady(toData(run)) != 1) {
      return false;
    }
    canvasRunWorkQueue.request(run.nodeId(), Duration.ZERO);
    return true;
  }

  @Override
  public boolean checkpoint(
      UUID nodeId,
      UUID requestId,
      String leaseToken,
      String stateJson,
      String stage,
      Instant updatedAt) {
    if (!stage.equals(stateCodec.stage(stateJson))) {
      throw new IllegalArgumentException("checkpoint stage must match stateJson");
    }
    return runStore.checkpoint(
            nodeId,
            requestId,
            leaseToken,
            stateJson,
            OffsetDateTime.ofInstant(updatedAt, ZoneOffset.UTC))
        == 1;
  }

  @Override
  @Transactional
  public boolean transitionTerminal(CanvasFunctionRun run, String leaseToken) {
    if (run.status() == CanvasFunctionRunStatus.READY
        || run.status() == CanvasFunctionRunStatus.RUNNING) {
      throw new IllegalArgumentException("terminal transition requires terminal status");
    }
    return finishOwned(run.nodeId(), runStore.transitionTerminal(toData(run), leaseToken));
  }

  @Override
  @Transactional
  public boolean markUnknown(CanvasFunctionRun run, String leaseToken) {
    requireStatus(run, CanvasFunctionRunStatus.UNKNOWN);
    return finishOwned(run.nodeId(), runStore.markUnknown(toData(run), leaseToken));
  }

  @Override
  @Transactional
  public boolean resumeUnknown(CanvasFunctionRun run) {
    requireStatus(run, CanvasFunctionRunStatus.READY);
    if (runStore.resumeUnknown(
            run.nodeId(), run.requestId(), run.stateJson(), toOffsetDateTime(run.updatedAt()))
        != 1) {
      return false;
    }
    canvasRunWorkQueue.request(run.nodeId(), Duration.ZERO);
    return true;
  }

  @Override
  public boolean resolveUnknownTerminal(CanvasFunctionRun run) {
    if (run.status() != CanvasFunctionRunStatus.FAILED
        && run.status() != CanvasFunctionRunStatus.CANCELLED) {
      throw new IllegalArgumentException("resolved FunctionRun must be FAILED or CANCELLED");
    }
    return runStore.resolveUnknownTerminal(toData(run)) == 1;
  }

  @Override
  @Transactional
  public boolean cancelActive(CanvasFunctionRun run) {
    requireStatus(run, CanvasFunctionRunStatus.CANCELLED);
    if (runStore.cancelActive(toData(run)) != 1) {
      return false;
    }
    canvasRunWorkQueue.delete(run.nodeId());
    return true;
  }

  @Override
  @Transactional
  public boolean deleteByNodeId(UUID nodeId) {
    canvasRunWorkQueue.delete(nodeId);
    return runStore.deleteByNodeId(nodeId) >= 0;
  }

  @Override
  @Transactional
  public boolean deleteByCanvasId(UUID canvasId) {
    runStore.deleteWorkByCanvas(canvasId);
    return runStore.deleteByCanvas(canvasId) >= 0;
  }

  /** 持有者的 CAS 成功即证明它仍持有租约：Run 不再可执行，同一事务内删除它的 Work 行。 */
  private boolean finishOwned(UUID nodeId, int updated) {
    if (updated != 1) {
      return false;
    }
    canvasRunWorkQueue.delete(nodeId);
    return true;
  }

  private CanvasFunctionRun toDomain(CanvasFunctionRunDO run) {
    return new CanvasFunctionRun(
        run.getNodeId(),
        run.getRequestId(),
        CanvasFunctionRunStatus.valueOf(run.getStatus()),
        run.getAttempt(),
        stateCodec.stage(run.getStateJson()),
        run.getStateJson(),
        run.getError(),
        run.getUpdatedAt().toInstant(),
        run.getCreatedAt().toInstant());
  }

  private static CanvasFunctionRunDO toData(CanvasFunctionRun run) {
    CanvasFunctionRunDO data = new CanvasFunctionRunDO();
    data.setNodeId(run.nodeId());
    data.setRequestId(run.requestId());
    data.setStatus(run.status().name());
    data.setAttempt(run.attempt());
    data.setStateJson(run.stateJson());
    data.setError(run.error());
    data.setUpdatedAt(OffsetDateTime.ofInstant(run.updatedAt(), ZoneOffset.UTC));
    data.setCreatedAt(OffsetDateTime.ofInstant(run.createdAt(), ZoneOffset.UTC));
    return data;
  }

  private static OffsetDateTime toOffsetDateTime(Instant value) {
    return value == null ? null : OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
  }

  private static void requireStatus(CanvasFunctionRun run, CanvasFunctionRunStatus expectedStatus) {
    if (run.status() != expectedStatus) {
      throw new IllegalArgumentException("FunctionRun status must be " + expectedStatus);
    }
  }
}
