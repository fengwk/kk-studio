package fun.fengwk.kkstudio.harness.infra.postgresql;

import static fun.fengwk.kkstudio.harness.runtime.store.testing.TestIds.id;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStreamEvent;
import fun.fengwk.kkstudio.harness.runtime.realtime.RealtimeEvent;

import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** PostgresqlRealtimeEventSource 的 callback/global lifecycle lock 与 close fence 并发契约。 */
class PostgresqlRealtimeEventSourceConcurrencyTest {

  private static final Instant NOW = Instant.parse("2026-08-05T00:00:00Z");

  private final RealtimeNotificationCodec codec = new RealtimeNotificationCodec();

  /**
   * 阻塞中的用户回调不能持有全局 lifecycle lock：其他 Thread 仍可 subscribe/close，统一 listener 的另一条 notification
   * 也能独立完成。
   */
  @Test
  void blockingCallbackDoesNotHoldGlobalLifecycleLock() throws Exception {
    PostgresqlRealtimeEventSource source = new PostgresqlRealtimeEventSource(codec);
    CountDownLatch callbackEntered = new CountDownLatch(1);
    CountDownLatch releaseCallback = new CountDownLatch(1);
    CountDownLatch otherDelivered = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(3);
    try {
      source.subscribe(
          id(1L),
          ignored -> {
            callbackEntered.countDown();
            await(releaseCallback);
          },
          () -> {});
      Future<?> blockedDispatch =
          executor.submit(
              () -> source.onNotification(codec.encodeEvent(modelDelta(1L, "blocked"))));
      assertTrue(callbackEntered.await(5, TimeUnit.SECONDS));

      AutoCloseable other =
          executor
              .submit(
                  () -> source.subscribe(id(2L), ignored -> otherDelivered.countDown(), () -> {}))
              .get(1, TimeUnit.SECONDS);
      executor
          .submit(() -> source.onNotification(codec.encodeEvent(modelDelta(2L, "other"))))
          .get(1, TimeUnit.SECONDS);
      assertTrue(otherDelivered.await(1, TimeUnit.SECONDS));
      executor.submit(() -> closeUnchecked(other)).get(1, TimeUnit.SECONDS);

      releaseCallback.countDown();
      blockedDispatch.get(5, TimeUnit.SECONDS);
    } finally {
      releaseCallback.countDown();
      source.close();
      executor.shutdownNow();
    }
  }

