package fun.fengwk.kkstudio.platform.harness;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * {@link ExecutorSafety} 单元测试：覆盖三种不安全拒绝策略（CallerRunsPolicy、DiscardPolicy、DiscardOldestPolicy）、
 * 内联运行的 inline executor、正常异步 executor、异常探针容忍与 null 入参校验。
 */
class ExecutorSafetyTest {

  /** 验证 CallerRunsPolicy 拒绝策略会被准确拒绝，且保留 CallerRunsPolicy 不支持的错误描述。 */
  @Test
  void callerRunsPolicyIsRejected() {
    ExecutorService executor =
        new ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(),
            new ThreadPoolExecutor.CallerRunsPolicy());
    try {
      IllegalStateException ex =
          assertThrows(
              IllegalStateException.class,
              () -> ExecutorSafety.requireSafeAsyncExecutor(executor, "test component"));
      assertEquals(
          "test component requires a non-inline executor; CallerRunsPolicy is not supported",
          ex.getMessage());
    } finally {
      executor.shutdownNow();
    }
  }

  /** 验证 DiscardPolicy 静默丢弃策略会被准确拒绝，且提示 silent discard 策略不支持。 */
  @Test
  void discardPolicyIsRejected() {
    ExecutorService executor =
        new ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(),
            new ThreadPoolExecutor.DiscardPolicy());
    try {
      IllegalStateException ex =
          assertThrows(
              IllegalStateException.class,
              () -> ExecutorSafety.requireSafeAsyncExecutor(executor, "test component"));
      assertEquals(
          "test component requires a rejecting executor; silent discard policies are not"
              + " supported",
          ex.getMessage());
    } finally {
      executor.shutdownNow();
    }
  }

  /** 验证 DiscardOldestPolicy 静默丢弃策略会被准确拒绝，且提示 silent discard 策略不支持。 */
  @Test
  void discardOldestPolicyIsRejected() {
    ExecutorService executor =
        new ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(),
            new ThreadPoolExecutor.DiscardOldestPolicy());
    try {
      IllegalStateException ex =
          assertThrows(
              IllegalStateException.class,
              () -> ExecutorSafety.requireSafeAsyncExecutor(executor, "test component"));
      assertEquals(
          "test component requires a rejecting executor; silent discard policies are not"
              + " supported",
          ex.getMessage());
    } finally {
      executor.shutdownNow();
    }
  }

  /** 验证在调用线程同步内联执行任务的 inline executor 会被探针探测并直接拒绝。 */
  @Test
  void inlineExecutorIsRejected() {
    ExecutorService inlineExecutor = new InlineExecutor();
    try {
      IllegalStateException ex =
          assertThrows(
              IllegalStateException.class,
              () -> ExecutorSafety.requireSafeAsyncExecutor(inlineExecutor, "test gateway"));
      assertEquals("test gateway requires a non-inline executor", ex.getMessage());
    } finally {
      inlineExecutor.shutdownNow();
    }
  }

  /** 验证标准异步线程池 executor（默认 AbortPolicy）能够正常通过安全校验，且任务能在独立线程异步执行。 */
  @Test
  void safeAsyncThreadPoolExecutorIsAccepted() throws Exception {
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      assertDoesNotThrow(() -> ExecutorSafety.requireSafeAsyncExecutor(executor, "test gateway"));
      CountDownLatch latch = new CountDownLatch(1);
      AtomicReference<Thread> executedThread = new AtomicReference<>();
      executor.execute(
          () -> {
            executedThread.set(Thread.currentThread());
            latch.countDown();
          });
      assertTrue(latch.await(5, TimeUnit.SECONDS), "task must complete asynchronously");
      assertNotNull(executedThread.get());
      assertNotEquals(Thread.currentThread(), executedThread.get());
    } finally {
      executor.shutdownNow();
    }
  }

  /** 验证探针提交阶段若遇到异常（如拒绝执行或损坏）会安全容忍，不误判为 inline executor。 */
  @Test
  void failingProbeIsSafelyTolerated() {
    ExecutorService rejectingExecutor =
        new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(1)) {
          @Override
          public void execute(Runnable command) {
            throw new RejectedExecutionException("probe rejected");
          }
        };
    try {
      assertDoesNotThrow(
          () -> ExecutorSafety.requireSafeAsyncExecutor(rejectingExecutor, "test gateway"));
    } finally {
      rejectingExecutor.shutdownNow();
    }
  }

  /** 验证 null executor 入参会直接抛出 NullPointerException。 */
  @Test
  void nullExecutorThrowsNpe() {
    assertThrows(
        NullPointerException.class,
        () -> ExecutorSafety.requireSafeAsyncExecutor(null, "test gateway"));
  }

  /** 测试用同步内联执行器。 */
  private static final class InlineExecutor extends AbstractExecutorService {

    @Override
    public void shutdown() {}

    @Override
    public List<Runnable> shutdownNow() {
      return List.of();
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
      return true;
    }

    @Override
    public void execute(Runnable command) {
      command.run();
    }
  }
}
