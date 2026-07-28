package fun.fengwk.kkstudio.core.harness.thread.worker;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;

import fun.fengwk.kkstudio.core.harness.configuration.HarnessRuntimeProperties;
import fun.fengwk.kkstudio.core.harness.thread.reconcile.ThreadReconcileMapper;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadKick;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Thread durable recovery scanner，也是生产环境本地 {@link ThreadKick} 的来源。
 *
 * <p>它只扫描 PostgreSQL 中 runnable 且 lease 可接管的 Thread，随后请求 Dispatcher 本地 reconcile；不读取 Redis
 * payload、不执行状态机，也不持有 Thread ownership。重复或丢失的 wake 因此是安全的：最终由数据库 claim/fencing 决定哪一个 activation
 * 可以推进。
 */
@Slf4j
public final class ThreadRecoveryLifecycle implements SmartLifecycle {
  private static final int PHASE = Integer.MAX_VALUE - 70;

  private final HarnessRuntimeProperties properties;
  private final ThreadReconcileMapper threadMapper;
  private final ThreadKick threadKick;
  private final ScheduledExecutorService scheduler;
  private final Duration interval;
  private final int batchSize;

  /** 串行化 lifecycle 切换与每次 durable recovery dispatch。 */
  private final Object lifecycleMonitor = new Object();

  private volatile boolean running;
  private volatile boolean stopping;
  private volatile ScheduledFuture<?> future;

  public ThreadRecoveryLifecycle(
      HarnessRuntimeProperties properties,
      ThreadReconcileMapper threadMapper,
      ThreadKick threadKick,
      ScheduledExecutorService scheduler,
      Duration interval,
      int batchSize) {
    this.properties = Objects.requireNonNull(properties, "properties");
    this.threadMapper = Objects.requireNonNull(threadMapper, "threadMapper");
    this.threadKick = Objects.requireNonNull(threadKick, "threadKick");
    this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    this.interval = Objects.requireNonNull(interval, "interval");
    if (interval.toMillis() <= 0) {
      throw new IllegalArgumentException(
          "thread recovery interval must be at least one millisecond");
    }
    if (batchSize <= 0) {
      throw new IllegalArgumentException("thread recovery batch size must be positive");
    }
    this.batchSize = batchSize;
  }

  @Override
  public boolean isAutoStartup() {
    return properties.isWorkersEnabled();
  }

  @Override
  public void start() {
    synchronized (lifecycleMonitor) {
      if (!properties.isWorkersEnabled() || running) {
        return;
      }
      stopping = false;
      running = true;
      try {
        future =
            scheduler.scheduleWithFixedDelay(
                this::scanSafely, 0L, interval.toMillis(), TimeUnit.MILLISECONDS);
      } catch (RuntimeException error) {
        running = false;
        future = null;
        log.warn("thread recovery schedule failed", error);
        throw error;
      }
    }
  }

  @Override
  public void stop() {
    stopping = true;
    synchronized (lifecycleMonitor) {
      running = false;
      ScheduledFuture<?> current = future;
      future = null;
      if (current != null) {
        current.cancel(false);
      }
    }
  }

  @Override
  public void stop(Runnable callback) {
    Objects.requireNonNull(callback, "callback");
    try {
      stop();
    } finally {
      callback.run();
    }
  }

  @Override
  public boolean isRunning() {
    ScheduledFuture<?> current = future;
    return running
        && !stopping
        && properties.isWorkersEnabled()
        && current != null
        && !current.isCancelled()
        && !current.isDone();
  }

  /** 扫描一次可恢复 Thread 并 kick；供测试与 lifecycle 调用。 */
  public int scanOnce() {
    return scan(false);
  }

  private int scanWhileRunning() {
    return scan(true);
  }

  private int scan(boolean requireRunning) {
    synchronized (lifecycleMonitor) {
      if (!properties.isWorkersEnabled() || (requireRunning && (!running || stopping))) {
        return 0;
      }
      List<Long> threadIds =
          threadMapper.listRecoverableThreadIds(OffsetDateTime.now(ZoneOffset.UTC), batchSize);
      int dispatched = 0;
      for (Long threadId : threadIds) {
        if (requireRunning && stopping) {
          break;
        }
        if (threadId != null && threadId > 0) {
          try {
            threadKick.kick(threadId);
            dispatched++;
          } catch (RejectedExecutionException rejected) {
            log.warn("recovery kick rejected for {}", threadId, rejected);
          }
        }
      }
      return dispatched;
    }
  }

  private void scanSafely() {
    try {
      scanWhileRunning();
    } catch (RuntimeException error) {
      log.warn("thread recovery scan failed", error);
    }
  }

  @Override
  public int getPhase() {
    return PHASE;
  }
}
