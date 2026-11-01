package fun.fengwk.kkstudio.web.runtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.SmartLifecycle;

import fun.fengwk.kkstudio.harness.builtin.processor.ModelProcessor;
import fun.fengwk.kkstudio.harness.environment.server.EnvironmentDaemonServer;
import fun.fengwk.kkstudio.harness.infra.dispatch.HarnessWorkDispatcher;
import fun.fengwk.kkstudio.harness.runtime.processor.ToolProcessor;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Harness worker dispatcher 生命周期与节点优雅退出排空编排。
 *
 * <p>{@code workersEnabled=false} 时保持控制/查询平面可用，但不启动 dispatcher；重复 start / stop 幂等。
 *
 * <p>节点优雅关闭编排顺序（SmartLifecycle Phase = {@link Integer#MAX_VALUE}，在 {@code ClusterNodeLifecycle}
 * 之前停止）：
 *
 * <ol>
 *   <li>停止 claim 派发（{@link HarnessWorkDispatcher#stop()}）；
 *   <li>等待本机 Model / Tool 执行完成（至多等待 {@code drainTimeout}，默认 60s，50ms 轮询）；
 *   <li>通知远端守护进程进入排空并释放本地环境路由租约（{@link EnvironmentDaemonServer#drain()}）；
 *   <li>移交在途远端工具执行并 release TOOL Work（{@link ToolProcessor#drain()}）；
 *   <li>完成停止标记（后续由 Spring 容器销毁各 Processor 与 Dispatcher 资源）。
 * </ol>
 */
final class HarnessRuntimeLifecycle implements SmartLifecycle {

  private static final Logger log = LoggerFactory.getLogger(HarnessRuntimeLifecycle.class);
  private static final Duration DEFAULT_POLL_INTERVAL = Duration.ofMillis(50);

  private final boolean workersEnabled;
  private final HarnessWorkDispatcher dispatcher;
  private final ModelProcessor modelProcessor;
  private final ToolProcessor toolProcessor;
  private final ObjectProvider<EnvironmentDaemonServer> daemonServerProvider;
  private final Duration drainTimeout;
  private final Duration pollInterval;
  private final Sleeper sleeper;

  private final Object lifecycleLock = new Object();
  private boolean running;

  HarnessRuntimeLifecycle(boolean workersEnabled, HarnessWorkDispatcher dispatcher) {
    this(workersEnabled, dispatcher, null, null, null, Duration.ofSeconds(60));
  }

  HarnessRuntimeLifecycle(
      boolean workersEnabled,
      HarnessWorkDispatcher dispatcher,
      ModelProcessor modelProcessor,
      ToolProcessor toolProcessor,
      ObjectProvider<EnvironmentDaemonServer> daemonServerProvider,
      Duration drainTimeout) {
    this(
        workersEnabled,
        dispatcher,
        modelProcessor,
        toolProcessor,
        daemonServerProvider,
        drainTimeout,
        DEFAULT_POLL_INTERVAL,
        Thread::sleep);
  }

  HarnessRuntimeLifecycle(
      boolean workersEnabled,
      HarnessWorkDispatcher dispatcher,
      ModelProcessor modelProcessor,
      ToolProcessor toolProcessor,
      ObjectProvider<EnvironmentDaemonServer> daemonServerProvider,
      Duration drainTimeout,
      Duration pollInterval,
      Sleeper sleeper) {
    this.workersEnabled = workersEnabled;
    this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
    this.modelProcessor = modelProcessor;
    this.toolProcessor = toolProcessor;
    this.daemonServerProvider = daemonServerProvider;
    this.drainTimeout = drainTimeout != null ? drainTimeout : Duration.ZERO;
    this.pollInterval = pollInterval != null ? pollInterval : DEFAULT_POLL_INTERVAL;
    this.sleeper = sleeper != null ? sleeper : Thread::sleep;
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
      try {
        waitForExecutions();
      } catch (RuntimeException failure) {
        log.warn("Error during harness execution drain wait", failure);
      }
      try {
        if (daemonServerProvider != null) {
          daemonServerProvider.ifAvailable(EnvironmentDaemonServer::drain);
        }
      } catch (RuntimeException failure) {
        log.warn("Failed to drain environment daemon server cleanly", failure);
      } finally {
        if (toolProcessor != null) {
          toolProcessor.drain();
        }
        running = false;
      }
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
    if (drainTimeout.isZero() || drainTimeout.isNegative()) {
      return;
    }
    Instant deadline = Instant.now().plus(drainTimeout);
    while (hasActiveExecutions() && Instant.now().isBefore(deadline)) {
      long remainingMillis = Duration.between(Instant.now(), deadline).toMillis();
      if (remainingMillis <= 0) {
        break;
      }
      try {
        sleeper.sleep(Math.min(pollInterval.toMillis(), remainingMillis));
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
    return (modelProcessor != null && modelProcessor.hasActiveExecution())
        || (toolProcessor != null && toolProcessor.hasActiveExecution());
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

  @FunctionalInterface
  interface Sleeper {
    void sleep(long millis) throws InterruptedException;
  }
}
