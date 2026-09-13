package fun.fengwk.kkstudio.harness.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * {@link McpCancellationToken} 语义测试，重点是共享的 {@link McpCancellationToken#none()} 单例。
 *
 * <p>该单例跨所有「不需要取消」的调用共享，因此必须真正不可取消、且绝不保留监听器：否则一次误用会永久污染全进程的后续调用，或让监听器无界累积。
 */
class McpCancellationTokenTest {

  /** none() 必须真正不可取消：cancel 不改变状态、不触发任何回调，且后续取用仍是同一未取消实例。 */
  @Test
  void noneTokenIsImmutableAndNeverNotifies() {
    McpCancellationToken none = McpCancellationToken.none();
    AtomicInteger notifications = new AtomicInteger(0);
    none.onCancel(notifications::incrementAndGet);

    none.cancel();
    none.cancel();

    assertThat(none.isCancelled()).as("共享单例绝不能被 cancel 污染").isFalse();
    assertThat(notifications.get()).as("永无取消事件的令牌不得触发回调").isZero();
    // 共享单例身份与状态都必须稳定：污染后面所有调用是不可接受的
    assertThat(McpCancellationToken.none()).isSameAs(none);
    assertThat(McpCancellationToken.none().isCancelled()).isFalse();
  }

  /** none() 绝不保留监听器：否则每次 call 的注册都会让共享单例无界增长。 */
  @Test
  void noneTokenDoesNotRetainListeners() {
    McpCancellationToken none = McpCancellationToken.none();
    for (int i = 0; i < 1_000; i++) {
      none.onCancel(() -> {});
    }
    assertThat(none.listenerCount()).as("共享单例不得累积监听器").isZero();
  }

  /** 正常令牌语义不受影响：可取消、监听器恰好触发一次、注册晚于取消时立即回放。 */
  @Test
  void cancellableTokenKeepsNormalSemantics() {
    McpCancellationToken token = new McpCancellationToken();
    AtomicInteger fired = new AtomicInteger(0);
    token.onCancel(fired::incrementAndGet);
    assertThat(token.listenerCount()).isEqualTo(1);

    assertThat(token.isCancelled()).isFalse();
    token.cancel();
    token.cancel();

    assertThat(token.isCancelled()).isTrue();
    assertThat(fired.get()).as("取消必须幂等，回调只触发一次").isEqualTo(1);
    assertThat(token.listenerCount()).as("取消后必须清除监听器引用").isZero();

    // 取消之后注册的监听器必须立即执行，保证「先取消再建立请求」不丢取消意图
    AtomicBoolean lateListener = new AtomicBoolean(false);
    token.onCancel(() -> lateListener.set(true));
    assertThat(lateListener).isTrue();
    assertThat(token.listenerCount()).as("取消后新注册的监听器同样不保留引用").isZero();
  }

  /** 取消必须对其他线程可见：跨线程等待的调用方不能被永久阻塞。 */
  @Test
  void cancellationIsVisibleAcrossThreads() throws Exception {
    McpCancellationToken token = new McpCancellationToken();
    CountDownLatch cancelled = new CountDownLatch(1);
    token.onCancel(cancelled::countDown);

    ExecutorService pool = Executors.newSingleThreadExecutor();
    try {
      pool.submit(token::cancel);
      assertThat(cancelled.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(token.isCancelled()).isTrue();
    } finally {
      pool.shutdownNow();
    }
  }

  /** 并发注册与取消竞争：高轮次下断言每个监听器至多被触发一次，且 cancel 后监听器引用被清空。 */
  @Test
  @Timeout(60)
  void concurrentCancelAndOnCancelInvokesEachListenerAtMostOnceAndClearsReferences()
      throws Exception {
    int rounds = 2_000;
    ExecutorService pool = Executors.newFixedThreadPool(4);
    try {
      for (int round = 0; round < rounds; round++) {
        McpCancellationToken token = new McpCancellationToken();
        AtomicInteger listener1Calls = new AtomicInteger(0);
        AtomicInteger listener2Calls = new AtomicInteger(0);
        AtomicInteger listener3Calls = new AtomicInteger(0);

        CountDownLatch ready = new CountDownLatch(4);
        CountDownLatch go = new CountDownLatch(1);

        Future<?> f1 =
            pool.submit(
                () -> {
                  ready.countDown();
                  await(go);
                  token.onCancel(listener1Calls::incrementAndGet);
                });
        Future<?> f2 =
            pool.submit(
                () -> {
                  ready.countDown();
                  await(go);
                  token.onCancel(listener2Calls::incrementAndGet);
                });
        Future<?> f3 =
            pool.submit(
                () -> {
                  ready.countDown();
                  await(go);
                  token.onCancel(listener3Calls::incrementAndGet);
                });
        Future<?> f4 =
            pool.submit(
                () -> {
                  ready.countDown();
                  await(go);
                  token.cancel();
                });

        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
        go.countDown();

        f1.get(5, TimeUnit.SECONDS);
        f2.get(5, TimeUnit.SECONDS);
        f3.get(5, TimeUnit.SECONDS);
        f4.get(5, TimeUnit.SECONDS);

        assertThat(token.isCancelled()).isTrue();
        assertThat(listener1Calls.get()).as("listener 1 至多调用一次 (round %d)", round).isEqualTo(1);
        assertThat(listener2Calls.get()).as("listener 2 至多调用一次 (round %d)", round).isEqualTo(1);
        assertThat(listener3Calls.get()).as("listener 3 至多调用一次 (round %d)", round).isEqualTo(1);
        assertThat(token.listenerCount())
            .as("cancel 后 listenerCount 恒为 0 (round %d)", round)
            .isZero();
      }
    } finally {
      pool.shutdownNow();
    }
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(10, TimeUnit.SECONDS)) {
        throw new AssertionError("latch timed out");
      }
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new AssertionError("interrupted while waiting", error);
    }
  }
}
