package fun.fengwk.kkstudio.harness.daemon.coding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * {@link LspClientOwnedTasks} 的静止证据：只有运行体自己结束才算静止，取消请求与 {@code Future} 的终态都不算。
 *
 * <p>用例用测试自有的运行体与真实外部执行器制造两种窗口：已经开始的运行体被外部取消、从未开始的任务停在外部队列里。 断言基于真实线程与真实闩锁，不依赖 sleep 猜测同步点。
 */
class LspClientOwnedTasksTest {

  private static final Duration WAIT = Duration.ofSeconds(20);

  private final ExecutorService callers = Executors.newCachedThreadPool();
  private final List<ExecutorService> executors = new ArrayList<>();

  @AfterEach
  void tearDown() throws InterruptedException {
    executors.forEach(ExecutorService::shutdownNow);
    callers.shutdownNow();
    // 测试自有执行器必须真正静止：这里逐个有界等待，而不是假设它们已经终止。
    for (ExecutorService executor : executors) {
      assertTrue(executor.awaitTermination(WAIT.toSeconds(), TimeUnit.SECONDS), "测试自有执行器必须真正终止");
    }
    assertTrue(callers.awaitTermination(WAIT.toSeconds(), TimeUnit.SECONDS), "测试自有执行器必须真正终止");
  }

  /**
   * 意图：运行体已经开始时，{@code Future.cancel(false)} 依然可以成功，因此它绝不能作为静止证据——关闭必须等运行体自己结束。
   *
   * <p>同步点是关闭线程真正阻塞在生产 {@code Task.awaitExit} 上，而不是靠 sleep 猜测它已经开始等待。
   */
  @Test
  void externallyCancelledRunningTaskIsNotTreatedAsSettled() throws Exception {
    LspClientOwnedTasks owned = new LspClientOwnedTasks(newDispatch());
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch gate = new CountDownLatch(1);
    Future<?> handle =
        owned.submit(
            () -> {
              entered.countDown();
              awaitLatch(gate);
            });
    assertTrue(entered.await(WAIT.toSeconds(), TimeUnit.SECONDS), "运行体必须真的开始");
    assertTrue(handle.cancel(false), "运行中的 FutureTask 会接受取消，所以它不能作为静止证据");

    Future<Throwable> closed = callers.submit(() -> owned.closeAndAwait(WAIT.toMillis()));
    awaitBlockedIn(LspClientOwnedTasks.class, "awaitExit", WAIT);
    assertFalse(closed.isDone(), "运行体还没有结束时关闭不得声称静止");

    gate.countDown();
    assertNull(closed.get(WAIT.toSeconds(), TimeUnit.SECONDS), "运行体结束之后才允许成功");
  }

  /**
   * 意图：关闭已经 snapshot 到某次任务、运行体随后真实结束（完成闩锁已经发布）之后，即使调用者预先带中断， {@code awaitExit}
   * 也必须返回「静止」而不是把自己的处境改写成未证明静止；中断标记必须保留。
   *
   * <p>竞态窗口是确定构造的：先从外部队列取出真实 {@code Task}（等价于关闭时的 snapshot），手动 {@code run()} 让它结算，再对同一个 snapshot
   * 对象反射调用私有的 {@code awaitExit(long)}。反射只提供 {@code Task} 的控制流事实，不是 OS 物理证明。
   */
  @Test
  void awaitExitAfterPublishedRunnerExitIgnoresCallerInterrupt() throws Exception {
    PausedDispatch dispatch = pausedDispatch();
    LspClientOwnedTasks owned = new LspClientOwnedTasks(dispatch);
    owned.execute(() -> {});
    Runnable task = dispatch.queued.get(0);

    // 运行体真实结束：完成闩锁已经发布，随后调用者才带着中断进入等待。
    task.run();

    Thread.currentThread().interrupt();
    try {
      assertNull(
          invokeAwaitExit(task, System.nanoTime() + WAIT.toNanos()),
          "已经发布的完成事实不得因为调用者中断而被改写成未证明静止");
      assertTrue(Thread.currentThread().isInterrupted(), "读取已发布完成不得清除中断标记");
    } finally {
      Thread.interrupted();
    }
  }

