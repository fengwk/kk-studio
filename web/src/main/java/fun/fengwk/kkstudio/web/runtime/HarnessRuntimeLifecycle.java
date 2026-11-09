package fun.fengwk.kkstudio.web.runtime;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.SmartLifecycle;

import fun.fengwk.kkstudio.harness.runtime.processor.ToolProcessor;
import fun.fengwk.kkstudio.harness.spi.execution.LocalExecutionLifecycle;
import fun.fengwk.kkstudio.platform.daemon.server.EnvironmentDaemonServer;
import fun.fengwk.kkstudio.platform.harness.dispatch.HarnessWorkDispatcher;

import java.time.Duration;
import java.util.Objects;

/**
 * Harness worker dispatcher 生命周期与节点优雅退出排空编排。
 *
 * <p>{@code workersEnabled=false} 时保持控制/查询平面可用，但仍先完成执行日志恢复；重复 start / stop 幂等。start 失败会尝试回滚
 * dispatcher，且 running 状态在任何失败路径都正确复位。stop 只有在底层 dispatcher 成功关闭后才开始排空；异常时保持 running，允许再次停止。start 与
 * stop 串行，避免并发把状态和底层生命周期交错。
 *
 * <p>节点优雅关闭使用一个总 deadline（{@code drainTimeout + leaseDuration}），下层不再各自开启新的 lease 窗口：
 *
 * <ol>
 *   <li>停止 claim 派发（{@link HarnessWorkDispatcher#stop()}）；
 *   <li>等待本地物理执行自然完成至 {@code drainTimeout}，超时后由生命周期端口请求取消；
 *   <li>通知远端守护进程进入排空并释放本地环境路由租约（{@link EnvironmentDaemonServer#drain()}）；
 *   <li>在剩余预算内移交在途远端工具执行并 release TOOL Work（{@link ToolProcessor#drain(Duration)}）；
 *   <li>继续等待被取消的本地 body 结束并落盘退出证明，仍无法证明时保留 journal 锁到 JVM 退出。
 * </ol>
 */
final class HarnessRuntimeLifecycle implements SmartLifecycle {

  private final boolean workersEnabled;
  private final HarnessWorkDispatcher dispatcher;
  private final LocalExecutionLifecycle executionLifecycle;
  private final ToolProcessor toolProcessor;
  private final ObjectProvider<EnvironmentDaemonServer> daemonServerProvider;
  private final Duration drainTimeout;
  private final Duration leaseDuration;

  private final Object lifecycleLock = new Object();
  private boolean started;
  private boolean running;

  /** {@code drainTimeout} 等待本地执行自然完成；{@code leaseDuration} 是该等待之后用于取消、远端移交与退出确认的剩余预算。 */
  HarnessRuntimeLifecycle(
      boolean workersEnabled,
      HarnessWorkDispatcher dispatcher,
      LocalExecutionLifecycle executionLifecycle,
      ToolProcessor toolProcessor,
      ObjectProvider<EnvironmentDaemonServer> daemonServerProvider,
      Duration drainTimeout,
      Duration leaseDuration) {
    this.workersEnabled = workersEnabled;
    this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
    this.executionLifecycle = Objects.requireNonNull(executionLifecycle, "executionLifecycle");
    this.toolProcessor = Objects.requireNonNull(toolProcessor, "toolProcessor");
    this.daemonServerProvider =
        Objects.requireNonNull(daemonServerProvider, "daemonServerProvider");
    Objects.requireNonNull(drainTimeout, "drainTimeout");
    Objects.requireNonNull(leaseDuration, "leaseDuration");
    if (drainTimeout.isNegative()) {
      throw new IllegalArgumentException("drainTimeout must not be negative");
    }
    if (leaseDuration.isNegative()) {
      throw new IllegalArgumentException("leaseDuration must not be negative");
    }
    this.drainTimeout = drainTimeout;
    this.leaseDuration = leaseDuration;
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
      Duration totalBudget = drainTimeout.plus(leaseDuration);
      executionLifecycle.drain(drainTimeout);
      daemonServerProvider.ifAvailable(EnvironmentDaemonServer::drain);
      toolProcessor.drain(remaining(totalBudget, stopStartedAt));
      executionLifecycle.drain(remaining(totalBudget, stopStartedAt));
      executionLifecycle.close();
      started = false;
    }
  }

  @Override
  public void stop(Runnable callback) {
    try {
      stop();
    } finally {
      callback.run();
    }
  }

  private static Duration remaining(Duration totalBudget, long startedAtNanos) {
    long elapsed = System.nanoTime() - startedAtNanos;
    Duration remaining = totalBudget.minusNanos(elapsed);
    return remaining.isNegative() ? Duration.ZERO : remaining;
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
