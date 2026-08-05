package fun.fengwk.kkstudio.web.runtime;

import org.springframework.context.SmartLifecycle;

import fun.fengwk.kkstudio.harness.runtime.spring.dispatch.HarnessWorkDispatcher;
import fun.fengwk.kkstudio.harness.runtime.spring.postgresql.PostgresqlWorkListener;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Harness worker 生命周期：启动顺序 dispatcher -&gt; listener，停止顺序 listener -&gt; dispatcher。
 *
 * <p>{@code workersEnabled=false} 时保持控制/查询平面可用，但不启动 dispatcher / listener；重复 start / stop 幂等。 start
 * 失败回滚与 stop 都 failure-safe：总是先 listener 后 dispatcher stop、保留首个异常并抑制后续异常；running 状态在
 * 任何失败路径都正确复位，因此失败后的 start 重试不会被跳过。processor 关闭与 executor shutdown 不在这里处理，由 Spring 按依赖逆序
 * destroy（dispatcher -&gt; processor -&gt; executor）保证。
 */
final class HarnessRuntimeLifecycle implements SmartLifecycle {

  private final boolean workersEnabled;
  private final HarnessWorkDispatcher dispatcher;
  private final PostgresqlWorkListener listener;
  private final AtomicBoolean running = new AtomicBoolean(false);

  HarnessRuntimeLifecycle(
      boolean workersEnabled, HarnessWorkDispatcher dispatcher, PostgresqlWorkListener listener) {
    this.workersEnabled = workersEnabled;
    this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
    this.listener = Objects.requireNonNull(listener, "listener");
  }

  @Override
  public void start() {
    if (!workersEnabled || !running.compareAndSet(false, true)) {
      return;
    }
    try {
      dispatcher.start();
      listener.start();
    } catch (RuntimeException failure) {
      // 回滚：总是尝试 listener 后 dispatcher stop（各自失败只记录抑制），保留首个异常；running 复位保证重试不被跳过。
      RuntimeException rollbackFailure = stopComponents();
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
    RuntimeException failure = stopComponents();
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

  /**
   * 停止顺序恒为 listener -&gt; dispatcher；两边都被尝试，首个异常保留，后续异常 addSuppressed。
   *
   * @return 首个 stop 失败；无失败返回 null
   */
  private RuntimeException stopComponents() {
    RuntimeException first = null;
    try {
      listener.stop();
    } catch (RuntimeException failure) {
      first = failure;
    }
    try {
      dispatcher.stop();
    } catch (RuntimeException failure) {
      if (first == null) {
        first = failure;
      } else {
        first.addSuppressed(failure);
      }
    }
    return first;
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
    return Integer.MAX_VALUE;
  }
}