  /** 意图：从未开始的任务在关闭时被结算为「永不执行」；外部执行器之后取出它也不得执行运行体，且必须仍然可用。 */
  @Test
  void queuedTaskNeverExecutesAfterCloseAndBackingExecutorStaysActive() throws Exception {
    PausedDispatch dispatch = pausedDispatch();
    LspClientOwnedTasks owned = new LspClientOwnedTasks(dispatch);
    AtomicBoolean ran = new AtomicBoolean();
    owned.submit(() -> ran.set(true));

    assertNull(owned.closeAndAwait(WAIT.toMillis()), "从未开始的任务必须被结算，而不是被等待");

    CountDownLatch probe = new CountDownLatch(1);
    dispatch.execute(probe::countDown);
    dispatch.resume();
    assertTrue(probe.await(WAIT.toSeconds(), TimeUnit.SECONDS), "外部执行器必须仍然可用");
    assertFalse(ran.get(), "被结算的任务不得在关闭之后执行运行体");
    assertFalse(dispatch.isShutdown(), "自有登记绝不关闭外部执行器");
  }

  /** 意图：运行体以 {@link Error} 结束时结论必须保留原身份，不能被吞掉或降级成成功。 */
  @Test
  void runnerErrorIsRetainedInTheConclusion() throws Exception {
    LspClientOwnedTasks owned = new LspClientOwnedTasks(newDispatch());
    AssertionError fatal = new AssertionError("synthetic owned task failure");
    CountDownLatch entered = new CountDownLatch(1);
    owned.submit(
        () -> {
          entered.countDown();
          throw fatal;
        });
    assertTrue(entered.await(WAIT.toSeconds(), TimeUnit.SECONDS), "运行体必须真的开始");

    assertSame(fatal, owned.closeAndAwait(WAIT.toMillis()), "运行体的 Error 必须原身份保留");
  }

  /** 意图：运行体在预算内没有结束时必须报告「未证明静止」，且运行体真正结束之后结论不再被改写。 */
  @Test
  void budgetTimeoutReportsUnprovenRunnerExit() throws Exception {
    LspClientOwnedTasks owned = new LspClientOwnedTasks(newDispatch());
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch gate = new CountDownLatch(1);
    owned.submit(
        () -> {
          entered.countDown();
          awaitLatch(gate);
        });
    assertTrue(entered.await(WAIT.toSeconds(), TimeUnit.SECONDS));
    try {
      Throwable failure = owned.closeAndAwait(100);
      assertTrue(
          failure != null && failure.getMessage().contains("did not exit in time"),
          "运行体没有结束时必须报告未证明静止，实际 " + failure);
    } finally {
      gate.countDown();
    }
    awaitTrue(owned::isTerminated, WAIT);
    assertNull(owned.closeAndAwait(WAIT.toMillis()), "运行体结束之后不再有未证明静止的结论");
  }

  /** 意图：登记关闭之后不再接受新任务，拒绝是显式的而不是静默丢弃。 */
  @Test
  void executeAfterShutdownIsRejectedAndTheTaskNeverRuns() {
    LspClientOwnedTasks owned = new LspClientOwnedTasks(newDispatch());
    owned.shutdown();

    assertTrue(owned.isShutdown(), "关闭之后必须如实报告不再接受任务");
    assertThrows(
        RejectedExecutionException.class, () -> owned.execute(() -> {}), "关闭之后提交自有任务必须被拒绝");
  }

