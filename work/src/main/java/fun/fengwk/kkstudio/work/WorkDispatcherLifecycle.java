package fun.fengwk.kkstudio.work;

import org.springframework.context.SmartLifecycle;

import java.util.Objects;

/**
 * {@link BoundedWorkDispatcher} 的 Spring 生命周期。
 *
 * <p>{@code enabled=false} 时不启动 dispatcher；重复 start / stop 幂等。start 失败会关闭 dispatcher 并复位
 * running。start 与 stop 串行，避免并发交错。调度器本身不实现 Spring 生命周期，避免被容器自动启动。
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
        dispatcher.stop();
        running = false;
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
