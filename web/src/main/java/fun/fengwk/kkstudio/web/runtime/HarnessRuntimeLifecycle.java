package fun.fengwk.kkstudio.web.runtime;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.SmartLifecycle;

import fun.fengwk.kkstudio.harness.runtime.processor.ToolProcessor;
import fun.fengwk.kkstudio.harness.spi.execution.LocalExecutionLifecycle;
import fun.fengwk.kkstudio.platform.daemon.server.EnvironmentDaemonServer;
import fun.fengwk.kkstudio.platform.harness.dispatch.HarnessWorkDispatcher;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Harness worker dispatcher 生命周期与节点优雅退出排空编排。
 *
 * <p>{@code workersEnabled=false} 时保持控制/查询平面可用，但仍先完成执行日志恢复；重复 start / stop 幂等。start 失败会尝试回滚
 * dispatcher，且 running 状态在任何失败路径都正确复位。stop 只有在底层 dispatcher 成功关闭后才开始排空；dispatcher.stop 异常时保持
 * running，允许再次停止；排空阶段失败会先关闭物理生命周期并保留锁，再上报首个异常。start 与 stop 串行，避免并发把状态和底层生命周期交错。
 *
 * <p>节点优雅关闭使用一个总 deadline（{@code drainTimeout + leaseDuration}），下层不再各自开启新的 lease 窗口：
 *
 * <ol>
 *   <li>停止 claim 派发（{@link HarnessWorkDispatcher#stop()}）；
 *   <li>等待本地物理执行自然完成至 {@code drainTimeout}，超时后由生命周期端口请求取消；
 *   <li>在隔离虚拟线程中执行 daemon 排空与 Tool handover，协调线程只等待同一个单调 deadline；超时或中断时请求中断后台清理，但不把它当成物理退出或 REMOTE
 *       ACK；
 *   <li>继续以剩余预算等待被取消的本地 body 结束并落盘退出证明，再关闭物理生命周期；仍无法证明时保留 journal 锁到 JVM 退出。
 * </ol>
 */
@Slf4j
final class HarnessRuntimeLifecycle implements SmartLifecycle {

  private final boolean workersEnabled;
  private final HarnessWorkDispatcher dispatcher;
  private final LocalExecutionLifecycle executionLifecycle;
  private final ToolProcessor toolProcessor;
  private final ObjectProvider<EnvironmentDaemonServer> daemonServerProvider;
  private final Duration drainTimeout;
  private final Supplier<Duration> leaseDurationProvider;

  private final Object lifecycleLock = new Object();
  private boolean started;
  private boolean running;

  /** {@code drainTimeout} 等待本地执行自然完成；关闭时使用当前租约时长作为剩余预算。 */
  HarnessRuntimeLifecycle(
      boolean workersEnabled,
      HarnessWorkDispatcher dispatcher,
      LocalExecutionLifecycle executionLifecycle,
      ToolProcessor toolProcessor,
      ObjectProvider<EnvironmentDaemonServer> daemonServerProvider,
      Duration drainTimeout,
      Supplier<Duration> leaseDurationProvider) {
    this.workersEnabled = workersEnabled;
    this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
    this.executionLifecycle = Objects.requireNonNull(executionLifecycle, "executionLifecycle");
    this.toolProcessor = Objects.requireNonNull(toolProcessor, "toolProcessor");
    this.daemonServerProvider =
        Objects.requireNonNull(daemonServerProvider, "daemonServerProvider");
    Objects.requireNonNull(drainTimeout, "drainTimeout");
    this.leaseDurationProvider =
        Objects.requireNonNull(leaseDurationProvider, "leaseDurationProvider");
    if (drainTimeout.isNegative()) {
      throw new IllegalArgumentException("drainTimeout must not be negative");
    }
    this.drainTimeout = drainTimeout;
  }

  @Override
  public void start() {
    synchronized (lifecycleLock) {
      if (!started) {
        executionLifecycle.start();
        started = true;
      }
      if (!workersEnabled || running) {
        return;
      }
      running = true;
      try {
        dispatcher.start();
      } catch (RuntimeException failure) {
        RuntimeException rollbackFailure = stopDispatcher();
        running = false;
        if (rollbackFailure != null) {
          failure.addSuppressed(rollbackFailure);
        }
        throw failure;
      }
    }
  }

  @Override
  public void stop() {
    synchronized (lifecycleLock) {
      if (!started) {
        return;
      }
      if (running) {
        // 停止失败时保持 running，允许上层重试；此时不开始新的排空窗口。
        dispatcher.stop();
        running = false;
      }
      long stopStartedAt = System.nanoTime();
      Duration leaseDuration = Objects.requireNonNull(leaseDurationProvider.get(), "leaseDuration");
      if (leaseDuration.isNegative()) {
        throw new IllegalArgumentException("leaseDuration must not be negative");
      }
      long deadlineNanos = stopStartedAt + drainTimeout.plus(leaseDuration).toNanos();
      AtomicReference<Throwable> stopFailure = new AtomicReference<>();

      try {
        executionLifecycle.drain(drainTimeout);
      } catch (Throwable failure) {
        recordFailure(stopFailure, "local execution drain", failure);
      }

      Thread cleanupThread =
          Thread.ofVirtual()
              .name("harness-runtime-remote-cleanup")
              .start(
                  () -> {
                    try {
                      daemonServerProvider.ifAvailable(EnvironmentDaemonServer::drain);
                      toolProcessor.drain(remainingUntil(deadlineNanos));
                    } catch (Throwable failure) {
                      recordFailure(stopFailure, "remote cleanup", failure);
                    }
                  });

      awaitRemoteCleanup(cleanupThread, deadlineNanos);

      try {
        executionLifecycle.drain(remainingUntil(deadlineNanos));
      } catch (Throwable failure) {
        recordFailure(stopFailure, "local execution drain after remote cleanup", failure);
      }
      try {
        executionLifecycle.close();
      } catch (Throwable failure) {
        recordFailure(stopFailure, "local execution lifecycle close", failure);
      } finally {
        started = false;
      }
      Throwable failure = stopFailure.get();
      if (failure != null) {
        throw propagate(failure);
      }
    }
  }

  /** 协调线程只等待后台 cleanup 到同一单调 deadline；超时或中断后请求中断，不再阻塞。 */
  private void awaitRemoteCleanup(Thread cleanupThread, long deadlineNanos) {
    Duration remaining = remainingUntil(deadlineNanos);
    try {
      cleanupThread.join(remaining);
    } catch (InterruptedException interruption) {
      Thread.currentThread().interrupt();
      log.warn(
          "harness runtime remote cleanup wait interrupted; phase=RUNTIME_REMOTE_CLEANUP;"
              + " remaining={}; the background cleanup is being interrupted",
          remaining);
      cleanupThread.interrupt();
      return;
    }
    if (cleanupThread.isAlive()) {
      log.warn(
          "harness runtime remote cleanup exceeded the shutdown deadline;"
              + " phase=RUNTIME_REMOTE_CLEANUP; remaining={}; the background cleanup is being"
              + " interrupted",
          remaining);
      cleanupThread.interrupt();
    }
  }

  private static void recordFailure(
      AtomicReference<Throwable> stopFailure, String phase, Throwable failure) {
    stopFailure.compareAndSet(null, failure);
    log.error(
        "harness runtime stop stage failed; phase={}; exception={}",
        phase,
        failure.getClass().getSimpleName(),
        failure);
  }

  private static RuntimeException propagate(Throwable failure) {
    if (failure instanceof RuntimeException runtimeFailure) {
      return runtimeFailure;
    }
    if (failure instanceof Error error) {
      throw error;
    }
    return new IllegalStateException("harness runtime stop failed", failure);
  }

  private static Duration remainingUntil(long deadlineNanos) {
    long remainingNanos = deadlineNanos - System.nanoTime();
    return remainingNanos <= 0 ? Duration.ZERO : Duration.ofNanos(remainingNanos);
  }

  @Override
  public void stop(Runnable callback) {
    try {
      stop();
    } finally {
      callback.run();
    }
  }

  private RuntimeException stopDispatcher() {
    try {
      dispatcher.stop();
      return null;
    } catch (RuntimeException failure) {
      return failure;
    }
  }

  @Override
  public boolean isRunning() {
    synchronized (lifecycleLock) {
      return running;
    }
  }

  @Override
  public boolean isAutoStartup() {
    return true;
  }

  @Override
  public int getPhase() {
    return Integer.MAX_VALUE;
  }
}
