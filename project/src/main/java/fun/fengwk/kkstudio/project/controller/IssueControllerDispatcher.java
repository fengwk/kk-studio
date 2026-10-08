package fun.fengwk.kkstudio.project.controller;

import lombok.extern.slf4j.Slf4j;

import fun.fengwk.kkstudio.project.controller.IssueReconciler.IssueWorkClaim;
import fun.fengwk.kkstudio.project.service.IssueWorkStore;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * Issue Controller 的有界 Work 调度器。
 *
 * <p>职责边界：dispatcher 只负责 {@code project_issue_work} 的 claim、bounded handoff、合并 wake、periodic poll 与
 * stop 生命周期，绝不读取 Issue/Project 业务状态；业务状态由 {@link IssueReconciler} 在行锁与租约 fencing 保护下推进。
 *
 * <p>claim 是短的 claim-only 写；归还与延迟一律通过 {@code rescheduleWork} 的 lease 围栏完成，因此 worker 拒绝、reconciler
 * 失败与进程重启都只留 lease 自愈，不会吞掉新唤醒。
 */
@Slf4j
public final class IssueControllerDispatcher implements AutoCloseable {

  private final IssueWorkStore workStore;
  private final Function<IssueWorkClaim, IssueReconcileOutcome> reconciler;
  private final IssueControllerProperties properties;
  private final Executor drainExecutor;
  private final Executor workerExecutor;
  private final ScheduledExecutorService pollScheduler;

  private final AtomicBoolean wakeRequested = new AtomicBoolean();
  private final AtomicBoolean drainRunning = new AtomicBoolean();
  private final AtomicInteger dispatchCapacity = new AtomicInteger();
  private final Object lifecycleLock = new Object();
  private volatile boolean started;
  private volatile boolean stopped;
  private ScheduledFuture<?> pollFuture;

  public IssueControllerDispatcher(
      IssueWorkStore workStore,
      IssueReconciler reconciler,
      IssueControllerProperties properties,
      Executor drainExecutor,
      Executor workerExecutor,
      ScheduledExecutorService pollScheduler) {
    this(
        workStore,
        Objects.requireNonNull(reconciler, "reconciler")::reconcile,
        properties,
        drainExecutor,
        workerExecutor,
        pollScheduler);
  }

  IssueControllerDispatcher(
      IssueWorkStore workStore,
      Function<IssueWorkClaim, IssueReconcileOutcome> reconciler,
      IssueControllerProperties properties,
      Executor drainExecutor,
      Executor workerExecutor,
      ScheduledExecutorService pollScheduler) {
    this.workStore = Objects.requireNonNull(workStore, "workStore");
    this.reconciler = Objects.requireNonNull(reconciler, "reconciler");
    this.properties = Objects.requireNonNull(properties, "properties");
    this.drainExecutor = requireFailFastExecutor(drainExecutor, "drainExecutor");
    this.workerExecutor = requireFailFastExecutor(workerExecutor, "workerExecutor");
    this.pollScheduler = Objects.requireNonNull(pollScheduler, "pollScheduler");
  }

