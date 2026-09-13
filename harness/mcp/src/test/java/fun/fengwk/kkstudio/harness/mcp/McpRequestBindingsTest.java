package fun.fengwk.kkstudio.harness.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * {@link McpRequestBindings} 的「取消 ↔ 派发」原子性测试。
 *
 * <p>覆盖取消与派发的两个交错方向，且不靠 sleep 猜时序：两侧由 {@link CountDownLatch} 精确编排，断言的是「取消意图绝不丢失」这一不变式。
 */
class McpRequestBindingsTest {

  /** 记录被中止的 request：既验证「确实发出了取消通知」，也验证「绝不为不存在的 request 伪造取消」。 */
  private static final class RecordingAborter implements McpRequestAborter {

    private final Map<Long, Integer> aborted = new ConcurrentHashMap<>();

    @Override
    public boolean abort(long requestId, String reason) {
      aborted.merge(requestId, 1, Integer::sum);
      return true;
    }

    int callCount() {
      return aborted.values().stream().mapToInt(Integer::intValue).sum();
    }

    boolean abortedRequest(long requestId) {
      return aborted.containsKey(requestId);
    }
  }

  /** 取消先于派发：请求绝不允许写出，且不得为不存在的 request 发送取消通知。 */
  @Test
  void cancelBeforeDispatchPreventsRequestFromBeingSent() throws Exception {
    McpRequestBindings bindings = new McpRequestBindings();
    RecordingAborter aborter = new RecordingAborter();
    bindings.bindAborter(aborter);
    McpCancellationToken token = new McpCancellationToken();

    McpRequestBindings.Binding binding = bindings.create(token);
    bindings.activate(binding);
    try {
      // SDK 已把真实 request id 告知绑定，但请求尚未交给传输写出
      binding.prepare(7L);
      token.cancel();

      AtomicInteger registrations = new AtomicInteger(0);
      boolean dispatched = bindings.dispatchOrAbort(7L, registrations::incrementAndGet);

      assertThat(dispatched).as("取消先到：请求必须被拦住").isFalse();
      assertThat(registrations.get()).as("被拦住的请求不得登记为在途").isZero();
      assertThat(aborter.callCount()).as("不存在的 request 不得收到取消通知").isZero();
    } finally {
      bindings.deactivate();
    }
  }

  /** 派发先于取消：请求已登记为在途，取消必须精确中止它并发送取消通知。 */
  @Test
  void cancelAfterDispatchAbortsTheRegisteredRequest() throws Exception {
    McpRequestBindings bindings = new McpRequestBindings();
    RecordingAborter aborter = new RecordingAborter();
    bindings.bindAborter(aborter);
    McpCancellationToken token = new McpCancellationToken();

    McpRequestBindings.Binding binding = bindings.create(token);
    bindings.activate(binding);
    try {
      binding.prepare(11L);
      AtomicInteger registrations = new AtomicInteger(0);
      assertThat(bindings.dispatchOrAbort(11L, registrations::incrementAndGet)).isTrue();
      assertThat(registrations.get()).isEqualTo(1);

      token.cancel();

      assertThat(aborter.abortedRequest(11L)).isTrue();
      assertThat(aborter.callCount()).isEqualTo(1);
    } finally {
      bindings.deactivate();
    }
  }

  /** 取消幂等：重复 cancel 不得重复发送取消通知。 */
  @Test
  void repeatedCancelSendsSingleNotification() throws Exception {
    McpRequestBindings bindings = new McpRequestBindings();
    RecordingAborter aborter = new RecordingAborter();
    bindings.bindAborter(aborter);
    McpCancellationToken token = new McpCancellationToken();

    McpRequestBindings.Binding binding = bindings.create(token);
    bindings.activate(binding);
    try {
      binding.prepare(3L);
      assertThat(bindings.dispatchOrAbort(3L, () -> {})).isTrue();

      token.cancel();
      token.cancel();
      token.cancel();

      assertThat(aborter.callCount()).isEqualTo(1);
    } finally {
      bindings.deactivate();
    }
  }

