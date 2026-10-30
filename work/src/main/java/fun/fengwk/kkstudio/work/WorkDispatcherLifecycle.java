package fun.fengwk.kkstudio.work;

import org.springframework.context.SmartLifecycle;

import java.util.Objects;

/**
 * {@link BoundedWorkDispatcher} 的 Spring 生命周期。
 *
 * <p>{@code enabled=false} 时不启动 dispatcher；重复 start / stop 幂等。start 失败会尝试回滚 dispatcher，且 running
 * 状态在任何失败路径都正确复位。stop 只有在底层 dispatcher 成功关闭后才标记为 stopped；异常时保持 running，允许再次停止。start 与 stop
 * 串行，避免并发交错。
 *
 * <p>当前 {@link BoundedWorkDispatcher#stop()} 在置位后不会抛出；本类仍按“抛出即尚未可靠关闭”处理，以便故障注入和未来实现变化都能重试。调度器本身不实现
 * Spring 生命周期，避免被容器自动启动。
 */
public final class WorkDispatcherLifecycle implements SmartLifecycle {

  private final boolean enabled;
  private final BoundedWorkDispatcher<?> dispatcher;
  private final Object lifecycleLock = new Object();
  private boolean running;

  public WorkDispatcherLifecycle(boolean enabled, BoundedWorkDispatcher<?> dispatcher) {
    this.enabled = enabled;
    this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
  }

  @Override
  public void start() {
    synchronized (lifecycleLock) {
      if (!enabled || running) {
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
    return Integer.MAX_VALUE - 1;
  }
}
