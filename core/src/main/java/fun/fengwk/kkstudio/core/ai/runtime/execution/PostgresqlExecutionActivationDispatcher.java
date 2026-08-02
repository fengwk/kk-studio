package fun.fengwk.kkstudio.core.ai.runtime.execution;

import lombok.extern.slf4j.Slf4j;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 单进程 PostgreSQL ExecutionActivation 分发器。
 *
 * <p>所有扫描循环在同一个执行器中串行运行；处理器只接收无锁快照，并在自己的事务中完成行锁和目标状态推进。 Environment 不在当前节点 READY 时，记录会保留在持久化事实中。
 */
@Slf4j
public final class PostgresqlExecutionActivationDispatcher {

  private static final int MAX_ROWS_PER_DRAIN = 64;

  private final ExecutionActivationStore store;
  private final ExecutionActivationEnvironmentEligibility environmentEligibility;
  private final ExecutionActivationHandler handler;
  private final Clock clock;
  private final ExecutorService drainExecutor;
  private final ScheduledExecutorService wakeExecutor;

  private final AtomicBoolean started = new AtomicBoolean(false);
  private final AtomicBoolean stopping = new AtomicBoolean(false);
  private final AtomicBoolean wakeRequested = new AtomicBoolean(false);
  private final AtomicBoolean drainRunning = new AtomicBoolean(false);
  private final AtomicReference<ScheduledFuture<?>> wakeTimer = new AtomicReference<>();

  public PostgresqlExecutionActivationDispatcher(
      ExecutionActivationStore store,
      ExecutionActivationEnvironmentEligibility environmentEligibility,
      ExecutionActivationHandler handler,
      Clock clock,
      ExecutorService drainExecutor,
      ScheduledExecutorService wakeExecutor) {
    this.store = Objects.requireNonNull(store, "store");
    this.environmentEligibility =
        Objects.requireNonNull(environmentEligibility, "environmentEligibility");
    this.handler = Objects.requireNonNull(handler, "handler");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.drainExecutor = Objects.requireNonNull(drainExecutor, "drainExecutor");
    this.wakeExecutor = Objects.requireNonNull(wakeExecutor, "wakeExecutor");
  }

  /** 提交首次唤醒，重复调用不会重复启动扫描。 */
  public void start() {
    if (!started.compareAndSet(false, true)) {
      return;
    }
    wake();
  }

  /** 合并唤醒请求，保证同一时刻只有一个扫描循环。 */
  public void wake() {
    if (stopping.get() || !started.get()) {
      return;
    }
    wakeRequested.set(true);
    submitDrainIfIdle();
  }

  private void submitDrainIfIdle() {
    if (drainRunning.compareAndSet(false, true)) {
      try {
        drainExecutor.execute(this::drainLoop);
      } catch (RejectedExecutionException rejection) {
        drainRunning.set(false);
        throw rejection;
      }
    }
  }

  /** 停止唤醒并取消最近 wakeAt 定时器。 */
  public void stop() {
    stopping.set(true);
    ScheduledFuture<?> timer = wakeTimer.getAndSet(null);
    if (timer != null) {
      timer.cancel(false);
    }
    wakeRequested.set(true);
  }

  private void drainLoop() {
    try {
      while (true) {
        if (!wakeRequested.compareAndSet(true, false)) {
          return;
        }
        if (stopping.get()) {
          return;
        }
        drainOnce();
      }
    } catch (RuntimeException error) {
      log.warn("execution activation drain failed", error);
    } finally {
      drainRunning.set(false);
      if (wakeRequested.get()) {
        try {
          wake();
        } catch (RejectedExecutionException rejection) {
          if (!stopping.get()) {
            log.warn("execution activation drain resubmission rejected", rejection);
          }
        }
      }
    }
  }

  private void drainOnce() {
    Instant now = clock.instant();
    List<ExecutionActivation> activations;
    try {
      activations = store.findEligibleDue(environmentEligibility, now, MAX_ROWS_PER_DRAIN);
    } catch (RuntimeException error) {
      log.warn("execution activation due scan failed", error);
      return;
    }
    for (ExecutionActivation activation : activations) {
      if (stopping.get()) {
        return;
      }
      boolean handled;
      try {
        handled = handler.handle(activation);
      } catch (RuntimeException error) {
        log.warn("execution activation handler failed for {}", activation, error);
        handled = false;
      }
      if (!handled) {
        log.debug("execution activation handler reported stale row {}", activation);
      }
    }
    armNextTimer();
  }

  private void armNextTimer() {
    Optional<Instant> next = store.findNearestEligibleWakeAt(environmentEligibility);
    ScheduledFuture<?> previous = wakeTimer.getAndSet(null);
    if (previous != null) {
      previous.cancel(false);
    }
    if (next.isEmpty()) {
      return;
    }
    long delayMillis = delayMillis(next.get());
    ScheduledFuture<?> scheduled =
        wakeExecutor.schedule(this::wake, delayMillis, TimeUnit.MILLISECONDS);
    wakeTimer.set(scheduled);
    if (stopping.get()) {
      scheduled.cancel(false);
      wakeTimer.compareAndSet(scheduled, null);
    }
  }

  private long delayMillis(Instant target) {
    Duration duration = Duration.between(clock.instant(), target);
    return Math.max(1L, duration.toMillis());
  }
}
