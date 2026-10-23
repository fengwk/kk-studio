package fun.fengwk.kkstudio.canvas.infra.postgresql;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import fun.fengwk.kkstudio.canvas.CanvasFunctionRun;
import fun.fengwk.kkstudio.canvas.CanvasFunctionRunStateCodecPort;
import fun.fengwk.kkstudio.canvas.CanvasFunctionRunStatus;
import fun.fengwk.kkstudio.canvas.infra.function.ClaimedRun;
import fun.fengwk.kkstudio.work.DurableWorkQueue;
import fun.fengwk.kkstudio.work.WorkClaim;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Optional;

/**
 * Canvas Function 的调度适配：在共享 CANVAS_RUN Work 之上领取、续租与归还 Run。
 *
 * <p>领取分两步：先从队列领取一条到期 Work，再在短事务内把 READY（或租约过期遗留的 RUNNING）Run 推进为新一次尝试。Run 已终态、UNKNOWN 或已删除时完成该
 * Work 并继续领取下一条。租约与 due 全部由数据库时钟判定。
 */
@Repository
public class CanvasFunctionWorkStore {

  private final DurableWorkQueue canvasRunWorkQueue;
  private final CanvasFunctionRunJdbcStore runStore;
  private final CanvasFunctionRunStateCodecPort stateCodec;
  private final TransactionTemplate transactions;
  private final Clock clock;

  public CanvasFunctionWorkStore(
      DurableWorkQueue canvasRunWorkQueue,
      CanvasFunctionRunJdbcStore runStore,
      CanvasFunctionRunStateCodecPort stateCodec,
      PlatformTransactionManager transactionManager,
      Clock clock) {
    this.canvasRunWorkQueue = Objects.requireNonNull(canvasRunWorkQueue, "canvasRunWorkQueue");
    this.runStore = Objects.requireNonNull(runStore, "runStore");
    this.stateCodec = Objects.requireNonNull(stateCodec, "stateCodec");
    this.transactions = new TransactionTemplate(transactionManager);
    this.clock = Clock.tick(Objects.requireNonNull(clock, "clock"), Duration.ofMillis(1));
  }

  public Optional<ClaimedRun> claimNext(Duration leaseDuration, String ownerToken) {
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
    return canvasRunWorkQueue.renew(Objects.requireNonNull(claim, "claim").work(), leaseDuration);
  }

  /** 归还仍持有的 claim：Run 退回 READY，Work 推迟 {@code delay} 后可再领取；已失去租约时不写入。 */
  public boolean reschedule(ClaimedRun claim, Duration delay) {
    Objects.requireNonNull(claim, "claim");
    return Boolean.TRUE.equals(
        transactions.execute(
            status ->
                runStore.returnToReady(claim.nodeId(), claim.leaseToken(), now()) == 1
                    && canvasRunWorkQueue.reschedule(claim.work(), delay)));
  }

  private sealed interface AttemptResult {}

  private record Started(ClaimedRun claim) implements AttemptResult {}

  private enum NotStarted implements AttemptResult {
    LOST,
    SKIPPED
  }

  private AttemptResult startAttempt(WorkClaim work) {
    // 保持 Run -> Work 锁序；Work 锁覆盖检查到 attempt 写入的整个短事务。
    runStore.findByNodeIdForUpdate(work.targetId());
    if (!canvasRunWorkQueue.lockOwned(work)) {
      return NotStarted.LOST;
    }
    Optional<CanvasFunctionRunDO> started = runStore.startAttempt(work.targetId(), now());
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
    return new Started(new ClaimedRun(domain, work));
  }

  private OffsetDateTime now() {
    return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
  }
}