  /** 意图：{@code shutdownNow} 只交回从未开始的任务，正在跑的运行体照常结束；两者都不影响外部执行器。 */
  @Test
  void shutdownNowReturnsOnlyNeverStartedTasksAndTheRunningOneStillFinishes() throws Exception {
    PausedDispatch dispatch = pausedDispatch();
    LspClientOwnedTasks owned = new LspClientOwnedTasks(dispatch);
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch gate = new CountDownLatch(1);
    AtomicBoolean queuedRan = new AtomicBoolean();
    owned.submit(
        () -> {
          entered.countDown();
          awaitLatch(gate);
        });
    dispatch.resume();
    assertTrue(entered.await(WAIT.toSeconds(), TimeUnit.SECONDS), "第一个运行体必须真的开始");
    // 单工作线程执行器：第二个提交停在队列里，从未开始。
    owned.submit(() -> queuedRan.set(true));

    List<Runnable> neverStarted = owned.shutdownNow();

    assertEquals(1, neverStarted.size(), "只有从未开始的任务会被交回");
    assertFalse(owned.isTerminated(), "运行体还在跑时不得声称已经终止");
    gate.countDown();
    assertTrue(owned.awaitTermination(WAIT.toSeconds(), TimeUnit.SECONDS), "运行体结束后登记必须报告终止");
    assertTrue(owned.isTerminated(), "运行体结束后登记必须报告终止");
    assertFalse(queuedRan.get(), "被交回的任务绝不执行运行体");
    assertFalse(dispatch.isShutdown(), "自有登记绝不关闭外部执行器");
  }

  /** 意图：{@code awaitTermination} 只等自己的运行体，并在运行体仍然阻塞时如实返回「还没终止」。 */
  @Test
  void awaitTerminationReportsTheRealRunnerExit() throws Exception {
    LspClientOwnedTasks owned = new LspClientOwnedTasks(newDispatch());
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch gate = new CountDownLatch(1);
    owned.submit(
        () -> {
          entered.countDown();
          awaitLatch(gate);
        });
    assertTrue(entered.await(WAIT.toSeconds(), TimeUnit.SECONDS), "运行体必须真的开始");
    owned.shutdown();

    assertFalse(owned.awaitTermination(100, TimeUnit.MILLISECONDS), "运行体没有结束时必须如实返回未终止");

    gate.countDown();
    assertTrue(owned.awaitTermination(WAIT.toSeconds(), TimeUnit.SECONDS), "运行体结束后必须终止");
  }

  /** 意图：外部执行器拒绝时任务从未运行，拒绝原因必须原样交给调用方，不能让任务留在登记里等待。 */
  @Test
  void rejectedExecutionIsPropagatedAndTheTaskIsSettled() {
    ExecutorService rejecting = Executors.newSingleThreadExecutor();
    executors.add(rejecting);
    rejecting.shutdown();
    LspClientOwnedTasks owned = new LspClientOwnedTasks(rejecting);

    assertThrows(
        RejectedExecutionException.class, () -> owned.submit(() -> {}), "外部执行器的拒绝必须原样交给调用方");

    assertNull(owned.closeAndAwait(WAIT.toMillis()), "从未运行的任务不得留下未证明静止的结论");
    assertTrue(owned.isTerminated(), "从未运行的任务必须已经结算");
  }

  /** 意图：直接交给执行器的纯 {@code Runnable} 抛出 {@link Error} 时同样不能被吞掉，必须成为结论。 */
  @Test
  void directlyExecutedRunnableErrorIsRetained() throws Exception {
    LspClientOwnedTasks owned = new LspClientOwnedTasks(silentDispatch());
    AssertionError fatal = new AssertionError("synthetic raw runnable failure");
    CountDownLatch entered = new CountDownLatch(1);
    owned.execute(
        () -> {
          entered.countDown();
          throw fatal;
        });
    assertTrue(entered.await(WAIT.toSeconds(), TimeUnit.SECONDS), "运行体必须真的开始");

    assertSame(fatal, owned.closeAndAwait(WAIT.toMillis()), "运行体的 Error 必须原身份保留");
  }

