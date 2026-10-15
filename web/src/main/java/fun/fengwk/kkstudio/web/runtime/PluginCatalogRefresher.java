package fun.fengwk.kkstudio.web.runtime;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;

import fun.fengwk.kkstudio.platform.plugin.catalog.PluginCatalogRefreshTrigger;
import fun.fengwk.kkstudio.platform.plugin.catalog.PluginCatalogRuntime;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * 生产 Plugin 目录对账器：把共享 LISTEN 通知、本节点提交后唤醒与固定周期对账合并成一次权威回读，是唯一拥有 {@link
 * PluginCatalogRuntime#refresh()} 调度的组件。
 *
 * <p>所有触发都只在调用线程排队，绝不在共享 LISTEN 线程上配置插件；单线程 executor 串行执行真正的对账，因此任意时刻至多一个运行中和一个待执行的对账。唤醒用 {@link
 * AtomicBoolean} 合并：任务开始前清标志，运行中到达的新信号会再排一次，既不无界排队也不丢唤醒。
 *
 * <p>生命周期与 executor 世代严格绑定：{@link #stop()} 停止调度并等待在途对账自然结束（不中断、不强杀已发布的实例），本代已排队但未开始的对账不会再执行 SQL；
 * 上一代 executor 未退出前拒绝 {@link #start()}，绝不出现两个活动线程。
 */
@Slf4j
public final class PluginCatalogRefresher
    implements SmartLifecycle, AutoCloseable, PluginCatalogRefreshTrigger {

  private static final long SHUTDOWN_JOIN_MILLIS = 5_000;

  private final PluginCatalogRuntime runtime;

  private final long intervalMillis;

  /** 仅用于测试调度失败回滚的注入点；生产沿用 daemon platform thread 的单线程调度器。 */
  private final Supplier<ScheduledExecutorService> executorFactory;

  private final AtomicBoolean queued = new AtomicBoolean();

  private final Object lifecycleLock = new Object();

  private volatile boolean running;

  private volatile boolean closed;

  /** 当前一代 executor：只在 {@link #lifecycleLock} 内读写，保证触发与停止观察到同一代。 */
  private ScheduledExecutorService executor;

  /** 上一代 executor：未终止前禁止再启动一代，避免同时存在两个活动线程。 */
  private ScheduledExecutorService retiredExecutor;

  public PluginCatalogRefresher(PluginCatalogRuntime runtime, Duration interval) {
    this(runtime, interval, PluginCatalogRefresher::newRefreshExecutor);
  }

  PluginCatalogRefresher(
      PluginCatalogRuntime runtime,
      Duration interval,
      Supplier<ScheduledExecutorService> executorFactory) {
    this.runtime = Objects.requireNonNull(runtime, "runtime");
    this.intervalMillis = requirePositiveWholeMillis(interval);
    this.executorFactory = Objects.requireNonNull(executorFactory, "executorFactory");
  }

  @Override
  public void start() {
    synchronized (lifecycleLock) {
      if (closed) {
        throw new IllegalStateException("plugin catalog refresher is already closed");
      }
      if (running) {
        return;
      }
      ScheduledExecutorService previous = retiredExecutor;
      if (previous != null) {
        if (!previous.isTerminated()) {
          throw new IllegalStateException(
              "the previous refresh executor has not exited; restart is refused");
        }
        retiredExecutor = null;
      }
      ScheduledExecutorService created = executorFactory.get();
      executor = created;
      running = true;
      try {
        // 立即触发一次启动对账，之后按固定周期只排队合并唤醒。
        created.scheduleWithFixedDelay(
            () -> requestTick(created), 0L, intervalMillis, TimeUnit.MILLISECONDS);
      } catch (RuntimeException | Error error) {
        running = false;
        executor = null;
        retiredExecutor = created;
        created.shutdownNow();
        throw error;
      }
    }
  }

  @Override
  public void stop() {
    closeInternal(false);
  }

  @Override
  public boolean isRunning() {
    return running;
  }

  @Override
  public void close() {
    closeInternal(true);
  }

  /**
   * 只排队合并唤醒：立即返回，真正的权威回读由单线程 executor 串行执行。
   *
   * <p>已停止（或已关闭）时是安全 no-op；仍在运行时若调度已拒绝排队则原样上抛，绝不假装唤醒成功而静默丢弃对账。
   */
  @Override
  public void requestRefresh() {
    ScheduledExecutorService current;
    synchronized (lifecycleLock) {
      if (!running) {
        return;
      }
      current = executor;
      enqueue(current);
    }
  }

  private void closeInternal(boolean permanentClose) {
    ScheduledExecutorService current;
    synchronized (lifecycleLock) {
      if (closed || (!running && !permanentClose)) {
        return;
      }
      if (permanentClose) {
        closed = true;
      }
      running = false;
      current = executor != null ? executor : retiredExecutor;
      executor = null;
      // 本代已停止：已排队但未开始的对账由 refreshOnce 的世代检查丢弃，不再执行任何 SQL。
      queued.set(false);
      if (current != null) {
        retiredExecutor = current;
      }
    }
    if (current != null) {
      // 不中断在途对账，只停止接收新任务；已发布实例因此绝不会被提前回收。
      current.shutdown();
      awaitTermination(current);
    }
  }

  /** 周期 tick 属于创建它的那一代：本代已停止就不再排队。 */
  private void requestTick(ScheduledExecutorService generation) {
    synchronized (lifecycleLock) {
      if (executor != generation || !running) {
        return;
      }
      enqueue(generation);
    }
  }

  private void enqueue(ScheduledExecutorService generation) {
    if (!queued.compareAndSet(false, true)) {
      return;
    }
    try {
      generation.execute(() -> refreshOnce(generation));
    } catch (RejectedExecutionException error) {
      queued.set(false);
      throw error;
    }
  }

  private void refreshOnce(ScheduledExecutorService generation) {
    synchronized (lifecycleLock) {
      if (executor != generation || !running) {
        return;
      }
      // 运行开始前清标志，旧世代不能改写新世代的排队状态。
      queued.set(false);
    }
    runtime.refresh();
  }

  /** 有界等待本代 executor 退出；等待期间绝不持有生命周期锁，未退出则由 retiredExecutor 禁止重启。 */
  private void awaitTermination(ScheduledExecutorService current) {
    try {
      if (!current.awaitTermination(SHUTDOWN_JOIN_MILLIS, TimeUnit.MILLISECONDS)) {
        log.warn(
            "plugin catalog refresh executor did not exit within {}ms; restart is refused until it does",
            SHUTDOWN_JOIN_MILLIS);
        return;
      }
    } catch (InterruptedException ignored) {
      Thread.currentThread().interrupt();
      return;
    }
    synchronized (lifecycleLock) {
      if (retiredExecutor == current) {
        retiredExecutor = null;
      }
    }
  }

  private static ScheduledExecutorService newRefreshExecutor() {
    return Executors.newSingleThreadScheduledExecutor(
        Thread.ofPlatform().name("plugin-catalog-refresh").daemon(true).factory());
  }

  private static long requirePositiveWholeMillis(Duration interval) {
    Objects.requireNonNull(interval, "interval");
    long nanos;
    try {
      nanos = interval.toNanos();
    } catch (ArithmeticException error) {
      throw new IllegalArgumentException("interval is too large", error);
    }
    if (nanos <= 0 || nanos % 1_000_000 != 0) {
      throw new IllegalArgumentException("interval must be a positive whole-millisecond duration");
    }
    return nanos / 1_000_000;
  }
}
