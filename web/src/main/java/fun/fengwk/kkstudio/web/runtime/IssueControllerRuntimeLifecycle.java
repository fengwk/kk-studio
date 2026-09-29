package fun.fengwk.kkstudio.web.runtime;

import org.springframework.context.SmartLifecycle;

import fun.fengwk.kkstudio.project.controller.IssueControllerDispatcher;

import java.util.Objects;

/**
 * Issue Controller dispatcher 生命周期。
 *
 * <p>{@code workersEnabled=false} 时不启动 dispatcher；重复 start / stop 幂等。 start 失败会尝试回滚 dispatcher，且
 * running 状态在任何失败路径都正确复位。stop 只有在底层 dispatcher 成功关闭后才标记为 stopped；异常时保持 running，允许再次停止。 start 与 stop
 * 串行，避免并发交错。
 *
 * <p>当前 {@link IssueControllerDispatcher#stop()} 在置位后不会抛出；本类仍按“抛出即尚未可靠关闭”处理，以便故障注入和未来实现变化 都能重试。
 */
final class IssueControllerRuntimeLifecycle implements SmartLifecycle {

  private final boolean workersEnabled;
  private final IssueControllerDispatcher dispatcher;
  private final Object lifecycleLock = new Object();
  private boolean running;

  IssueControllerRuntimeLifecycle(boolean workersEnabled, IssueControllerDispatcher dispatcher) {
    this.workersEnabled = workersEnabled;
    this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
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
