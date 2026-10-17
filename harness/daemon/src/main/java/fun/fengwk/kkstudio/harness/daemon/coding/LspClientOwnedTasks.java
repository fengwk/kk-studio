package fun.fengwk.kkstudio.harness.daemon.coding;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * 一个 LSP 客户端自有的 IO 任务登记：stderr 排空与 lsp4j 监听都经它提交，「已经静止」只能由运行体自己结束来证明。
 *
 * <p>它把外部执行器（由 {@code DaemonRuntime} 拥有）包装成本实例私有的提交入口：不创建线程与执行器，也绝不关闭外部执行器。 每个任务持有真实的
 * QUEUED/RUNNING/SETTLED 状态。取消请求与 {@code Future} 的终态都不构成静止证据：{@code FutureTask.cancel(false)}
 * 在运行体已经开始之后依然可以成功，所以只有已经开始的运行体在自己结束时结算才作数。
 *
 * <p>{@link #closeAndAwait(long)} 把还没开始的任务结算成「永不执行」，再以同一个有界预算等待真正在跑的运行体结束；
 * 外部执行器之后是否再取出这些任务都不改变结论，因为被结算的任务在运行体入口就返回。
 */
final class LspClientOwnedTasks extends AbstractExecutorService {

  private static final int QUEUED = 0;
  private static final int RUNNING = 1;
  private static final int SETTLED = 2;

  private final ExecutorService delegate;
  private final Object lock = new Object();
  private final List<Task> tasks = new ArrayList<>();
  private boolean closing;

  /** 运行体观察到的失败：属于这次登记的事实，任务结束之后依然必须出现在结论里。 */
  private Throwable taskFailure;

  LspClientOwnedTasks(ExecutorService delegate) {
    this.delegate = Objects.requireNonNull(delegate, "delegate");
  }

  @Override
  public void execute(Runnable command) {
    Task task = new Task(command);
    synchronized (lock) {
      if (closing) {
        throw new RejectedExecutionException("the LSP client accepts no more owned IO tasks");
      }
      tasks.add(task);
    }
    try {
      delegate.execute(task);
    } catch (RuntimeException | Error rejection) {
      // 外部执行器拒绝：任务从未运行，直接结算，拒绝原因原样交给调用方。
      task.preventExecution();
      throw rejection;
    }
  }

  /**
   * 阻止还没开始的任务执行，然后有界等待真正在跑的任务结束。
   *
   * @return {@code null} 表示静止已经被证明；否则是「没能证明静止」的结论或运行体观察到的 {@link Error}
   */
  Throwable closeAndAwait(long budgetMillis) {
    List<Task> outstanding;
    synchronized (lock) {
      closing = true;
      outstanding = new ArrayList<>(tasks);
    }
    for (Task task : outstanding) {
      task.preventExecution();
    }
    long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(budgetMillis, 0));
    Throwable failure = null;
    for (Task task : outstanding) {
      failure = LspClient.mergeFailure(failure, task.awaitExit(deadline));
    }
    synchronized (lock) {
      return LspClient.mergeFailure(failure, taskFailure);
    }
  }

  /** 只停止接受新任务；外部执行器的生命周期由它的所有者决定。 */
  @Override
  public void shutdown() {
    synchronized (lock) {
      closing = true;
    }
  }

  /** 停止接受新任务，并交回从未开始执行的任务；它们因此永远不会执行。 */
  @Override
  public List<Runnable> shutdownNow() {
    shutdown();
    List<Runnable> neverStarted = new ArrayList<>();
    for (Task task : snapshot()) {
      if (task.preventExecution()) {
        neverStarted.add(task);
      }
    }
    return neverStarted;
  }

  @Override
  public boolean isShutdown() {
    synchronized (lock) {
      return closing;
    }
  }

  @Override
  public boolean isTerminated() {
    synchronized (lock) {
      return closing && tasks.isEmpty();
    }
  }

  /** 只等本实例自己的任务，不触碰外部执行器，也绝不代替它声称已经终止。 */
  @Override
  public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
    long deadline = System.nanoTime() + unit.toNanos(timeout);
    for (Task task : snapshot()) {
      if (!task.awaitRunnerExit(deadline)) {
        return false;
      }
    }
    return isTerminated();
  }

  private List<Task> snapshot() {
    synchronized (lock) {
      return new ArrayList<>(tasks);
    }
  }

  /** 一次自有 IO 任务：状态与「运行体是否真的结束」是唯一事实。 */
  private final class Task implements Runnable {

    private final Runnable command;
    private final CountDownLatch runnerExited = new CountDownLatch(1);

    private int state = QUEUED;

    private Task(Runnable command) {
      this.command = command;
    }

    @Override
    public void run() {
      synchronized (lock) {
        if (state != QUEUED) {
          // 已经结算为「永不执行」：运行体绝不在这里开始。
          return;
        }
        state = RUNNING;
      }
      Throwable failure = null;
      try {
        command.run();
      } catch (Error fatal) {
        // 直接提交的 Runnable 抛出的 Error 在这里可见，不能被丢掉。
        failure = fatal;
        throw fatal;
      } finally {
        settle(failure == null ? observableError(command) : failure);
      }
    }

    /**
     * 结算还没开始的任务：它永不执行，因此不再需要被等待。
     *
     * @return {@code true} 表示这次调用完成了结算
     */
    private boolean preventExecution() {
      synchronized (lock) {
        if (state != QUEUED) {
          // 运行体已经接手：是否静止只能由它自己的结束来回答。
          return false;
        }
        state = SETTLED;
        tasks.remove(this);
      }
      runnerExited.countDown();
      return true;
    }

    /** 运行体结束：只有走到这里才代表这次任务真的不再运行；观察到的失败由登记保留。 */
    private void settle(Throwable failure) {
      synchronized (lock) {
        state = SETTLED;
        if (failure != null) {
          taskFailure = LspClient.mergeFailure(taskFailure, failure);
        }
        tasks.remove(this);
      }
      runnerExited.countDown();
    }

    /** 有界等待运行体结束；{@code null} 表示这次任务已经静止（含被结算为永不执行）。 */
    private Throwable awaitExit(long deadline) {
      try {
        if (!runnerExited.await(Math.max(deadline - System.nanoTime(), 0), TimeUnit.NANOSECONDS)) {
          // 超时之后再读一次完成闩锁：等待期间运行体刚好结束就不算未证明静止。
          return runnerExited.getCount() == 0
              ? null
              : new ToolServiceFailureException("LSP client I/O task did not exit in time");
        }
      } catch (InterruptedException error) {
        Thread.currentThread().interrupt();
        // 中断不能改写已经发布的完成事实：只有运行体确实还没结束才报告未证明静止。
        if (runnerExited.getCount() != 0) {
          return new ToolServiceFailureException("LSP client I/O task exit was interrupted");
        }
      }
      return null;
    }

    private boolean awaitRunnerExit(long deadline) throws InterruptedException {
      return runnerExited.await(Math.max(deadline - System.nanoTime(), 0), TimeUnit.NANOSECONDS);
    }
  }

  /**
   * 已经交给会捕获异常的执行器时（例如 {@code submit} 的 {@code FutureTask}），从结果里取回可观察到的 {@link Error}。
   *
   * <p>结果被取消时运行体可能确实已经结束，但结论不可确认：既不构成失败，也绝不构成「已经静止」的证据。
   */
  private static Throwable observableError(Runnable command) {
    if (!(command instanceof Future<?> result)) {
      return null;
    }
    try {
      result.get();
      return null;
    } catch (ExecutionException error) {
      return error.getCause() instanceof Error fatal ? fatal : null;
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      return null;
    } catch (CancellationException ignored) {
      return null;
    }
  }
}
