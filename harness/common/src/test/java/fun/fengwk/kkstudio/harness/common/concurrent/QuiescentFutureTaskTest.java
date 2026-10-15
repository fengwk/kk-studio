package fun.fengwk.kkstudio.harness.common.concurrent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

/**
 * {@link QuiescentFutureTask} 的静止事实契约测试：只有真正执行本体的那次 {@code run} 退出才是静止。
 *
 * <p>测试用受控 latch 精确控制本体的进入与退出，因此不依赖 sleep 或真实调度时序：被阻塞的本体代表仍在访问本地资源的执行线程， 重复 {@code run} 与 {@code
 * cancel} 都不允许把它宣告为静止。
 */
class QuiescentFutureTaskTest {

  /** 本体正常返回后立刻完成静止。 */
  @Test
  void quiescenceCompletesWhenBodyReturns() {
    CountDownLatch bodyEntered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    QuiescentFutureTask<Void> task =
        new QuiescentFutureTask<>(
            () -> {
              bodyEntered.countDown();
              await(release);
            });

    Thread worker = new Thread(task);
    worker.start();
    await(bodyEntered);
    assertFalse(task.quiescence().toCompletableFuture().isDone(), "执行线程仍在运行，静止不成立");

    release.countDown();
    join(worker);
    assertTrue(task.quiescence().toCompletableFuture().isDone(), "本体退出后静止成立");
  }

  /** 第一次 run 仍在使用资源时，并发的第二次 run 立即返回，既不执行本体也不完成静止。 */
  @Test
  void concurrentSecondRunNeverCompletesQuiescence() {
    CountDownLatch bodyEntered = new CountDownLatch(1);
    AtomicBoolean released = new AtomicBoolean();
    AtomicInteger executed = new AtomicInteger();
    QuiescentFutureTask<Void> task =
        new QuiescentFutureTask<>(
            () -> {
              executed.incrementAndGet();
              bodyEntered.countDown();
              // 不可中断的本地调用：中断被忽略，执行体只能由 released 决定何时退出。
              while (!released.get()) {
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
              }
            });

    Thread first = new Thread(task);
    first.start();
    await(bodyEntered);

    // 重复提交：第二次 run 不得进入本体，也不得以「任务已定」冒充线程已退出。
    task.run();
    assertEquals(1, executed.get(), "重复 run 绝不再次执行本体");
    assertFalse(task.quiescence().toCompletableFuture().isDone(), "第二次 run 没有任何退出事实可以声明");

    // 取消可以成功（丢弃结果并请求中断），但执行线程仍在运行，因此绝不能宣告静止。
    assertTrue(task.cancel(true), "尚未结束的任务仍可被取消");
    assertTrue(task.isCancelled());
    assertFalse(task.quiescence().toCompletableFuture().isDone(), "取消不能把仍在运行的执行体宣告为静止");

    released.set(true);
    join(first);
    assertTrue(task.quiescence().toCompletableFuture().isDone(), "第一次执行线程退出后静止成立");
  }

  /** 取消先赢（尚未进入本体）是确定的静止事实，且此后不得再执行本体。 */
  @Test
  void cancelBeforeRunCompletesQuiescenceImmediately() {
    AtomicBoolean executed = new AtomicBoolean();
    QuiescentFutureTask<Void> task = new QuiescentFutureTask<>(() -> executed.set(true));

    assertTrue(task.cancel(true));
    assertTrue(task.quiescence().toCompletableFuture().isDone(), "尚未进入本体的取消可以确认静止");

    task.run();
    assertFalse(executed.get(), "取消后不得再执行本体");
    assertTrue(task.quiescence().toCompletableFuture().isDone());
  }

  /** 调用方只能观察静止事实：对只读视图的「提前完成」绝不改变内部事实。 */
  @Test
  void externalViewCannotCompleteTheInternalQuiescenceFact() {
    CountDownLatch bodyEntered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    QuiescentFutureTask<Void> task =
        new QuiescentFutureTask<>(
            () -> {
              bodyEntered.countDown();
              await(release);
            });

    Thread worker = new Thread(task);
    worker.start();
    await(bodyEntered);

    CompletableFuture<Void> external = task.quiescence().toCompletableFuture();
    external.complete(null);
    assertFalse(task.quiescence().toCompletableFuture().isDone(), "调用方不能提前完成内部静止事实");

    release.countDown();
    join(worker);
    assertTrue(task.quiescence().toCompletableFuture().isDone(), "只有真实退出才完成静止");
  }

  /** Callable 同样以执行体退出为静止边界。 */
  @Test
  void callableResultCompletesQuiescence() throws Exception {
    QuiescentFutureTask<String> task = new QuiescentFutureTask<>(() -> "result");
    task.run();
    assertEquals("result", task.get());
    assertTrue(task.quiescence().toCompletableFuture().isDone());
    assertFalse(task.cancel(false));
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

  private static void join(Thread thread) {
    try {
      thread.join(TimeUnit.SECONDS.toMillis(5));
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(error);
    }
    assertFalse(thread.isAlive(), "worker thread did not exit");
  }
}