  /** 订阅句柄 close 必须等待已开始的 callback；返回后旧快照与新 notification 都不能再次进入 callback。 */
  @Test
  void subscriptionCloseWaitsForStartedCallbackAndFencesLaterCallbacks() throws Exception {
    PostgresqlRealtimeEventSource source = new PostgresqlRealtimeEventSource(codec);
    CountDownLatch callbackEntered = new CountDownLatch(1);
    CountDownLatch releaseCallback = new CountDownLatch(1);
    CountDownLatch closeStarted = new CountDownLatch(1);
    AtomicInteger callbacks = new AtomicInteger();
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      AutoCloseable subscription =
          source.subscribe(
              id(1L),
              ignored -> {
                callbacks.incrementAndGet();
                callbackEntered.countDown();
                await(releaseCallback);
              },
              () -> {});
      Future<?> dispatch =
          executor.submit(() -> source.onNotification(codec.encodeEvent(modelDelta(1L, "one"))));
      assertTrue(callbackEntered.await(5, TimeUnit.SECONDS));
      Future<?> close =
          executor.submit(
              () -> {
                closeStarted.countDown();
                closeUnchecked(subscription);
              });
      assertTrue(closeStarted.await(5, TimeUnit.SECONDS));
      assertStillWaiting(close);

      releaseCallback.countDown();
      close.get(5, TimeUnit.SECONDS);
      dispatch.get(5, TimeUnit.SECONDS);
      source.onNotification(codec.encodeEvent(modelDelta(1L, "dropped")));

      assertEquals(1, callbacks.get());
    } finally {
      releaseCallback.countDown();
      source.close();
      executor.shutdownNow();
    }
  }

  /** Source close 同样等待全部已开始 callback；返回后 notification/resync 均无回调且拒绝新订阅。 */
  @Test
  void sourceCloseWaitsForStartedCallbackAndFencesLaterCallbacks() throws Exception {
    PostgresqlRealtimeEventSource source = new PostgresqlRealtimeEventSource(codec);
    CountDownLatch callbackEntered = new CountDownLatch(1);
    CountDownLatch releaseCallback = new CountDownLatch(1);
    CountDownLatch closeStarted = new CountDownLatch(2);
    AtomicInteger callbacks = new AtomicInteger();
    AtomicInteger resyncs = new AtomicInteger();
    ExecutorService executor = Executors.newFixedThreadPool(3);
    try {
      source.subscribe(
          id(1L),
          ignored -> {
            callbacks.incrementAndGet();
            callbackEntered.countDown();
            await(releaseCallback);
          },
          resyncs::incrementAndGet);
      Future<?> dispatch =
          executor.submit(() -> source.onNotification(codec.encodeEvent(modelDelta(1L, "one"))));
      assertTrue(callbackEntered.await(5, TimeUnit.SECONDS));
      Future<?> firstClose =
          executor.submit(
              () -> {
                closeStarted.countDown();
                source.close();
              });
      Future<?> secondClose =
          executor.submit(
              () -> {
                closeStarted.countDown();
                source.close();
              });
      assertTrue(closeStarted.await(5, TimeUnit.SECONDS));
      assertStillWaiting(firstClose);
      assertStillWaiting(secondClose);

      releaseCallback.countDown();
      firstClose.get(5, TimeUnit.SECONDS);
      secondClose.get(5, TimeUnit.SECONDS);
      dispatch.get(5, TimeUnit.SECONDS);
      source.onNotification(codec.encodeEvent(modelDelta(1L, "dropped")));
      source.onResync();

      assertEquals(1, callbacks.get());
      assertEquals(0, resyncs.get());
      assertThrows(
          IllegalStateException.class, () -> source.subscribe(id(1L), ignored -> {}, () -> {}));
    } finally {
      releaseCallback.countDown();
      source.close();
      executor.shutdownNow();
    }
  }

  /** onEvent 可以重入关闭自己的订阅，当前 callback 正常返回且后续 callback 被 fenced。 */
  @Test
  void callbackCanReenterSubscriptionClose() throws Exception {
    PostgresqlRealtimeEventSource source = new PostgresqlRealtimeEventSource(codec);
    AtomicReference<AutoCloseable> subscription = new AtomicReference<>();
    AtomicInteger callbacks = new AtomicInteger();
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      subscription.set(
          source.subscribe(
              id(1L),
              ignored -> {
                callbacks.incrementAndGet();
                closeUnchecked(subscription.get());
              },
              () -> {}));

      executor
          .submit(() -> source.onNotification(codec.encodeEvent(modelDelta(1L, "one"))))
          .get(5, TimeUnit.SECONDS);
      source.onNotification(codec.encodeEvent(modelDelta(1L, "dropped")));

      assertEquals(1, callbacks.get());
    } finally {
      source.close();
      executor.shutdownNow();
    }
  }

  /** onEvent 可以重入 source.close；close 不自锁，回调返回后 source 保持完整关闭边界。 */
  @Test
  void callbackCanReenterSourceClose() throws Exception {
    PostgresqlRealtimeEventSource source = new PostgresqlRealtimeEventSource(codec);
    AtomicInteger callbacks = new AtomicInteger();
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      source.subscribe(
          id(1L),
          ignored -> {
            source.close();
            callbacks.incrementAndGet();
          },
          () -> {});

      executor
          .submit(() -> source.onNotification(codec.encodeEvent(modelDelta(1L, "one"))))
          .get(5, TimeUnit.SECONDS);
      source.onNotification(codec.encodeEvent(modelDelta(1L, "dropped")));

      assertEquals(1, callbacks.get());
      assertThrows(
          IllegalStateException.class, () -> source.subscribe(id(1L), ignored -> {}, () -> {}));
    } finally {
      source.close();
      executor.shutdownNow();
    }
  }

  /** 两个并发 callback 同时重入 source.close 也不能形成互相等待的关闭环。 */
  @Test
  void concurrentCallbacksCanBothReenterSourceCloseWithoutDeadlock() throws Exception {
    PostgresqlRealtimeEventSource source = new PostgresqlRealtimeEventSource(codec);
    CountDownLatch callbacksEntered = new CountDownLatch(2);
    CountDownLatch startClose = new CountDownLatch(1);
    AtomicInteger closesReturned = new AtomicInteger();
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      for (long threadId : new long[] {1L, 2L}) {
        source.subscribe(
            id(threadId),
            ignored -> {
              callbacksEntered.countDown();
              await(startClose);
              source.close();
              closesReturned.incrementAndGet();
            },
            () -> {});
      }

      Future<?> first =
          executor.submit(() -> source.onNotification(codec.encodeEvent(modelDelta(1L, "one"))));
      Future<?> second =
          executor.submit(() -> source.onNotification(codec.encodeEvent(modelDelta(2L, "two"))));
      assertTrue(callbacksEntered.await(5, TimeUnit.SECONDS));
      startClose.countDown();
      first.get(5, TimeUnit.SECONDS);
      second.get(5, TimeUnit.SECONDS);

      assertEquals(2, closesReturned.get());
    } finally {
      startClose.countDown();
      source.close();
      executor.shutdownNow();
    }
  }

  private static void assertStillWaiting(Future<?> close) throws Exception {
    assertThrows(TimeoutException.class, () -> close.get(200, TimeUnit.MILLISECONDS));
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(5, TimeUnit.SECONDS)) {
        throw new AssertionError("latch was not released");
      }
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new AssertionError(error);
    }
  }

  private static void closeUnchecked(AutoCloseable closeable) {
    try {
      closeable.close();
    } catch (Exception error) {
      throw new AssertionError(error);
    }
  }

  private static RealtimeEvent.ModelDelta modelDelta(long threadId, String text) {
    return new RealtimeEvent.ModelDelta(
        id(threadId), id(42L), 1, 1L, new ProviderStreamEvent.TextDelta(text), NOW);
  }
}