  /** 意图：{@code Future} 里可观察到的普通失败不构成「未证明静止」，也不是 Error，因此不被提升为结论。 */
  @Test
  void submittedRuntimeExceptionIsNotReportedAsUnprovenStop() throws Exception {
    LspClientOwnedTasks owned = new LspClientOwnedTasks(newDispatch());
    CountDownLatch entered = new CountDownLatch(1);
    owned.submit(
        () -> {
          entered.countDown();
          throw new IllegalStateException("synthetic task failure");
        });
    assertTrue(entered.await(WAIT.toSeconds(), TimeUnit.SECONDS), "运行体必须真的开始");

    assertNull(owned.closeAndAwait(WAIT.toMillis()), "普通失败说明任务已经结束，不得被当成未证明静止");
  }

  private PausedDispatch pausedDispatch() {
    PausedDispatch dispatch = new PausedDispatch();
    executors.add(dispatch);
    return dispatch;
  }

  /** 外部执行器线程不打印未捕获的 Error：运行体抛出 Error 的可见性由登记负责。 */
  private ExecutorService silentDispatch() {
    ExecutorService dispatch =
        Executors.newCachedThreadPool(
            runnable -> {
              Thread thread = new Thread(runnable);
              thread.setUncaughtExceptionHandler((self, error) -> {});
              return thread;
            });
    executors.add(dispatch);
    return dispatch;
  }

  private ExecutorService newDispatch() {
    ExecutorService dispatch = Executors.newCachedThreadPool();
    executors.add(dispatch);
    return dispatch;
  }

  private static void awaitLatch(CountDownLatch gate) {
    try {
      gate.await();
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new AssertionError("interrupted while waiting for the test gate", error);
    }
  }

  /** 反射调用私有的 {@code Task.awaitExit(long)}：只用于对一个已 snapshot 的任务构造控制流事实。 */
  private static Object invokeAwaitExit(Runnable task, long deadline) throws Exception {
    Method method = task.getClass().getDeclaredMethod("awaitExit", long.class);
    method.setAccessible(true);
    return method.invoke(task, deadline);
  }

  private static void awaitBlockedIn(Class<?> owner, String method, Duration timeout)
      throws InterruptedException {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (System.nanoTime() < deadline) {
      for (Thread thread : Thread.getAllStackTraces().keySet()) {
        for (StackTraceElement frame : thread.getStackTrace()) {
          if (isInnerOf(frame.getClassName(), owner) && frame.getMethodName().equals(method)) {
            return;
          }
        }
      }
      Thread.sleep(10);
    }
    throw new AssertionError("no thread ever reached " + owner.getSimpleName() + "." + method);
  }

  private static boolean isInnerOf(String className, Class<?> owner) {
    return className.equals(owner.getName()) || className.startsWith(owner.getName() + "$");
  }

  private static void awaitTrue(Supplier<Boolean> condition, Duration timeout)
      throws InterruptedException {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (System.nanoTime() < deadline) {
      if (Boolean.TRUE.equals(condition.get())) {
        return;
      }
      Thread.sleep(10);
    }
    throw new AssertionError("condition was not met within " + timeout);
  }

  /** 单工作线程外部执行器：暂停时排队，恢复时按提交顺序取出执行，因此「后续探测任务跑完」即证明先前任务已被处理。 */
  private static final class PausedDispatch extends ThreadPoolExecutor {

    private final List<Runnable> queued = new ArrayList<>();
    private boolean paused = true;

    private PausedDispatch() {
      super(1, 1, 60L, TimeUnit.SECONDS, new LinkedBlockingQueue<>());
    }

    @Override
    public synchronized void execute(Runnable command) {
      if (paused) {
        queued.add(command);
        return;
      }
      super.execute(command);
    }

    private synchronized void resume() {
      paused = false;
      for (Runnable task : queued) {
        super.execute(task);
      }
      queued.clear();
    }
  }
}
