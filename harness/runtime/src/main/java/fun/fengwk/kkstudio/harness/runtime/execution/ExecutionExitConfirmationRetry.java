package fun.fengwk.kkstudio.harness.runtime.execution;

import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 基于本机 journal 的退出确认重试调度。
 *
 * <p>定时线程只负责把重放任务派发到 worker；JDBC 与文件读取都在 worker 上执行。关闭时有界取消调度、停止 worker，再由 journal 的 close 释放目录锁。
 */
@Slf4j
public final class ExecutionExitConfirmationRetry implements AutoCloseable {

  private static final Duration INTERVAL = Duration.ofSeconds(1);
  private static final Duration SHUTDOWN_WAIT = Duration.ofSeconds(5);

  private final ExecutionExitConfirmer confirmer;
  private final ScheduledExecutorService timer;
  private final ExecutorService worker;
  private final AtomicBoolean closed = new AtomicBoolean();
  private final AtomicBoolean replayInFlight = new AtomicBoolean();
  private ScheduledFuture<?> task;

  public ExecutionExitConfirmationRetry(ExecutionExitConfirmer confirmer) {
    this(
        confirmer,
        Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform()
                .name("execution-exit-confirmation-timer", 0L)
                .daemon(true)
                .factory()),
        Executors.newSingleThreadExecutor(
            Thread.ofPlatform()
                .name("execution-exit-confirmation-worker", 0L)
                .daemon(true)
                .factory()));
  }

  /** 包内可见的 executor 注入只用于确定性测试；生产构造器仍创建真实单线程 timer/worker。 */
  ExecutionExitConfirmationRetry(
      ExecutionExitConfirmer confirmer, ScheduledExecutorService timer, ExecutorService worker) {
    this.confirmer = Objects.requireNonNull(confirmer, "confirmer");
    this.timer = Objects.requireNonNull(timer, "timer");
    this.worker = Objects.requireNonNull(worker, "worker");
  }

  /** 启动周期重放；重复调用幂等。 */
  public synchronized void start() {
    if (task != null || closed.get()) {
      return;
    }
    task =
        timer.scheduleWithFixedDelay(
            this::dispatch, INTERVAL.toMillis(), INTERVAL.toMillis(), TimeUnit.MILLISECONDS);
  }

  private void dispatch() {
    if (closed.get() || !replayInFlight.compareAndSet(false, true)) {
      return;
    }
    try {
      worker.execute(this::replay);
    } catch (RejectedExecutionException ignored) {
      // 关闭竞态：worker 已停止，不再产生新的重放。
      replayInFlight.set(false);
    }
  }

  private void replay() {
    try {
      if (!closed.get()) {
        confirmer.replayPending();
      }
    } catch (RuntimeException failure) {
      log.warn(
          "cannot replay execution exit confirmations;"
              + " phase=EXIT_CONFIRMATION_REPLAY; exception={}",
          failure.getClass().getSimpleName());
    } finally {
      replayInFlight.set(false);
    }
  }

  @Override
  public synchronized void close() {
    if (!closed.compareAndSet(false, true)) {
      return;
    }
    if (task != null) {
      task.cancel(false);
    }
    timer.shutdownNow();
    worker.shutdownNow();
    await(timer, worker);
  }

  private static void await(ExecutorService... executors) {
    boolean interrupted = false;
    for (ExecutorService executor : executors) {
      if (Thread.interrupted()) {
        interrupted = true;
      }
      try {
        if (!executor.awaitTermination(SHUTDOWN_WAIT.toMillis(), TimeUnit.MILLISECONDS)) {
          log.warn("execution exit confirmation executor did not terminate in time");
        }
      } catch (InterruptedException error) {
        interrupted = true;
      }
    }
    if (interrupted) {
      Thread.currentThread().interrupt();
    }
  }
}
