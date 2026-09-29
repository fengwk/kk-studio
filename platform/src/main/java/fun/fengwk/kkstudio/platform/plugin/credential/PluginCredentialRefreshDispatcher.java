package fun.fengwk.kkstudio.platform.plugin.credential;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;

import fun.fengwk.kkstudio.platform.plugin.PluginProperties;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 凭据刷新 dispatcher：按数据库最早到期时刻做单次调度，而不是只按固定间隔轮询。
 *
 * <p>每次扫描结束后读取最早可调度的 {@code next_refresh_at} 并安排一次 timer；{@code pollDelay} 只是没有更早到期时刻时的最慢兜底，
 * 也是跨节点没有本地唤醒时的恢复上界。凭据保存成功后 {@link #wake()} 立刻触发一次扫描，因此刚登录的短寿命 token 不必等到兜底轮询。
 *
 * <p>重复触发会被合并：扫描进行中到达的唤醒只置一个待处理标记，由在途扫描结束后补跑一次，既不丢失也不堆积；同一时刻最多只有一个待触发 timer（重新排期会先取消旧 timer）。{@link
 * #stop()} 之后调度不复活，wake 与 stop 竞争也不会创建新任务。
 */
@Slf4j
public final class PluginCredentialRefreshDispatcher implements SmartLifecycle {

  private static final long SHUTDOWN_TIMEOUT_SECONDS = 5L;

  private final PluginCredentialRefreshService refreshService;
  private final PluginProperties properties;
  private final Clock clock;
  private final Object lifecycleLock = new Object();
  private final AtomicBoolean scanRunning = new AtomicBoolean();

  private ScheduledExecutorService executor;
  private ScheduledFuture<?> scheduled;
  private volatile boolean running;

  /** 扫描在途时到达的唤醒标记；与 {@link #lifecycleLock} 一起保护，不在此锁内读写会破坏「不丢失」保证。 */
  private boolean wakePending;

  public PluginCredentialRefreshDispatcher(
      PluginCredentialRefreshService refreshService, PluginProperties properties, Clock clock) {
    this.refreshService = Objects.requireNonNull(refreshService, "refreshService");
    this.properties = Objects.requireNonNull(properties, "properties");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  @Override
  public void start() {
    synchronized (lifecycleLock) {
      if (running) {
        return;
      }
      executor =
          Executors.newSingleThreadScheduledExecutor(
              Thread.ofPlatform().daemon().name("plugin-credential-refresher").factory());
      running = true;
      schedule(Duration.ZERO);
    }
  }

  @Override
  public void stop() {
    ScheduledExecutorService current;
    synchronized (lifecycleLock) {
      if (!running) {
        return;
      }
      running = false;
      cancelSchedule();
      current = executor;
      executor = null;
    }
    if (current != null) {
      current.shutdown();
      try {
        if (!current.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
          current.shutdownNow();
        }
      } catch (InterruptedException error) {
        current.shutdownNow();
        Thread.currentThread().interrupt();
      }
    }
  }

  @Override
  public boolean isRunning() {
    return running;
  }

  /** 扫描相位排在最后，使数据库与依赖 bean 全部就绪后才第一次扫描。 */
  @Override
  public int getPhase() {
    return Integer.MAX_VALUE;
  }

  /**
   * 凭据事实变化后的唤醒：未启动时是空操作；空闲时立即排队一次扫描，扫描在途时只置一个待处理标记。
   *
   * <p>待处理标记由在途扫描结束时消费：唤醒对应的提交可能发生在本轮调度读取之后，所以不能直接丢弃；同一批重复唤醒也只补跑一次。标记与提交都在 {@link #lifecycleLock}
   * 内处理，因此不会与「清除扫描标记」竞争而丢失。
   */
  public void wake() {
    synchronized (lifecycleLock) {
      if (!running || executor == null) {
        return;
      }
      if (scanRunning.get()) {
        wakePending = true;
        return;
      }
      submitScan();
    }
  }

  /** 在单线程 executor 上排队一次扫描；停止过程中被拒绝时静默忽略。 */
  private void submitScan() {
    try {
      executor.execute(this::scan);
    } catch (RejectedExecutionException error) {
      log.debug("Plugin credential refresh wake ignored because the dispatcher is stopping");
    }
  }

  private void scan() {
    if (!scanRunning.compareAndSet(false, true)) {
      return;
    }
    boolean progressed = false;
    try {
      // 触发本次扫描的 timer 已被消费：顺便取消可能在途的其它 timer，保证同一时刻最多只有一个待触发调度。
      synchronized (lifecycleLock) {
        cancelSchedule();
      }
      progressed = refreshService.refreshOnce() > 0;
    } catch (RuntimeException error) {
      log.warn("Plugin credential refresh scan failed", error);
    } finally {
      scheduleNext(progressed);
      finishScan();
    }
  }

  /**
   * 结束本轮扫描并把等待中的唤醒交给下一次扫描。
   *
   * <p>先清除扫描标记、再在锁内消费待处理标记：标记清除前到达的唤醒只会置标记（这里必然消费到），清除后到达的唤醒会从 {@link #wake()} 直接排队，两种顺序都不会丢失。
   */
  private void finishScan() {
    scanRunning.set(false);
    synchronized (lifecycleLock) {
      if (!running || executor == null || !wakePending) {
        return;
      }
      wakePending = false;
      submitScan();
    }
  }

  /**
   * 按最早到期时刻安排下一次扫描；没有更早的到期行时退化为 {@code pollDelay} 兜底。
   *
   * <p>已经到期、但本轮一条都没有终结的行（例如主密钥不可用）退回兜底轮询，避免零延迟重扫形成热点循环。
   */
  private void scheduleNext(boolean progressed) {
    Duration delay = properties.getRefresh().getPollDelay();
    try {
      Optional<Instant> earliest = refreshService.earliestRefreshAt();
      if (earliest.isPresent()) {
        Duration untilDue = Duration.between(clock.instant(), earliest.get());
        if (untilDue.compareTo(delay) < 0 && (progressed || !untilDue.isNegative())) {
          delay = untilDue.isNegative() ? Duration.ZERO : untilDue;
        }
      }
    } catch (RuntimeException error) {
      log.warn("Cannot read the next plugin credential refresh time; using poll delay", error);
    }
    synchronized (lifecycleLock) {
      if (!running || executor == null) {
        return;
      }
      schedule(delay);
    }
  }

  private void schedule(Duration delay) {
    cancelSchedule();
    scheduled =
        executor.schedule(this::scan, Math.max(0L, delay.toMillis()), TimeUnit.MILLISECONDS);
  }

  private void cancelSchedule() {
    if (scheduled != null) {
      scheduled.cancel(false);
      scheduled = null;
    }
  }
}
