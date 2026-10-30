package fun.fengwk.kkstudio.web.runtime;

import io.micrometer.context.ContextExecutorService;
import io.micrometer.context.ContextSnapshotFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import fun.fengwk.kkstudio.platform.harness.configuration.HarnessRuntimeProperties;
import fun.fengwk.kkstudio.project.reconcile.IssueReconcileProperties;
import fun.fengwk.kkstudio.project.reconcile.IssueReconciler;
import fun.fengwk.kkstudio.work.BoundedWorkDispatcher;
import fun.fengwk.kkstudio.work.DurableWorkStore;
import fun.fengwk.kkstudio.work.WorkClaim;
import fun.fengwk.kkstudio.work.WorkDispatcherLifecycle;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Issue Reconcile 运行时装配：drain executor、worker executor、poll scheduler、dispatcher 与 SmartLifecycle。
 *
 * <p>进程内 dispatcher 受 {@code HarnessRuntimeProperties.isWorkersEnabled()} 控制； false
 * 时不启动调度。PostgreSQL notification loop 为独立应用级生命周期，不受 worker 开关影响。
 *
 * <p>所有 executor 线程均为 daemon 并以 {@code destroyMethod = "shutdown"} 交给 Spring 持有生命周期； dispatcher 使用
 * fail-fast 单线程 drain executor 与 bounded AbortPolicy worker executor。
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(HarnessRuntimeProperties.class)
public class IssueReconcileRuntimeConfiguration {

  private static final String DISPATCHER_NAME = "issue reconcile dispatcher";

  /** fail-fast 单线程 drain executor：串行执行 drain，拒绝时同步抛错。 */
  @Bean(name = "issueReconcileDispatcherDrainExecutor", destroyMethod = "shutdown")
  public ExecutorService issueReconcileDispatcherDrainExecutor() {
    return Executors.newSingleThreadExecutor(
        Thread.ofPlatform().name("issue-dispatch-drain-", 0L).daemon(true).factory());
  }

  /** bounded worker executor：固定并发 + 有界队列 + AbortPolicy（fail-fast，绝不静默丢弃 handoff task）。 */
  @Bean(name = "issueReconcileDispatcherWorkerExecutor", destroyMethod = "shutdown")
  public ExecutorService issueReconcileDispatcherWorkerExecutor(
      IssueReconcileProperties properties, ContextSnapshotFactory contextSnapshotFactory) {
    int concurrency = properties.getWorker().getConcurrency();
    int queueCapacity = properties.getWorker().getQueueCapacity();
    return ContextExecutorService.wrap(
        new ThreadPoolExecutor(
            concurrency,
            concurrency,
            60L,
            TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(queueCapacity),
            Thread.ofPlatform().name("issue-dispatch-worker-", 0L).daemon(true).factory(),
            new ThreadPoolExecutor.AbortPolicy()),
        contextSnapshotFactory);
  }

  @Bean(name = "issueReconcileDispatcherPollScheduler", destroyMethod = "shutdown")
  public ScheduledExecutorService issueReconcileDispatcherPollScheduler() {
    return Executors.newSingleThreadScheduledExecutor(
        Thread.ofPlatform().name("issue-dispatch-poll-", 0L).daemon(true).factory());
  }

  @Bean(name = "issueReconcileDispatcher")
  public BoundedWorkDispatcher<WorkClaim> issueReconcileDispatcher(
      @Qualifier("issueWorkQueue") DurableWorkStore issueWorkQueue,
      IssueReconciler reconciler,
      IssueReconcileProperties properties,
      @Qualifier("issueReconcileDispatcherDrainExecutor") Executor drainExecutor,
      @Qualifier("issueReconcileDispatcherWorkerExecutor") Executor workerExecutor,
      @Qualifier("issueReconcileDispatcherPollScheduler") ScheduledExecutorService pollScheduler,
      PlatformTransactionManager transactionManager) {
    DurableWorkStore workQueue = Objects.requireNonNull(issueWorkQueue, "workQueue");
    IssueReconciler reconcile = Objects.requireNonNull(reconciler, "reconciler");
    IssueReconcileProperties reconcileProperties = Objects.requireNonNull(properties, "properties");
    TransactionTemplate transactions =
        new TransactionTemplate(Objects.requireNonNull(transactionManager, "transactionManager"));
    return new BoundedWorkDispatcher<>(
        DISPATCHER_NAME,
        reconcileProperties.getMaxDispatchTasks(),
        reconcileProperties.getPollInterval(),
        drainExecutor,
        workerExecutor,
        pollScheduler,
        () -> claimNext(workQueue, reconcileProperties),
        claim -> runHandoff(reconcile, workQueue, transactions, reconcileProperties, claim),
        claim -> returnClaim(workQueue, transactions, reconcileProperties, claim));
  }

  @Bean(name = "issueReconcileRuntimeLifecycle")
  public SmartLifecycle issueReconcileRuntimeLifecycle(
      HarnessRuntimeProperties properties,
      @Qualifier("issueReconcileDispatcher") BoundedWorkDispatcher<WorkClaim> dispatcher) {
    return new WorkDispatcherLifecycle(properties.isWorkersEnabled(), dispatcher);
  }

  private static WorkClaim claimNext(
      DurableWorkStore workQueue, IssueReconcileProperties properties) {
    return workQueue
        .claimNext(UUID.randomUUID().toString(), properties.getLeaseDuration())
        .orElse(null);
  }

  private static void runHandoff(
      IssueReconciler reconciler,
      DurableWorkStore workQueue,
      TransactionTemplate transactions,
      IssueReconcileProperties properties,
      WorkClaim claim) {
    try {
      reconciler.reconcile(claim);
    } catch (RuntimeException error) {
      log.error(
          "Reconciler failed for issueId={}; failure={}",
          claim.targetId(),
          error.getClass().getName());
      tryRescheduleOnFailure(workQueue, transactions, properties, claim);
    }
  }

  /** 归还 claim：延后 rejectionDelay 并交还 lease；归还失败仅留 lease 自愈。 */
  private static void returnClaim(
      DurableWorkStore workQueue,
      TransactionTemplate transactions,
      IssueReconcileProperties properties,
      WorkClaim claim) {
    try {
      rescheduleOwned(workQueue, transactions, claim, properties.getRejectionDelay());
    } catch (RuntimeException error) {
      log.warn(
          "Failed to return claim for issueId={}; failure={}",
          claim.targetId(),
          error.getClass().getName());
    }
  }

  /** reconciler 失败重试：延后 retryDelay 后重检；失败则留 lease 过期。 */
  private static void tryRescheduleOnFailure(
      DurableWorkStore workQueue,
      TransactionTemplate transactions,
      IssueReconcileProperties properties,
      WorkClaim claim) {
    try {
      rescheduleOwned(workQueue, transactions, claim, properties.getRetryDelay());
    } catch (RuntimeException error) {
      log.warn(
          "Failed to reschedule work after reconciler failure for issueId={}; failure={}",
          claim.targetId(),
          error.getClass().getName());
    }
  }

  private static void rescheduleOwned(
      DurableWorkStore workQueue,
      TransactionTemplate transactions,
      WorkClaim claim,
      Duration delay) {
    transactions.executeWithoutResult(
        status -> {
          if (workQueue.lockClaimed(claim).isPresent()) {
            workQueue.reschedule(claim, delay);
          }
        });
  }
}
