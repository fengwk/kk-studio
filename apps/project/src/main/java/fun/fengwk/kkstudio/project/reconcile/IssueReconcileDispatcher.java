package fun.fengwk.kkstudio.project.reconcile;

import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import fun.fengwk.kkstudio.work.BoundedWorkDispatcher;
import fun.fengwk.kkstudio.work.DurableWorkStore;
import fun.fengwk.kkstudio.work.WorkClaim;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Function;

/**
 * Issue Reconcile 的有界 Work 调度器。
 *
 * <p>职责边界：dispatcher 只负责 {@code durable_work} 中 ISSUE 行的 claim、bounded handoff、合并 wake、periodic
 * poll 与 stop 生命周期，绝不读取 Issue/Project 业务状态；业务状态由 {@link IssueReconciler} 在行锁与租约 fencing 保护下推进。
 *
 * <p>claim 是短的 claim-only 写；归还与延迟一律通过 lease 围栏的重排完成，因此 worker 拒绝、reconciler 失败与进程重启都只留 lease
 * 自愈，不会吞掉新唤醒。合并 wake 的单 drain、bounded handoff、容量计数与 stop 生命周期由共享的 {@link BoundedWorkDispatcher}
 * 提供；本类只注入 Issue 专有的 claim、reconciler handoff 与 lease 围栏归还。
 */
@Slf4j
public final class IssueReconcileDispatcher implements AutoCloseable {

  /** Issue Reconcile 在 {@code durable_work} 上的 target type。 */
  public static final String TARGET_TYPE = "ISSUE";

  private static final String NAME = "issue reconcile dispatcher";

  private final DurableWorkStore workQueue;
  private final Function<WorkClaim, IssueReconcileOutcome> reconciler;
  private final IssueReconcileProperties properties;
  private final BoundedWorkDispatcher<WorkClaim> dispatcher;
  private final TransactionTemplate transactions;

  public IssueReconcileDispatcher(
      DurableWorkStore workQueue,
      IssueReconciler reconciler,
      IssueReconcileProperties properties,
      Executor drainExecutor,
      Executor workerExecutor,
      ScheduledExecutorService pollScheduler,
      PlatformTransactionManager transactionManager) {
    this(
        workQueue,
        Objects.requireNonNull(reconciler, "reconciler")::reconcile,
        properties,
        drainExecutor,
        workerExecutor,
        pollScheduler,
        transactionManager);
  }

  IssueReconcileDispatcher(
      DurableWorkStore workQueue,
      Function<WorkClaim, IssueReconcileOutcome> reconciler,
      IssueReconcileProperties properties,
      Executor drainExecutor,
      Executor workerExecutor,
      ScheduledExecutorService pollScheduler,
      PlatformTransactionManager transactionManager) {
    this.workQueue = Objects.requireNonNull(workQueue, "workQueue");
    this.reconciler = Objects.requireNonNull(reconciler, "reconciler");
    this.properties = Objects.requireNonNull(properties, "properties");
    this.transactions =
        new TransactionTemplate(Objects.requireNonNull(transactionManager, "transactionManager"));
    this.dispatcher =
        new BoundedWorkDispatcher<>(
            NAME,
            properties.getMaxDispatchTasks(),
            properties.getPollInterval(),
            drainExecutor,
            workerExecutor,
            pollScheduler,
            this::claimNext,
            this::runHandoff,
            this::returnClaim);
  }

  /** 启动调度：注册 fixed-delay periodic poll 并触发首次 wake。幂等；已 stop 后调用抛 {@link IllegalStateException}。 */
  public void start() {
    dispatcher.start();
  }

  /** 请求一次 drain；start 前或 stop 后为 no-op。 */
  public void wake() {
    dispatcher.wake();
  }

  /**
   * 停止调度：取消 periodic poll future，之后不再启动新的 drain iteration；已经进入数据库的 claim 可能提交，但会在 handoff 前归还。不
   * shutdown 注入 executor、不 interrupt 已接受 task、不等待 worker task 完成。幂等。
   */
  public void stop() {
    dispatcher.stop();
  }

  @Override
  public void close() {
    dispatcher.close();
  }

  int dispatchCapacity() {
    return dispatcher.inFlightCount();
  }

  private WorkClaim claimNext() {
    String token = UUID.randomUUID().toString();
    return workQueue.claimNext(token, properties.getLeaseDuration()).orElse(null);
  }

  private void runHandoff(WorkClaim claim) {
    try {
      reconciler.apply(claim);
    } catch (RuntimeException error) {
      log.error(
          "Reconciler failed for issueId={}; failure={}",
          claim.targetId(),
          error.getClass().getName());
      tryRescheduleOnFailure(claim);
    }
  }

  /** 归还 claim：延后 rejectionDelay 并交还 lease；归还失败仅留 lease 自愈。 */
  private void returnClaim(WorkClaim claim) {
    try {
      rescheduleOwned(claim, properties.getRejectionDelay());
    } catch (RuntimeException error) {
      log.warn(
          "Failed to return claim for issueId={}; failure={}",
          claim.targetId(),
          error.getClass().getName());
    }
  }

  /** reconciler 失败重试：延后 retryDelay 后重检；失败则留 lease 过期。 */
  private void tryRescheduleOnFailure(WorkClaim claim) {
    try {
      rescheduleOwned(claim, properties.getRetryDelay());
    } catch (RuntimeException error) {
      log.warn(
          "Failed to reschedule work after reconciler failure for issueId={}; failure={}",
          claim.targetId(),
          error.getClass().getName());
    }
  }

  private void rescheduleOwned(WorkClaim claim, Duration delay) {
    transactions.executeWithoutResult(
        status -> {
          if (workQueue.lockClaimed(claim).isPresent()) {
            workQueue.reschedule(claim, delay);
          }
        });
  }
}
