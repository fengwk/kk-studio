package fun.fengwk.kkstudio.web.runtime;

import org.springframework.context.SmartLifecycle;

import fun.fengwk.kkstudio.platform.environment.operation.EnvironmentOperationDispatcher;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Environment Skill 来源异步管理操作分发器的 Web 层生命周期。
 *
 * <p>始终自动启动（独立于 {@code workers-enabled}）。在 {@code workers-enabled=false} 的 Dev 节点，应用作为提交与查询控制面运行；
 * 分发器在所有节点均自启动运行，但依赖 SQL 层的 Daemon 租约防护，仅当前持有该 Daemon 租约的节点（NAS 拓扑中通常为 Main 节点）才会真正抢占并派发执行。 重复
 * start / stop 保持幂等。start 失败会尝试回滚 dispatcher，且 running 状态在任何失败路径都正确复位。
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
