package fun.fengwk.kkstudio.harness.runtime.processor;

import lombok.extern.slf4j.Slf4j;

import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStoreTime;
import fun.fengwk.kkstudio.harness.runtime.work.ClaimedWork;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 单个 claim 的进程内 lease heartbeat 两层执行组件（供 Thread / Model / Tool processor 复用）。
 *
 * <p>定时器调度池（{@link ScheduledExecutorService}）仅负责按固定频率分发心跳任务与合并在途请求， 绝不阻塞等待数据库事务或同步执行所有权取消。具体 {@code
 * store.renewWork} 事务与 {@code onLostOwnership} 通知均由独立注入的 {@link Executor} 执行。
 *
 * <p>单在途与合并：单个 heartbeat 实例同时至多存在一个在途（queued 或 running）的续租任务；当上一轮续租尚未完成时，
 * 新触发的定时周期将被合并跳过，既不无界积压任务，也不占用调度线程。
 *
 * <p>停止不变量：{@link #stop} 幂等且保证在返回后，绝无排队或在途任务可再次提交 {@code renewWork}。在途任务执行期间 调用 {@link #stop}
 * 将等待当前单次续租事务结束；已排队未执行的任务会在获锁后检测停止标记并立即退出。
 *
 * <p>所有权丢失围栏：续租事务异常、调度器启动拒绝或分派执行器拒绝，均视为所有权无法维系； 统一触发停止并确保 {@link #onLostOwnership} 恰好执行一次。
 */
@Slf4j
final class WorkHeartbeat {

  private final HarnessStore store;
  private final ScheduledExecutorService scheduler;
  private final Executor heartbeatWorker;
  private final ProcessorLeaseConfig config;
  private final Clock clock;
  private final Runnable onLostOwnership;
  private final Object stateLock = new Object();
  private final Object renewalLock = new Object();
  private final AtomicBoolean stopped = new AtomicBoolean();
  private final AtomicBoolean stoppedNormally = new AtomicBoolean();
  private final AtomicBoolean renewalActive = new AtomicBoolean();
  private final AtomicBoolean lostNotified = new AtomicBoolean();
  private ScheduledFuture<?> future;
  private boolean started;

  WorkHeartbeat(
      HarnessStore store,
      ScheduledExecutorService scheduler,
      Executor heartbeatWorker,
      ProcessorLeaseConfig config,
      Clock clock,
      Runnable onLostOwnership) {
    this.store = Objects.requireNonNull(store, "store");
    this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    this.heartbeatWorker = Objects.requireNonNull(heartbeatWorker, "heartbeatWorker");
    this.config = Objects.requireNonNull(config, "config");
    this.clock = HarnessStoreTime.millisecondClock(clock);
    this.onLostOwnership = Objects.requireNonNull(onLostOwnership, "onLostOwnership");
  }

  /** 启动固定频率 renew；已 stop / 已 start / scheduler 拒绝时返回 false。 */
  boolean start(ClaimedWork claim) {
    Objects.requireNonNull(claim, "claim");
    boolean lost = false;
    synchronized (stateLock) {
      if (stopped.get() || stoppedNormally.get() || started) {
        return false;
      }
      try {
        long interval = config.heartbeatInterval().toMillis();
        future =
            scheduler.scheduleAtFixedRate(
                () -> tick(claim), interval, interval, TimeUnit.MILLISECONDS);
        started = true;
        return true;
      } catch (RejectedExecutionException failure) {
        log.warn("cannot schedule work lease heartbeat for {}", claim.target(), failure);
        stopped.set(true);
        lost = true;
      }
    }
    if (lost) {
      notifyLostOwnership();
    }
    return false;
  }

  /**
   * 停止 heartbeat 并 cancel 已提交的 periodic task；幂等。
   *
   * <p>等待在途 renewal 结束；返回后绝无在途或排队任务能够再次续租。
   */
  void stop() {
    stoppedNormally.set(true);
    stopScheduled();
    synchronized (renewalLock) {
      // 等待在途 renewal 结束；排队任务获锁后检测 stopped 立即退出。
    }
  }

  private void stopScheduled() {
    synchronized (stateLock) {
      stopped.set(true);
      if (future != null) {
        future.cancel(false);
      }
    }
  }

  /** 定时周期触发：非阻塞检查与合并在途续租，分派至 heartbeatWorker。 */
  private void tick(ClaimedWork claim) {
    if (stopped.get() || stoppedNormally.get()) {
      return;
    }
    if (!renewalActive.compareAndSet(false, true)) {
      // 在途续租合并：已有续租排队或执行中，不重复提交，亦不阻塞调度线程。
      return;
    }
    boolean dispatchFailed = false;
    synchronized (stateLock) {
      if (stopped.get() || stoppedNormally.get()) {
        renewalActive.set(false);
        return;
      }
      try {
        heartbeatWorker.execute(() -> renew(claim));
      } catch (RuntimeException failure) {
        renewalActive.set(false);
        if (stoppedNormally.get()) {
          log.debug(
              "heartbeat worker did not accept task after normal stop for {}", claim.target());
          return;
        }
        log.warn(
            "cannot dispatch work lease renewal for {}; ownership is lost",
            claim.target(),
            failure);
        stopped.set(true);
        if (future != null) {
          future.cancel(false);
        }
        dispatchFailed = true;
      }
    }
    if (dispatchFailed) {
      notifyLostOwnership();
    }
  }

  /** 单次 lease renewal：在 worker 线程执行数据库事务；失败时停止定时器并在锁外通知所有权丢失。 */
  private void renew(ClaimedWork claim) {
    try {
      boolean lost = false;
      synchronized (renewalLock) {
        if (stopped.get() || stoppedNormally.get()) {
          return;
        }
        try {
          Instant now = clock.instant();
          Instant until = now.plus(config.leaseDuration());
          store.transaction(
              tx -> {
                tx.renewWork(claim, now, until);
                return null;
              });
        } catch (RuntimeException failure) {
          log.warn("work lease renewal failed for {}; ownership is lost", claim.target(), failure);
          stopScheduled();
          lost = true;
        }
      }
      if (lost) {
        notifyLostOwnership();
      }
    } finally {
      renewalActive.set(false);
    }
  }

  /** 所有权丢失通知：确保至多通知一次，且不在调度线程或内部锁内同步阻塞执行。 */
  private void notifyLostOwnership() {
    if (stoppedNormally.get()) {
      return;
    }
    if (lostNotified.compareAndSet(false, true)) {
      if (stoppedNormally.get()) {
        return;
      }
      Runnable task =
          () -> {
            if (stoppedNormally.get()) {
              return;
            }
            try {
              onLostOwnership.run();
            } catch (Throwable failure) {
              log.warn("onLostOwnership callback failed", failure);
            }
          };
      try {
        heartbeatWorker.execute(task);
      } catch (RuntimeException failure) {
        log.warn(
            "cannot dispatch lost ownership notification to heartbeat worker; using fallback thread",
            failure);
        Thread.ofVirtual().name("heartbeat-lost-ownership").start(task);
      }
    }
  }
}
