package fun.fengwk.kkstudio.canvas.infra.postgresql;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import fun.fengwk.kkstudio.canvas.CanvasDocument;
import fun.fengwk.kkstudio.canvas.CanvasFunctionRun;
import fun.fengwk.kkstudio.canvas.CanvasFunctionRunStatus;
import fun.fengwk.kkstudio.canvas.infra.function.CanvasFunctionRunStateCodec;
import fun.fengwk.kkstudio.canvas.infra.function.ClaimedRun;
import fun.fengwk.kkstudio.work.DurableWorkStore;
import fun.fengwk.kkstudio.work.WorkClaim;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Canvas Function 的调度适配：在共享 CANVAS_RUN Work 之上领取、续租与归还 Run。
 *
 * <p>领取分两步：先从队列领取一条到期 Work，再在短事务内把 READY（或租约过期遗留的 RUNNING）Run 推进为新一次尝试。Run 已终态、UNKNOWN 或已删除时完成该
 * Work 并继续领取下一条。租约与 due 全部由数据库时钟判定。
 */
@Repository
public class CanvasFunctionWorkStore {

  /** Canvas Function Run 在 {@code durable_work} 上的 target type。 */
  public static final String TARGET_TYPE = "CANVAS_RUN";

  private final DurableWorkStore canvasRunWorkQueue;
  private final PostgresqlCanvasStore canvasStore;
  private final CanvasFunctionRunJdbcStore runStore;
  private final CanvasFunctionRunStateCodec stateCodec;
  private final TransactionTemplate transactions;
  private final Clock clock;

  public CanvasFunctionWorkStore(
      DurableWorkStore canvasRunWorkQueue,
      PostgresqlCanvasStore canvasStore,
      CanvasFunctionRunJdbcStore runStore,
      CanvasFunctionRunStateCodec stateCodec,
      PlatformTransactionManager transactionManager,
      Clock clock) {
    this.canvasRunWorkQueue = Objects.requireNonNull(canvasRunWorkQueue, "canvasRunWorkQueue");
    this.canvasStore = Objects.requireNonNull(canvasStore, "canvasStore");
    this.runStore = Objects.requireNonNull(runStore, "runStore");
    this.stateCodec = Objects.requireNonNull(stateCodec, "stateCodec");
    this.transactions = new TransactionTemplate(transactionManager);
    this.clock = Clock.tick(Objects.requireNonNull(clock, "clock"), Duration.ofMillis(1));
  }

  public Optional<ClaimedRun> claimNext(Duration leaseDuration, UUID ownerToken) {
    while (true) {
      Optional<WorkClaim> work = canvasRunWorkQueue.claimNext(ownerToken, leaseDuration);
      if (work.isEmpty()) {
        return Optional.empty();
      }
      AttemptResult result = transactions.execute(status -> startAttempt(work.get()));
      switch (result) {
        case Started started -> {
          return Optional.of(started.claim());
        }
        case NotStarted.LOST -> {
          return Optional.empty();
        }
        case NotStarted.SKIPPED -> {}
      }
    }
  }

  public boolean renew(ClaimedRun claim, Duration leaseDuration) {
    Objects.requireNonNull(claim, "claim");
    return Boolean.TRUE.equals(
        transactions.execute(
            status -> {
              if (canvasRunWorkQueue.lockClaimed(claim.work()).isEmpty()) {
                return false;
              }
              canvasRunWorkQueue.renew(claim.work(), leaseDuration);
              return true;
            }));
  }

  /** 归还仍持有的 claim：Run 退回 READY，Work 推迟 {@code delay} 后可再领取；已失去租约时不写入。 */
  public boolean reschedule(ClaimedRun claim, Duration delay) {
    Objects.requireNonNull(claim, "claim");
    return Boolean.TRUE.equals(
        transactions.execute(
            status -> {
              Optional<CanvasDocument> document = canvasStore.lockDocumentForNode(claim.nodeId());
              if (document.isEmpty()) {
                return false;
              }
              runStore.findByNodeIdForUpdate(claim.nodeId());
              if (canvasRunWorkQueue.lockClaimed(claim.work()).isEmpty()) {
                return false;
              }
              if (runStore.returnToReady(claim.nodeId(), claim.leaseToken(), now()) != 1) {
                return false;
              }
              canvasRunWorkQueue.reschedule(claim.work(), delay);
              bumpRevision(document.get());
              return true;
            }));
  }

  private sealed interface AttemptResult {}

  private record Started(ClaimedRun claim) implements AttemptResult {}

  private enum NotStarted implements AttemptResult {
    LOST,
    SKIPPED
  }

  private AttemptResult startAttempt(WorkClaim work) {
    // 保持 document → node → Run → Work 锁序；Work 锁覆盖 ownership 检查和 attempt 写入。
    Optional<CanvasDocument> document = canvasStore.lockDocumentForNode(work.targetId());
    if (document.isPresent()) {
      runStore.findByNodeIdForUpdate(work.targetId());
    }
    if (canvasRunWorkQueue.lockClaimed(work).isEmpty()) {
      return NotStarted.LOST;
    }
    Optional<CanvasFunctionRunDO> started =
        document.isPresent() ? runStore.startAttempt(work.targetId(), now()) : Optional.empty();
    if (started.isEmpty()) {
      canvasRunWorkQueue.complete(work);
      return NotStarted.SKIPPED;
    }
    CanvasFunctionRunDO run = started.get();
    CanvasFunctionRun domain =
        new CanvasFunctionRun(
            run.getNodeId(),
            run.getRequestId(),
            CanvasFunctionRunStatus.valueOf(run.getStatus()),
            run.getAttempt(),
            stateCodec.stage(run.getStateJson()),
            run.getStateJson(),
            run.getError(),
            run.getUpdatedAt().toInstant(),
            run.getCreatedAt().toInstant());
    bumpRevision(document.orElseThrow());
    return new Started(new ClaimedRun(domain, work));
  }

  private void bumpRevision(CanvasDocument document) {
    long revision = document.revision();
    if (!canvasStore.advanceRevision(document.id(), revision, revision + 1)) {
      throw new IllegalStateException("canvas revision CAS failed under row lock");
    }
  }

  private OffsetDateTime now() {
    return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
  }
}
