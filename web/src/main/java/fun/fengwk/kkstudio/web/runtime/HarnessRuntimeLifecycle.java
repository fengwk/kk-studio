package fun.fengwk.kkstudio.web.runtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.SmartLifecycle;

import fun.fengwk.kkstudio.harness.runtime.processor.ToolProcessor;
import fun.fengwk.kkstudio.platform.daemon.server.EnvironmentDaemonServer;
import fun.fengwk.kkstudio.platform.harness.dispatch.HarnessWorkDispatcher;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * Harness worker dispatcher 生命周期与节点优雅退出排空编排。
 *
 * <p>{@code workersEnabled=false} 时保持控制/查询平面可用，但不启动 dispatcher；重复 start / stop 幂等。start 失败会尝试回滚
 * dispatcher，且 running 状态在任何失败路径都正确复位。stop 只有在底层 dispatcher 成功关闭后才标记为 stopped；异常时保持
 * running，允许再次停止。start 与 stop 串行，避免并发把状态和底层生命周期交错。
 *
 * <p>节点优雅关闭编排顺序（SmartLifecycle Phase = {@link Integer#MAX_VALUE}，在 {@code ClusterNodeLifecycle}
 * 之前停止）：
 *
 * <ol>
 *   <li>停止 claim 派发（{@link HarnessWorkDispatcher#stop()}）；
 *   <li>等待本机 Model / Tool 执行完成（至多等待 {@code drainTimeout}，默认 60s，50ms 轮询）；
 *   <li>通知远端守护进程进入排空并释放本地环境路由租约（{@link EnvironmentDaemonServer#drain()}）；
 *   <li>移交在途远端工具执行并 release TOOL Work（{@link ToolProcessor#drain()}）；
 *   <li>后续由 Spring 容器销毁各 Processor 与 Dispatcher 资源。
 * </ol>
 */
final class HarnessRuntimeLifecycle implements SmartLifecycle {

  private static final Logger log = LoggerFactory.getLogger(HarnessRuntimeLifecycle.class);
  private static final Duration POLL_INTERVAL = Duration.ofMillis(50);

  private final boolean workersEnabled;
  private final HarnessWorkDispatcher dispatcher;
  private final BooleanSupplier driverExecutionActive;
  private final ToolProcessor toolProcessor;
  private final ObjectProvider<EnvironmentDaemonServer> daemonServerProvider;
  private final Duration drainTimeout;

  private final Object lifecycleLock = new Object();
  private boolean running;

  /** {@code driverExecutionActive} 报告本机是否仍有 Driver 执行，由装配方提供，本类不依赖具体 Agent Driver 的类型。 */
  HarnessRuntimeLifecycle(
      boolean workersEnabled,
      HarnessWorkDispatcher dispatcher,
      BooleanSupplier driverExecutionActive,
      ToolProcessor toolProcessor,
      ObjectProvider<EnvironmentDaemonServer> daemonServerProvider,
      Duration drainTimeout) {
    this.workersEnabled = workersEnabled;
    this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
    this.driverExecutionActive =
        Objects.requireNonNull(driverExecutionActive, "driverExecutionActive");
    this.toolProcessor = Objects.requireNonNull(toolProcessor, "toolProcessor");
    this.daemonServerProvider =
        Objects.requireNonNull(daemonServerProvider, "daemonServerProvider");
    Objects.requireNonNull(drainTimeout, "drainTimeout");
    if (drainTimeout.isNegative()) {
      throw new IllegalArgumentException("drainTimeout must not be negative");
    }
    this.drainTimeout = drainTimeout;
  }

  @Override
  public void start() {
    synchronized (lifecycleLock) {
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
      if (!running) {
        return;
      }
      dispatcher.stop();
      running = false;
      waitForExecutions();
      daemonServerProvider.ifAvailable(EnvironmentDaemonServer::drain);
      toolProcessor.drain();
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

  private void waitForExecutions() {
    if (drainTimeout.isZero()) {
      return;
    }
    Instant deadline = Instant.now().plus(drainTimeout);
    while (hasActiveExecutions() && Instant.now().isBefore(deadline)) {
      long remainingMillis = Duration.between(Instant.now(), deadline).toMillis();
      if (remainingMillis <= 0) {
        break;
      }
      try {
        Thread.sleep(Math.min(POLL_INTERVAL.toMillis(), remainingMillis));
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        log.warn("Harness drain wait interrupted, proceeding with drain");
        return;
      }
    }
    if (hasActiveExecutions()) {
      log.warn(
          "Harness drain timeout reached ({}s) while executions still active, proceeding to handover",
          drainTimeout.toSeconds());
    }
  }

  private boolean hasActiveExecutions() {
    return driverExecutionActive.getAsBoolean() || toolProcessor.hasActiveExecution();
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
