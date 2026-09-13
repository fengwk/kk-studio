package fun.fengwk.kkstudio.web.runtime;

import org.springframework.context.SmartLifecycle;

import fun.fengwk.kkstudio.platform.environment.operation.EnvironmentOperationDispatcher;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Environment Skill 来源异步管理操作分发器的 Web 层生命周期。
 *
 * <p>始终自动启动（不受 workers-enabled 控制，Dev 节点仍可拥有宿主 lease 并派发管理操作）； 重复 start / stop 幂等。start 失败会尝试回滚
 * dispatcher，且 running 状态在任何失败路径都正确复位。
 */
final class EnvironmentOperationDispatcherLifecycle implements SmartLifecycle {

  private final EnvironmentOperationDispatcher dispatcher;
  private final AtomicBoolean running = new AtomicBoolean(false);

  EnvironmentOperationDispatcherLifecycle(EnvironmentOperationDispatcher dispatcher) {
    this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
  }

  @Override
  public void start() {
    if (!running.compareAndSet(false, true)) {
      return;
    }
    try {
      dispatcher.start();
    } catch (RuntimeException failure) {
      RuntimeException rollbackFailure = stopDispatcher();
      running.set(false);
      if (rollbackFailure != null) {
        failure.addSuppressed(rollbackFailure);
      }
      throw failure;
    }
  }

  @Override
  public void stop() {
    if (!running.compareAndSet(true, false)) {
      return;
    }
    RuntimeException failure = stopDispatcher();
    if (failure != null) {
      throw failure;
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
    return running.get();
  }

  @Override
  public boolean isAutoStartup() {
    return true;
  }

  @Override
  public int getPhase() {
    return Integer.MAX_VALUE - 1;
  }
}
