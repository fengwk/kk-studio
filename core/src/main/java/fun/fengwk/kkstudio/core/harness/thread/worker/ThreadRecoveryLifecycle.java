package fun.fengwk.kkstudio.core.harness.thread.worker;

import org.springframework.context.SmartLifecycle;

import fun.fengwk.kkstudio.core.harness.configuration.HarnessRuntimeProperties;
import fun.fengwk.kkstudio.core.harness.thread.store.mapper.HarnessThreadMapper;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadKick;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Thread 恢复：仅根据 durable 可恢复事实 kick，不替代主事件循环；DB processor fencing 仍为权威。 */
public final class ThreadRecoveryLifecycle implements SmartLifecycle {
  private static final System.Logger LOGGER =
      System.getLogger(ThreadRecoveryLifecycle.class.getName());

  private final HarnessRuntimeProperties properties;
  private final HarnessThreadMapper threadMapper;
  private final ThreadKick threadKick;
  private final ScheduledExecutorService scheduler;
  private final Duration interval;
  private final int batchSize;
  private final AtomicBoolean running = new AtomicBoolean();
  private volatile ScheduledFuture<?> future;

  public ThreadRecoveryLifecycle(
      HarnessRuntimeProperties properties,
      HarnessThreadMapper threadMapper,
      ThreadKick threadKick,
      ScheduledExecutorService scheduler,
      Duration interval,
      int batchSize) {
    this.properties = Objects.requireNonNull(properties, "properties");
    this.threadMapper = Objects.requireNonNull(threadMapper, "threadMapper");
    this.threadKick = Objects.requireNonNull(threadKick, "threadKick");
    this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    this.interval = Objects.requireNonNull(interval, "interval");
    if (interval.isZero() || interval.isNegative()) {
      throw new IllegalArgumentException("thread recovery interval must be positive");
    }
    if (batchSize <= 0) {
      throw new IllegalArgumentException("thread recovery batch size must be positive");
    }
    this.batchSize = batchSize;
  }

  @Override
  public void start() {
    if (!properties.isWorkersEnabled()) {
      // workers-disabled 不得留下 running=true。
      return;
    }
    if (!running.compareAndSet(false, true)) {
      return;
    }
    try {
      future =
          scheduler.scheduleWithFixedDelay(
              this::scanSafely, 0, interval.toMillis(), TimeUnit.MILLISECONDS);
    } catch (RuntimeException error) {
      running.set(false);
      future = null;
      LOGGER.log(System.Logger.Level.WARNING, "thread recovery schedule failed", error);
    }
  }

  @Override
  public void stop() {
    running.set(false);
    ScheduledFuture<?> current = future;
    if (current != null) {
      current.cancel(false);
      future = null;
    }
  }

  @Override
  public boolean isRunning() {
    return running.get()
        && properties.isWorkersEnabled()
        && future != null
        && !future.isCancelled();
  }

  /** 扫描一次可恢复 Thread 并 kick；供测试与 lifecycle 调用。 */
  public int scanOnce() {
    if (!properties.isWorkersEnabled()) {
      return 0;
    }
    LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
    List<Long> threadIds = threadMapper.listRecoverableThreadIds(now, batchSize);
    for (Long threadId : threadIds) {
      if (threadId != null && threadId > 0) {
        try {
          threadKick.kick(threadId);
        } catch (RejectedExecutionException rejected) {
          LOGGER.log(
              System.Logger.Level.WARNING, "recovery kick rejected for " + threadId, rejected);
        }
      }
    }
    return threadIds.size();
  }

  private void scanSafely() {
    if (!running.get() || !properties.isWorkersEnabled()) {
      return;
    }
    try {
      scanOnce();
    } catch (RuntimeException error) {
      LOGGER.log(System.Logger.Level.WARNING, "thread recovery scan failed", error);
    }
  }
}