  /** 启动调度：注册 fixed-delay periodic poll 并触发首次 wake。幂等；已 stop 后调用抛 {@link IllegalStateException}。 */
  public void start() {
    synchronized (lifecycleLock) {
      if (stopped) {
        throw new IllegalStateException("dispatcher is already stopped");
      }
      if (pollFuture != null) {
        return;
      }
      long intervalMillis = properties.getPollInterval().toMillis();
      pollFuture =
          pollScheduler.scheduleWithFixedDelay(
              this::poll, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
      started = true;
      wake();
    }
  }

  /** 请求一次 drain；start 前或 stop 后为 no-op。 */
  public void wake() {
    if (!started || stopped) {
      return;
    }
    wakeRequested.set(true);
    if (drainRunning.compareAndSet(false, true)) {
      submitDrain();
    }
  }

  /**
   * 停止调度：取消 periodic poll future，之后不再启动新的 drain iteration；已经进入数据库的 claim 可能提交，但会在 handoff 前归还。不
   * shutdown 注入 executor、不 interrupt 已接受 task、不等待 worker task 完成。幂等。
   */
  public void stop() {
    synchronized (lifecycleLock) {
      if (stopped) {
        return;
      }
      stopped = true;
      started = false;
      ScheduledFuture<?> future = pollFuture;
      if (future != null) {
        future.cancel(false);
      }
    }
  }

  @Override
  public void close() {
    stop();
  }

  int dispatchCapacity() {
    return dispatchCapacity.get();
  }

  private void poll() {
    wake();
  }

  private void submitDrain() {
    AtomicBoolean taskStarted = new AtomicBoolean();
    try {
      drainExecutor.execute(
          () -> {
            taskStarted.set(true);
            runDrain();
          });
    } catch (RuntimeException error) {
      if (taskStarted.get()) {
        throw error;
      }
      drainRunning.set(false);
      log.warn(
          "Drain executor failed to accept a drain task; failure={}", error.getClass().getName());
    } catch (Error error) {
      if (!taskStarted.get()) {
        drainRunning.set(false);
      }
      throw error;
    }
  }

  private void runDrain() {
    try {
      do {
        wakeRequested.set(false);
        drainOnce();
      } while (!stopped && wakeRequested.get());
    } catch (RuntimeException error) {
      log.warn("Work drain failed; failure={}", error.getClass().getName());
    } finally {
      drainRunning.set(false);
      // drain 收尾窗口内到达的 wake：drainRunning 已清，必须由 recheck 重新提交，否则 wake 丢失。
      if (!stopped && wakeRequested.get() && drainRunning.compareAndSet(false, true)) {
        submitDrain();
      }
    }
  }

  /** 单次 drain 扫描：claim 直到 capacity 满、无 due 或需要结束。 */
  private void drainOnce() {
    while (!stopped && dispatchCapacity.get() < properties.getMaxDispatchTasks()) {
      IssueWorkClaim claim = claimNext();
      if (claim == null) {
        break;
      }
      if (stopped) {
        returnClaim(claim);
        break;
      }
      if (!handoff(claim)) {
        // worker executor rejection：结束当前 drain，禁止 claim -> reject 热循环。
        returnClaim(claim);
        break;
      }
    }
  }

  private IssueWorkClaim claimNext() {
    String token = UUID.randomUUID().toString();
    return workStore
        .claimNext(token, properties.getLeaseDuration())
        .map(
            work ->
                new IssueWorkClaim(
                    work.getIssueId(), token, work.getLeaseUntil(), work.getWakeVersion()))
        .orElse(null);
  }

  private boolean handoff(IssueWorkClaim claim) {
    AtomicBoolean taskStarted = new AtomicBoolean();
    dispatchCapacity.incrementAndGet();
    try {
      workerExecutor.execute(
          () -> {
            taskStarted.set(true);
            runHandoff(claim);
          });
      return true;
    } catch (RuntimeException error) {
      if (taskStarted.get()) {
        throw error;
      }
      dispatchCapacity.decrementAndGet();
      log.warn(
          "Worker executor failed to accept dispatch task for issueId={}; failure={}",
          claim.issueId(),
          error.getClass().getName());
      return false;
    } catch (Error error) {
      if (!taskStarted.get()) {
        dispatchCapacity.decrementAndGet();
        log.error(
            "Worker executor failed before accepting dispatch task for issueId={}; failure={}",
            claim.issueId(),
            error.getClass().getName());
        try {
          returnClaim(claim);
        } finally {
          throw error;
        }
      }
      throw error;
    }
  }

  private void runHandoff(IssueWorkClaim claim) {
    try {
      try {
        reconciler.apply(claim);
      } catch (RuntimeException error) {
        log.error(
            "Reconciler failed for issueId={}; failure={}",
            claim.issueId(),
            error.getClass().getName());
        tryRescheduleOnFailure(claim);
      }
    } finally {
      dispatchCapacity.decrementAndGet();
      wake();
    }
  }

  /** 归还 claim：延后 rejectionDelay 并交还 lease；归还失败仅留 lease 自愈。 */
  private void returnClaim(IssueWorkClaim claim) {
    try {
      workStore.rescheduleWork(
          claim.issueId(), claim.leaseToken(), claim.wakeVersion(), properties.getRejectionDelay());
    } catch (RuntimeException error) {
      log.warn(
          "Failed to return claim for issueId={}; failure={}",
          claim.issueId(),
          error.getClass().getName());
    }
  }

  /** reconciler 失败重试：延后 retryDelay 后重检；失败则留 lease 过期。 */
  private void tryRescheduleOnFailure(IssueWorkClaim claim) {
    try {
      workStore.rescheduleWork(
          claim.issueId(), claim.leaseToken(), claim.wakeVersion(), properties.getRetryDelay());
    } catch (RuntimeException error) {
      log.warn(
          "Failed to reschedule work after reconciler failure for issueId={}; failure={}",
          claim.issueId(),
          error.getClass().getName());
    }
  }

  private static Executor requireFailFastExecutor(Executor executor, String name) {
    Objects.requireNonNull(executor, name);
    if (executor instanceof ThreadPoolExecutor threadPool) {
      RejectedExecutionHandler handler = threadPool.getRejectedExecutionHandler();
      if (handler instanceof ThreadPoolExecutor.CallerRunsPolicy
          || handler instanceof ThreadPoolExecutor.DiscardPolicy
          || handler instanceof ThreadPoolExecutor.DiscardOldestPolicy) {
        throw new IllegalArgumentException(name + " must use a fail-fast rejection policy");
      }
    }
    return executor;
  }
}
