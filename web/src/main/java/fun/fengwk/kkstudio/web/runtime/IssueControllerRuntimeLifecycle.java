package fun.fengwk.kkstudio.web.runtime;

import org.springframework.context.SmartLifecycle;

import fun.fengwk.kkstudio.platform.project.controller.IssueControllerDispatcher;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Issue Controller dispatcher 生命周期。
 *
 * <p>{@code workersEnabled=false} 时不启动 dispatcher；重复 start / stop 幂等。 start 失败会尝试回滚 dispatcher，且
 * running 状态在任何失败路径都正确复位。
 */
final class IssueControllerRuntimeLifecycle implements SmartLifecycle {

  private final boolean workersEnabled;
  private final IssueControllerDispatcher dispatcher;
  private final AtomicBoolean running = new AtomicBoolean(false);

  IssueControllerRuntimeLifecycle(boolean workersEnabled, IssueControllerDispatcher dispatcher) {
    this.workersEnabled = workersEnabled;
    this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
  }

  @Override
  public void start() {
    if (!workersEnabled || !running.compareAndSet(false, true)) {
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
