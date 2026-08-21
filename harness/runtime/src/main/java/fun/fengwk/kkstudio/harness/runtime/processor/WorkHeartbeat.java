package fun.fengwk.kkstudio.harness.runtime.processor;

import lombok.extern.slf4j.Slf4j;

import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStoreTime;
import fun.fengwk.kkstudio.harness.runtime.work.ClaimedWork;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 单个 claim 的进程内 lease heartbeat（后续 Thread / Tool processor 复用）。
 *
 * <p>只 renew 当前 Work 的 lease，不触碰 Thread / Invocation / version；任何 renew 失败（行删除 / token 不匹配 / lease
 * 过期）都视为 lost ownership：停止 heartbeat 并通知 owner 立即关闭本地执行。
 *
 * <p>线程安全：{@link #start} 最多成功一次，{@link #stop} 与 {@link #start} 在同一个锁内交错，stop 先于 start 时 start
 * 直接拒绝且不会提交 periodic task；start 已提交的 periodic task 在 stop 时必然被 cancel（不泄漏）。{@link #beat} 与 {@link
 * #start} / {@link #stop} 同一锁互斥：检查 / renew / 失败标记在锁内完成，stop 返回后绝无在途 beat 继续 renew lease；lost
 * ownership 的 {@code onLostOwnership} 通知在锁外执行。
 */
@Slf4j
final class WorkHeartbeat {

  private final HarnessStore store;
  private final ScheduledExecutorService scheduler;
  private final ProcessorLeaseConfig config;
  private final Clock clock;
  private final Runnable onLostOwnership;
  private final Object lock = new Object();
  private final AtomicBoolean stopped = new AtomicBoolean();
  private ScheduledFuture<?> future;
  private boolean started;

  WorkHeartbeat(
      HarnessStore store,
      ScheduledExecutorService scheduler,
      ProcessorLeaseConfig config,
      Clock clock,
      Runnable onLostOwnership) {
    this.store = Objects.requireNonNull(store, "store");
    this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    this.config = Objects.requireNonNull(config, "config");
    this.clock = HarnessStoreTime.millisecondClock(clock);
    this.onLostOwnership = Objects.requireNonNull(onLostOwnership, "onLostOwnership");
  }

  /** 启动固定频率 renew；已 stop / 已 start / scheduler 拒绝时返回 false。 */
  boolean start(ClaimedWork claim) {
    synchronized (lock) {
      if (stopped.get() || started) {
        return false;
      }
      try {
        long interval = config.heartbeatInterval().toMillis();
        future =
            scheduler.scheduleAtFixedRate(
                () -> beat(claim), interval, interval, TimeUnit.MILLISECONDS);
        started = true;
        return true;
      } catch (RejectedExecutionException failure) {
        log.warn("cannot schedule work lease heartbeat for {}", claim.target(), failure);
        return false;
      }
    }
  }

  /**
   * 停止 heartbeat 并 cancel 已提交的 periodic task；幂等。
   *
   * <p>与 {@link #beat} 在同一锁内互斥：stop 返回后不可能存在在途 beat 继续 renew lease（在途 beat 要么在 stop 获取锁前 已完成最后一次
   * renew，要么在 stop 之后看到 stopped 直接返回）。
   */
  void stop() {
    synchronized (lock) {
      stopped.set(true);
      if (future != null) {
        future.cancel(false);
      }
    }
  }

  /**
   * 单次 lease renewal：检查 / renew / 失败标记全部在锁内完成（与 {@link #start} / {@link #stop} 互斥，保证 stop 返回后 绝无在途
   * renew）；{@link #onLostOwnership} 通知在锁外执行（回调可能触发 close / cancel，不能在持锁路径上调用）。
   */
  private void beat(ClaimedWork claim) {
    boolean lost = false;
    synchronized (lock) {
      if (stopped.get()) {
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
        stopped.set(true);
        if (future != null) {
          future.cancel(false);
        }
        lost = true;
      }
    }
    if (lost) {
      onLostOwnership.run();
    }
  }
}
