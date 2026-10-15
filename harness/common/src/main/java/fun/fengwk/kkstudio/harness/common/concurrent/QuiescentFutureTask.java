package fun.fengwk.kkstudio.harness.common.concurrent;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 以真实工作线程退出为事实的 {@link FutureTask}：静止 stage 只在真正执行本体的那次 {@code run} 的 finally 完成。
 *
 * <p>{@link #done()} 与 {@code Future#cancel(boolean)} 的完成都只表示「任务状态已定」，不代表执行体已经停止访问本地资源，因此都不能作为
 * 资源静止事实。本任务用一个 {@code started} 标志原子地裁决「谁拥有执行事实」：
 *
 * <ul>
 *   <li>取消成功且抢到标志：任务不会再进入本体，直接完成静止 stage；
 *   <li>已经进入本体：取消只是请求中断，静止 stage 只能由该次 {@code run} 的 finally 完成，绝不提前宣告退出；
 *   <li>重复 {@code run}（例如被错误地提交多次）：直接返回，既不执行本体也不完成 stage——第一次的执行线程仍在使用资源， 第二次调用没有任何退出事实可以声明。
 * </ul>
 *
 * <p>本类 {@code final}：静止事实依赖 {@code run}/{@code cancel} 的严格配对，不允许子类改变该协议。
 *
 * <p>必须用 {@code executor.execute(task)} 提交；{@code submit} 会把它包装成另一个 Future，使静止事实与实际执行线程脱离。 {@link
 * #quiescence()} 只暴露只读视图，调用方无法提前完成内部事实。
 */
public final class QuiescentFutureTask<T> extends FutureTask<T> {

  private final AtomicBoolean started = new AtomicBoolean();
  private final CompletableFuture<Void> quiescence = new CompletableFuture<>();
  private final CompletionStage<Void> quiescenceView = quiescence.minimalCompletionStage();

  public QuiescentFutureTask(Runnable runnable) {
    super(runnable, null);
  }

  public QuiescentFutureTask(Callable<T> callable) {
    super(callable);
  }

  @Override
  public void run() {
    if (!started.compareAndSet(false, true)) {
      // 已经有人拥有执行事实：本调用既不执行本体，也不代表工作线程退出，绝不能完成 stage。
      return;
    }
    try {
      super.run();
    } finally {
      quiescence.complete(null);
    }
  }

  @Override
  public boolean cancel(boolean mayInterruptIfRunning) {
    boolean cancelled = super.cancel(mayInterruptIfRunning);
    if (cancelled && started.compareAndSet(false, true)) {
      // 取消成功且抢到执行事实：任务不会再进入本体，因此可以立即确认本地资源静止。
      quiescence.complete(null);
    }
    return cancelled;
  }

  /** 该调用不再使用本地资源的完成事实；首次调用者已胜出时只有它的 {@code run} 退出才会完成。 */
  public CompletionStage<Void> quiescence() {
    return quiescenceView;
  }
}
