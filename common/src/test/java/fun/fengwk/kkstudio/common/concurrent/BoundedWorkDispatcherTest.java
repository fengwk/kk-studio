package fun.fengwk.kkstudio.common.concurrent;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Delayed;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * {@link BoundedWorkDispatcher} 机制测试：合并 wake 的单 drain、容量、worker 拒绝与 Error 分型、drain 执行器拒绝/Error、
 * 回调失败边界、stop 语义与 fail-fast executor 契约。
 *
 * <p>测试用受控 executor 精确推进 drain / worker 任务，用固定的 claim 队列表达 due，不依赖真实调度时序。
 */
class BoundedWorkDispatcherTest {

  private static final Duration POLL = Duration.ofMillis(20);

  /** 非法参数 fail-fast：名字、容量、poll 精度与所有必填注入项。 */
  @Test
  void constructorRejectsInvalidArguments() {
    ScheduledExecutorService poll = new FakeScheduler();
    Executor inline = Runnable::run;
    Supplier<String> claim = () -> null;
    Consumer<String> execute = value -> {};
    Consumer<String> returnClaim = value -> {};

    assertThrows(
        IllegalArgumentException.class,
        () ->
            new BoundedWorkDispatcher<>(
                null, 1, POLL, inline, inline, poll, claim, execute, returnClaim));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new BoundedWorkDispatcher<>(
                "  ", 1, POLL, inline, inline, poll, claim, execute, returnClaim));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new BoundedWorkDispatcher<>(
                "test", 0, POLL, inline, inline, poll, claim, execute, returnClaim));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new BoundedWorkDispatcher<>(
                "test", -1, POLL, inline, inline, poll, claim, execute, returnClaim));
    assertThrows(
        NullPointerException.class,
        () ->
            new BoundedWorkDispatcher<>(
                "test", 1, null, inline, inline, poll, claim, execute, returnClaim));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new BoundedWorkDispatcher<>(
                "test", 1, Duration.ZERO, inline, inline, poll, claim, execute, returnClaim));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new BoundedWorkDispatcher<>(
                "test",
                1,
                Duration.ofNanos(500_000),
                inline,
                inline,
                poll,
                claim,
                execute,
                returnClaim));
    assertThrows(
        NullPointerException.class,
        () ->
            new BoundedWorkDispatcher<>(
                "test", 1, POLL, null, inline, poll, claim, execute, returnClaim));
    assertThrows(
        NullPointerException.class,
        () ->
            new BoundedWorkDispatcher<>(
                "test", 1, POLL, inline, null, poll, claim, execute, returnClaim));
    assertThrows(
        NullPointerException.class,
        () ->
            new BoundedWorkDispatcher<>(
                "test", 1, POLL, inline, inline, null, claim, execute, returnClaim));
    assertThrows(
        NullPointerException.class,
        () ->
            new BoundedWorkDispatcher<>(
                "test", 1, POLL, inline, inline, poll, null, execute, returnClaim));
    assertThrows(
        NullPointerException.class,
        () ->
            new BoundedWorkDispatcher<>(
                "test", 1, POLL, inline, inline, poll, claim, null, returnClaim));
    assertThrows(
        NullPointerException.class,
        () ->
            new BoundedWorkDispatcher<>(
                "test", 1, POLL, inline, inline, poll, claim, execute, null));
  }

  /** 拒绝使用 CallerRuns / Discard / DiscardOldest 等会静默丢弃或替换已接受 task 的池。 */
  @Test
  void constructorRejectsSilentExecutorRejectionPolicies() {
    ScheduledExecutorService poll = new FakeScheduler();
    Supplier<String> claim = () -> null;
    Consumer<String> execute = value -> {};
    Consumer<String> returnClaim = value -> {};
    List<ThreadPoolExecutor> pools = new ArrayList<>();
    pools.add(pool(new ThreadPoolExecutor.CallerRunsPolicy()));
    pools.add(pool(new ThreadPoolExecutor.DiscardPolicy()));
    pools.add(pool(new ThreadPoolExecutor.DiscardOldestPolicy()));
    try {
      for (ThreadPoolExecutor silent : pools) {
        RejectedExecutionHandler handler = silent.getRejectedExecutionHandler();
        assertThrows(
            IllegalArgumentException.class,
            () ->
                new BoundedWorkDispatcher<>(
                    "test", 1, POLL, silent, Runnable::run, poll, claim, execute, returnClaim),
            "drain executor must reject " + handler.getClass());
        assertThrows(
            IllegalArgumentException.class,
            () ->
                new BoundedWorkDispatcher<>(
                    "test", 1, POLL, Runnable::run, silent, poll, claim, execute, returnClaim),
            "worker executor must reject " + handler.getClass());
      }
    } finally {
      pools.forEach(ExecutorService::shutdownNow);
    }
  }

  /** start 幂等、注册一次 fixed-delay poll、首次 wake 即 drain；stop 取消 poll、幂等且 stop 后 start 明确拒绝。 */
  @Test
  void startIsIdempotentTriggersFirstDrainAndStopIsTerminal() {
    FakeScheduler scheduler = new FakeScheduler();
    ManualExecutor drain = new ManualExecutor();
    ManualExecutor worker = new ManualExecutor();
    Deque<String> claims = new ArrayDeque<>(List.of("a"));
    List<String> executed = new ArrayList<>();
    BoundedWorkDispatcher<String> dispatcher =
        new BoundedWorkDispatcher<>(
            "test", 1, POLL, drain, worker, scheduler, claims::poll, executed::add, value -> {});

    dispatcher.start();
    dispatcher.start();
    dispatcher.start();

    assertTrue(dispatcher.isRunning());
    assertEquals(1, scheduler.registrations());
    assertEquals(POLL.toMillis(), scheduler.initialDelay);
    assertEquals(POLL.toMillis(), scheduler.delay);
    assertEquals(TimeUnit.MILLISECONDS, scheduler.unit);

    // 首次 wake 已提交一次 drain；执行 drain 后 claim 被 bounded handoff（容量满，drain 停止）。
    assertEquals(1, drain.size());
    drain.runNext();
    assertEquals(1, worker.size());
    assertTrue(executed.isEmpty());
    assertEquals(1, dispatcher.inFlightCount());

    // worker 收尾释放容量并再 wake。
    worker.runNext();
    assertEquals(List.of("a"), executed);
    assertEquals(0, dispatcher.inFlightCount());

    dispatcher.stop();
    dispatcher.stop();
    assertEquals(1, scheduler.future.cancelCalls());
    assertFalse(dispatcher.isRunning());
    assertThrows(IllegalStateException.class, dispatcher::start);
  }

  /** wake 在 start 前和 stop 后都是 no-op；close 幂等等于 stop。 */
  @Test
  void wakeBeforeStartAndAfterStopAreNoOps() {
    FakeScheduler scheduler = new FakeScheduler();
    ManualExecutor drain = new ManualExecutor();
    ManualExecutor worker = new ManualExecutor();
    AtomicInteger claimCalls = new AtomicInteger();
    BoundedWorkDispatcher<String> dispatcher =
        new BoundedWorkDispatcher<>(
            "test",
            1,
            POLL,
            drain,
            worker,
            scheduler,
            () -> {
              claimCalls.incrementAndGet();
              return null;
            },
            value -> {},
            value -> {});

    dispatcher.wake();
    assertEquals(0, drain.size());
    assertEquals(0, claimCalls.get());

    dispatcher.start();
    drain.runNext();
    assertEquals(1, claimCalls.get());

    dispatcher.close();
    assertFalse(dispatcher.isRunning());
    dispatcher.wake();
    assertEquals(1, claimCalls.get());
    assertEquals(0, drain.size());
  }

  /** poll 注册失败时 start 传播异常且不进入运行态；恢复后可正常 start。 */
  @Test
  void pollScheduleFailureKeepsDispatcherNotRunning() {
    FakeScheduler scheduler = new FakeScheduler();
    ManualExecutor drain = new ManualExecutor();
    ManualExecutor worker = new ManualExecutor();
    BoundedWorkDispatcher<String> dispatcher =
        new BoundedWorkDispatcher<>(
            "test", 1, POLL, drain, worker, scheduler, () -> null, value -> {}, value -> {});

    scheduler.failNext = true;
    assertThrows(RejectedExecutionException.class, dispatcher::start);
    assertFalse(dispatcher.isRunning());

    scheduler.failNext = false;
    dispatcher.start();
    assertTrue(dispatcher.isRunning());
    assertEquals(1, scheduler.registrations());
  }

  /**
   * start 的首次 wake 在释放 lifecycleLock 之后执行：inline drain 阻塞在 claim 时另一线程的 stop 仍可立即返回；claim 随后被归还而非
   * handoff。
   */
  @Test
  void stopReturnsWhileAnInlineClaimIsStillBlocked() throws Exception {
    ManualExecutor worker = new ManualExecutor();
    CountDownLatch claimEntered = new CountDownLatch(1);
    CountDownLatch releaseClaim = new CountDownLatch(1);
    List<String> returned = new CopyOnWriteArrayList<>();
    BoundedWorkDispatcher<String> dispatcher =
        new BoundedWorkDispatcher<>(
            "test",
            1,
            POLL,
            Runnable::run,
            worker,
            new FakeScheduler(),
            () -> {
              claimEntered.countDown();
              await(releaseClaim);
              return "a";
            },
            value -> {},
            returned::add);

    ExecutorService starter = Executors.newSingleThreadExecutor();
    ExecutorService stopper = Executors.newSingleThreadExecutor();
    try {
      Future<?> startFuture = starter.submit(dispatcher::start);
      assertTrue(claimEntered.await(5, TimeUnit.SECONDS));

      // claim 仍阻塞时 stop 必须能在另一线程立即返回，而不是等待 lifecycleLock。
      CountDownLatch stopReturned = new CountDownLatch(1);
      stopper.submit(
          () -> {
            dispatcher.stop();
            stopReturned.countDown();
          });
      assertTrue(stopReturned.await(5, TimeUnit.SECONDS));

      releaseClaim.countDown();
      startFuture.get(5, TimeUnit.SECONDS);
      assertEquals(List.of("a"), returned);
      assertEquals(0, worker.size());
    } finally {
      releaseClaim.countDown();
      starter.shutdownNow();
      stopper.shutdownNow();
    }
  }

  /** drain 执行器同步拒绝：只放弃本次 drain，容量门恢复，下一次 wake 可重试。 */
  @Test
  void drainExecutorRejectionRecoversOnNextWake() {
    ManualExecutor drain = new ManualExecutor();
    ManualExecutor worker = new ManualExecutor();
    Deque<String> claims = new ArrayDeque<>(List.of("a"));
    List<String> executed = new ArrayList<>();
    BoundedWorkDispatcher<String> dispatcher =
        new BoundedWorkDispatcher<>(
            "test",
            1,
            POLL,
            drain,
            worker,
            new FakeScheduler(),
            claims::poll,
            executed::add,
            value -> {});

    drain.reject = true;
    assertDoesNotThrow(dispatcher::start);
    assertTrue(executed.isEmpty());

    drain.reject = false;
    dispatcher.wake();
    assertEquals(1, drain.size());
    drain.runNext();
    worker.runNext();
    assertEquals(List.of("a"), executed);
  }

  /** drain 执行器在接收前抛 Error：恢复容量门并把 Error 原样抛给调用方。 */
  @Test
  void drainExecutorErrorRestoresGateBeforePropagating() {
    ManualExecutor drain = new ManualExecutor();
    ManualExecutor worker = new ManualExecutor();
    AssertionError boom = new AssertionError("drain executor down");
    drain.failure = boom;
    BoundedWorkDispatcher<String> dispatcher =
        new BoundedWorkDispatcher<>(
            "test", 1, POLL, drain, worker, new FakeScheduler(), () -> null, v -> {}, v -> {});

    assertSame(boom, assertThrows(AssertionError.class, dispatcher::start));
    assertTrue(dispatcher.isRunning());

    drain.failure = null;
    dispatcher.wake();
    assertEquals(1, drain.size());
  }

  /** worker 执行器在接收前同步拒绝：归还 claim、释放容量、结束本轮 drain，不进入 claim-reject 热循环。 */
  @Test
  void workerRejectionReturnsClaimAndEndsTheDrain() {
    ManualExecutor drain = new ManualExecutor();
    ManualExecutor worker = new ManualExecutor();
    Deque<String> claims = new ArrayDeque<>(List.of("a", "b"));
    AtomicInteger claimCalls = new AtomicInteger();
    List<String> returned = new ArrayList<>();
    List<String> executed = new ArrayList<>();
    BoundedWorkDispatcher<String> dispatcher =
        new BoundedWorkDispatcher<>(
            "test",
            1,
            POLL,
            drain,
            worker,
            new FakeScheduler(),
            () -> {
              claimCalls.incrementAndGet();
              return claims.poll();
            },
            executed::add,
            returned::add);

    worker.reject = true;
    dispatcher.start();
    drain.runNext();

    assertEquals(List.of("a"), returned);
    assertTrue(executed.isEmpty());
    assertEquals(0, dispatcher.inFlightCount());
    assertEquals(1, claimCalls.get());
  }

  /** worker 执行器在接收前抛 Error：先归还 claim 与释放容量，再把 Error 原样抛出。 */
  @Test
  void workerErrorBeforeAcceptReturnsClaimAndRethrows() {
    ManualExecutor drain = new ManualExecutor();
    ManualExecutor worker = new ManualExecutor();
    Deque<String> claims = new ArrayDeque<>(List.of("a"));
    List<String> returned = new ArrayList<>();
    List<String> executed = new ArrayList<>();
    BoundedWorkDispatcher<String> dispatcher =
        new BoundedWorkDispatcher<>(
            "test",
            1,
            POLL,
            drain,
            worker,
            new FakeScheduler(),
            claims::poll,
            executed::add,
            returned::add);

    AssertionError boom = new AssertionError("worker executor down");
    worker.failure = boom;
    dispatcher.start();
    assertSame(boom, assertThrows(AssertionError.class, drain::runNext));

    assertEquals(List.of("a"), returned);
    assertTrue(executed.isEmpty());
    assertEquals(0, dispatcher.inFlightCount());
  }

  /** worker 已同步开始后 execute 抛 RuntimeException：不得当作 reject 二次归还或二次减容量；诊断后终止本轮 drain。 */
  @Test
  void handlerRuntimeExceptionAfterAcceptIsNotTreatedAsReject() {
    ManualExecutor drain = new ManualExecutor();
    Deque<String> claims = new ArrayDeque<>(List.of("a"));
    List<String> returned = new ArrayList<>();
    BoundedWorkDispatcher<String> dispatcher =
        new BoundedWorkDispatcher<>(
            "test",
            1,
            POLL,
            drain,
            Runnable::run,
            new FakeScheduler(),
            claims::poll,
            value -> {
              throw new IllegalStateException("handler boom");
            },
            returned::add);

    dispatcher.start();
    assertDoesNotThrow(drain::runNext);
    assertTrue(returned.isEmpty());
    assertEquals(0, dispatcher.inFlightCount());
  }

  /** worker 已同步开始后 execute 抛 Error：不归还 claim，先释放容量再把 Error 原样抛出。 */
  @Test
  void handlerErrorAfterAcceptReleasesCapacityAndPropagates() {
    ManualExecutor drain = new ManualExecutor();
    Deque<String> claims = new ArrayDeque<>(List.of("a"));
    List<String> returned = new ArrayList<>();
    AssertionError boom = new AssertionError("handler boom");
    BoundedWorkDispatcher<String> dispatcher =
        new BoundedWorkDispatcher<>(
            "test",
            1,
            POLL,
            drain,
            Runnable::run,
            new FakeScheduler(),
            claims::poll,
            value -> {
              throw boom;
            },
            returned::add);

    dispatcher.start();
    assertSame(boom, assertThrows(AssertionError.class, drain::runNext));
    assertTrue(returned.isEmpty());
    assertEquals(0, dispatcher.inFlightCount());
  }

  /** 容量约束在途 worker task 数；收尾释放一个 slot 后由 wake 继续 claim 后续 due。 */
  @Test
  void capacityBoundsInFlightTasksAndCompletionWakesAgain() {
    ManualExecutor drain = new ManualExecutor();
    ManualExecutor worker = new ManualExecutor();
    Deque<String> claims = new ArrayDeque<>(List.of("a", "b", "c"));
    List<String> executed = new ArrayList<>();
    BoundedWorkDispatcher<String> dispatcher =
        new BoundedWorkDispatcher<>(
            "test",
            2,
            POLL,
            drain,
            worker,
            new FakeScheduler(),
            claims::poll,
            executed::add,
            value -> {});

    dispatcher.start();
    drain.runNext();
    assertEquals(2, dispatcher.inFlightCount());
    assertEquals(2, worker.size());
    assertEquals(1, claims.size());

    // c 只有在某个 worker 收尾释放 slot 后才会被 claim。
    worker.runNext();
    assertEquals(1, dispatcher.inFlightCount());
    drain.runNext();
    assertEquals(0, claims.size());
    assertEquals(2, dispatcher.inFlightCount());

    worker.runNext();
    worker.runNext();
    assertEquals(List.of("a", "b", "c"), executed);
    assertEquals(0, dispatcher.inFlightCount());
  }

  /** stop 赢得 claim 与 handoff 之间：已 claim 的 item 由 returnUnstartedClaim 归还，且不再有新的 claim iteration。 */
  @Test
  void stopDuringClaimReturnsTheClaimAndPreventsNewIterations() {
    ManualExecutor drain = new ManualExecutor();
    ManualExecutor worker = new ManualExecutor();
    AtomicInteger claimCalls = new AtomicInteger();
    List<String> returned = new ArrayList<>();
    List<String> executed = new ArrayList<>();
    AtomicReference<BoundedWorkDispatcher<String>> holder = new AtomicReference<>();
    BoundedWorkDispatcher<String> dispatcher =
        new BoundedWorkDispatcher<>(
            "test",
            1,
            POLL,
            drain,
            worker,
            new FakeScheduler(),
            () -> {
              claimCalls.incrementAndGet();
              holder.get().stop();
              return "a";
            },
            executed::add,
            returned::add);
    holder.set(dispatcher);

    dispatcher.start();
    drain.runNext();

    // claim 期间 stop 生效：claim 被归还，worker 未接手，drain 不再继续。
    assertEquals(List.of("a"), returned);
    assertTrue(executed.isEmpty());
    assertEquals(0, worker.size());
    assertEquals(1, claimCalls.get());
  }

  /** 并发 wake 合并进正在运行的 drain：不启动并发 drain，也不丢失任何 due。 */
  @Test
  void concurrentWakesMergeIntoTheRunningDrainAndLoseNothing() throws Exception {
    ExecutorService drain = Executors.newSingleThreadExecutor();
    ManualExecutor worker = new ManualExecutor();
    CountDownLatch enteredClaim = new CountDownLatch(1);
    CountDownLatch releaseClaim = new CountDownLatch(1);
    AtomicInteger claimCalls = new AtomicInteger();
    List<String> executed = new CopyOnWriteArrayList<>();
    Supplier<String> claimNext =
        () -> {
          int index = claimCalls.getAndIncrement();
          if (index == 0) {
            enteredClaim.countDown();
            await(releaseClaim);
            return "a";
          }
          if (index == 1) {
            return "b";
          }
          return null;
        };
    BoundedWorkDispatcher<String> dispatcher =
        new BoundedWorkDispatcher<>(
            "test", 2, POLL, drain, worker, new FakeScheduler(), claimNext, executed::add, v -> {});

    try {
      dispatcher.start();
      assertTrue(enteredClaim.await(5, TimeUnit.SECONDS));
      // drain 被钉在 claim 上时并发 wake：只合并、不产生新 drain 任务，也不丢失任何 due。
      for (int i = 0; i < 10; i++) {
        dispatcher.wake();
      }
      releaseClaim.countDown();

      awaitTrue(() -> worker.size() == 2);
      while (worker.size() > 0) {
        worker.runNext();
      }
      awaitTrue(() -> executed.size() == 2);
      assertEquals(List.of("a", "b"), executed);
    } finally {
      dispatcher.stop();
      drain.shutdownNow();
    }
  }

  private static ThreadPoolExecutor pool(RejectedExecutionHandler handler) {
    return new ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1), handler);
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(5, TimeUnit.SECONDS)) {
        throw new AssertionError("latch timed out");
      }
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(error);
    }
  }

  private static void awaitTrue(BooleanSupplier condition) {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() >= deadline) {
        throw new AssertionError("condition not met");
      }
      LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
    }
  }

  /** 手动推进提交任务的 executor；可编程同步拒绝与抛 Error，用于确定性观察容量与 wake。 */
  private static final class ManualExecutor implements Executor {

    private final Queue<Runnable> tasks = new ConcurrentLinkedQueue<>();
    private boolean reject;
    private Throwable failure;

    @Override
    public void execute(Runnable command) {
      if (failure instanceof RuntimeException runtimeException) {
        throw runtimeException;
      }
      if (failure instanceof Error error) {
        throw error;
      }
      if (reject) {
        throw new RejectedExecutionException("saturated");
      }
      tasks.add(command);
    }

    int size() {
      return tasks.size();
    }

    void runNext() {
      Runnable task = tasks.poll();
      if (task == null) {
        throw new AssertionError("no queued task");
      }
      task.run();
    }
  }

  /** 记录 fixed-delay 注册参数、可编程注册失败的 fake scheduler。 */
  private static final class FakeScheduler extends AbstractExecutorService
      implements ScheduledExecutorService {

    private final FakeFuture future = new FakeFuture();
    private Runnable command;
    private long initialDelay;
    private long delay;
    private TimeUnit unit;
    private int registrations;
    private boolean failNext;

    @Override
    public ScheduledFuture<?> scheduleWithFixedDelay(
        Runnable command, long initialDelay, long delay, TimeUnit unit) {
      if (failNext) {
        throw new RejectedExecutionException("schedule rejected");
      }
      this.command = command;
      this.initialDelay = initialDelay;
      this.delay = delay;
      this.unit = unit;
      this.registrations++;
      return future;
    }

    int registrations() {
      return registrations;
    }

    @Override
    public void execute(Runnable command) {
      throw unsupported();
    }

    @Override
    public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
      throw unsupported();
    }

    @Override
    public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
      throw unsupported();
    }

    @Override
    public ScheduledFuture<?> scheduleAtFixedRate(
        Runnable command, long initialDelay, long period, TimeUnit unit) {
      throw unsupported();
    }

    @Override
    public void shutdown() {
      throw unsupported();
    }

    @Override
    public List<Runnable> shutdownNow() {
      throw unsupported();
    }

    @Override
    public boolean isShutdown() {
      return false;
    }

    @Override
    public boolean isTerminated() {
      return false;
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) {
      return false;
    }

    private static UnsupportedOperationException unsupported() {
      return new UnsupportedOperationException("not used");
    }

    private static final class FakeFuture implements ScheduledFuture<Object> {

      private final AtomicInteger cancelCalls = new AtomicInteger();
      private boolean cancelled;

      @Override
      public boolean cancel(boolean mayInterruptIfRunning) {
        cancelCalls.incrementAndGet();
        cancelled = true;
        return true;
      }

      int cancelCalls() {
        return cancelCalls.get();
      }

      @Override
      public boolean isCancelled() {
        return cancelled;
      }

      @Override
      public boolean isDone() {
        return cancelled;
      }

      @Override
      public Object get() {
        return null;
      }

      @Override
      public Object get(long timeout, TimeUnit unit) {
        return null;
      }

      @Override
      public long getDelay(TimeUnit unit) {
        return 0;
      }

      @Override
      public int compareTo(Delayed other) {
        return 0;
      }
    }
  }
}