  /** 无绑定线程（例如初始化握手）不得被闸门拦截：非调用级请求没有取消意图。 */
  @Test
  void nonCallRequestsAreNeverBlocked() throws Exception {
    McpRequestBindings bindings = new McpRequestBindings();
    AtomicInteger registrations = new AtomicInteger(0);
    assertThat(bindings.dispatchOrAbort(1L, registrations::incrementAndGet)).isTrue();
    assertThat(registrations.get()).isEqualTo(1);
  }

  /**
   * 取消与派发并发竞争：无论谁先取得监视器，取消意图都不能丢失。
   *
   * <p>结果被显式穷举为两种，绝不接受第三种：请求未写出（无登记、无通知），或请求已写出（必然收到取消通知）。
   */
  @Test
  @Timeout(60)
  void concurrentCancelAndDispatchNeverLosesCancellation() throws Exception {
    int rounds = 2_000;
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      for (int round = 0; round < rounds; round++) {
        McpRequestBindings bindings = new McpRequestBindings();
        RecordingAborter aborter = new RecordingAborter();
        bindings.bindAborter(aborter);
        McpCancellationToken token = new McpCancellationToken();
        McpRequestBindings.Binding binding = bindings.create(token);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger registrations = new AtomicInteger(0);
        AtomicReference<Boolean> dispatched = new AtomicReference<>();

        Future<?> dispatcher =
            pool.submit(
                () -> {
                  // 绑定是线程局部状态：必须在真正发起派发的线程上激活，否则闸门看不到本次调用。
                  bindings.activate(binding);
                  try {
                    ready.countDown();
                    await(go);
                    dispatched.set(bindings.dispatchOrAbort(5L, registrations::incrementAndGet));
                  } finally {
                    bindings.deactivate();
                  }
                  return null;
                });
        Future<?> canceller =
            pool.submit(
                () -> {
                  ready.countDown();
                  await(go);
                  token.cancel();
                  return null;
                });
        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
        go.countDown();
        dispatcher.get(10, TimeUnit.SECONDS);
        canceller.get(10, TimeUnit.SECONDS);

        if (Boolean.TRUE.equals(dispatched.get())) {
          assertThat(aborter.abortedRequest(5L)).as("已写出的请求必然收到取消通知（round %s）", round).isTrue();
        } else {
          assertThat(registrations.get()).as("被拦住的请求不得登记（round %s）", round).isZero();
          assertThat(aborter.callCount()).as("不得为未写出的请求伪造取消（round %s）", round).isZero();
        }
      }
    } finally {
      pool.shutdownNow();
    }
  }

  /** 调用结束（绑定已废弃）后的迟到取消不得误拦后续请求。 */
  @Test
  void deactivatedBindingDoesNotBlockLaterRequests() throws Exception {
    McpRequestBindings bindings = new McpRequestBindings();
    McpCancellationToken token = new McpCancellationToken();
    McpRequestBindings.Binding binding = bindings.create(token);
    bindings.activate(binding);
    bindings.deactivate();
    token.cancel();

    AtomicInteger registrations = new AtomicInteger(0);
    assertThat(bindings.dispatchOrAbort(9L, registrations::incrementAndGet)).isTrue();
    assertThat(registrations.get()).isEqualTo(1);
  }

  /** 传输未装配 aborter（HTTP 走 per-request SSE 取消）时，取消只结束派发，绝不抛错。 */
  @Test
  void cancellationWithoutAborterIsStillDecisive() throws Exception {
    McpRequestBindings bindings = new McpRequestBindings();
    McpCancellationToken token = new McpCancellationToken();
    McpRequestBindings.Binding binding = bindings.create(token);
    bindings.activate(binding);
    try {
      binding.prepare(2L);
      token.cancel();
      assertThat(bindings.dispatchOrAbort(2L, () -> {})).isFalse();
    } finally {
      bindings.deactivate();
    }
  }

  /** 验证派发闸门的写出互斥性：底层写出动作（模拟向子进程管道发送原请求）必须完整执行完毕后， 迟到的取消才能发送取消通知；若取消先到达，则派发动作完全被阻止执行。 */
  @Test
  @Timeout(30)
  void fullDispatchActionCompletesBeforeCancellationAbortCanExecuteAndCancelFirstPreventsAction()
      throws Exception {
    McpRequestBindings bindings = new McpRequestBindings();
    AtomicInteger abortCalls = new AtomicInteger();
    AtomicBoolean actionCompleted = new AtomicBoolean(false);
    AtomicBoolean abortPrecededActionCompletion = new AtomicBoolean(false);
    bindings.bindAborter(
        (requestId, reason) -> {
          abortPrecededActionCompletion.set(!actionCompleted.get());
          abortCalls.incrementAndGet();
          return true;
        });

    // 1. 验证：派发先到时，写出动作完整执行完成前，取消通知绝不能抢先发出
    McpCancellationToken token1 = new McpCancellationToken();
    CountDownLatch cancellationStarted = new CountDownLatch(1);
    // 该监听器先于 Binding 注册；它触发后，cancel 已经进入监听器派发，下一步必然尝试中止 Binding。
    token1.onCancel(cancellationStarted::countDown);
    McpRequestBindings.Binding binding1 = bindings.create(token1);
    bindings.activate(binding1);
    try {
      binding1.prepare(42L);

      CountDownLatch dispatchStarted = new CountDownLatch(1);
      CountDownLatch allowDispatchToFinish = new CountDownLatch(1);

      ExecutorService pool = Executors.newFixedThreadPool(2);
      try {
        Future<Boolean> dispatchFuture =
            pool.submit(
                () -> {
                  bindings.activate(binding1);
                  try {
                    return bindings.dispatchOrAbort(
                        42L,
                        () -> {
                          dispatchStarted.countDown();
                          await(allowDispatchToFinish);
                          actionCompleted.set(true);
                        });
                  } finally {
                    bindings.deactivate();
                  }
                });

        await(dispatchStarted);
        // 此时派发正在执行底层写出动作（模拟正在写管道）
        assertThat(actionCompleted.get()).as("写出动作尚未完成").isFalse();

        // 另一个线程发起取消
        Future<?> cancelFuture = pool.submit(token1::cancel);
        assertThat(cancellationStarted.await(5, TimeUnit.SECONDS)).as("取消线程必须已经进入监听器派发").isTrue();

        // 允许写出动作完成
        allowDispatchToFinish.countDown();
        Boolean dispatched = dispatchFuture.get(5, TimeUnit.SECONDS);
        cancelFuture.get(5, TimeUnit.SECONDS);

        assertThat(dispatched).as("派发应成功").isTrue();
        assertThat(actionCompleted.get()).as("写出动作必须完整执行完毕").isTrue();
        assertThat(abortPrecededActionCompletion.get()).as("取消通知绝不能早于原请求写出完成").isFalse();
        assertThat(abortCalls.get()).as("写出完成后取消通知必然发出一次").isEqualTo(1);
      } finally {
        pool.shutdownNow();
      }
    } finally {
      bindings.deactivate();
    }

    // 2. 验证：取消先到时，写出动作完全不执行，且不为未写出的请求伪造取消通知
    McpCancellationToken token2 = new McpCancellationToken();
    McpRequestBindings.Binding binding2 = bindings.create(token2);
    bindings.activate(binding2);
    try {
      binding2.prepare(43L);
      token2.cancel();

      AtomicBoolean actionRan = new AtomicBoolean(false);
      boolean dispatched = bindings.dispatchOrAbort(43L, () -> actionRan.set(true));

      assertThat(dispatched).as("先取消后派发必须被拦住").isFalse();
      assertThat(actionRan.get()).as("被拦住的请求派发动作完全不得执行").isFalse();
      assertThat(abortCalls.get()).as("未写出的请求不得发送额外取消通知").isEqualTo(1);
    } finally {
      bindings.deactivate();
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
