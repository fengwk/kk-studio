package fun.fengwk.kkstudio.platform.harness;

import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy;
import java.util.concurrent.ThreadPoolExecutor.DiscardOldestPolicy;
import java.util.concurrent.ThreadPoolExecutor.DiscardPolicy;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Platform Gateway 执行器安全校验工具。
 *
 * <p>确保供给 Gateway 异步调度的 {@link ExecutorService} 不会在调用线程内联执行任务（直接 inline executor 或 {@link
 * CallerRunsPolicy}，可能导致门控死锁），也不会静默丢弃任务（{@link DiscardPolicy} / {@link
 * DiscardOldestPolicy}，导致拒绝无法感知）。
 */
public final class ExecutorSafety {

  private ExecutorSafety() {}

  /**
   * 校验指定的 executor 是安全且非内联的异步执行器。
   *
   * @param executor 待校验的执行器服务
   * @param component 错误消息中的组件标识（如 "model gateway" 或 "tool gateway"）
   * @throws NullPointerException 若 executor 为 null
   * @throws IllegalStateException 若 executor 配置了不安全的拒绝策略或为内联执行器
   */
  public static void requireSafeAsyncExecutor(ExecutorService executor, String component) {
    Objects.requireNonNull(executor, "executor");
    rejectUnsafeExecutorPolicies(executor, component);
    rejectInlineExecutor(executor, component);
  }

  private static void rejectUnsafeExecutorPolicies(ExecutorService executor, String component) {
    if (executor instanceof ThreadPoolExecutor threadPool) {
      RejectedExecutionHandler handler = threadPool.getRejectedExecutionHandler();
      if (handler instanceof CallerRunsPolicy) {
        throw new IllegalStateException(
            component + " requires a non-inline executor; CallerRunsPolicy is not supported");
      }
      if (handler instanceof DiscardPolicy || handler instanceof DiscardOldestPolicy) {
        throw new IllegalStateException(
            component
                + " requires a rejecting executor; silent discard policies are not supported");
      }
    }
  }

  private static void rejectInlineExecutor(ExecutorService executor, String component) {
    Thread caller = Thread.currentThread();
    AtomicReference<Thread> runner = new AtomicReference<>();
    try {
      executor.execute(() -> runner.set(Thread.currentThread()));
    } catch (RuntimeException ignored) {
      // 拒绝型或已损坏的 executor 不可能内联运行任务；失败由 start() 呈现。
      return;
    }
    if (runner.get() == caller) {
      throw new IllegalStateException(component + " requires a non-inline executor");
    }
  }
}
