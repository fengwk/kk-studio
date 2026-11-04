package fun.fengwk.kkstudio.platform.harness.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.api.HarnessChangeSubscription;
import fun.fengwk.kkstudio.harness.api.HarnessThreadVersionChanges.Event;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.harness.spi.HarnessThreadChangeSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ThreadVersionHub：公开 version 订阅与内部纯 wake 源共享同一套注册/分发语义。
 *
 * <p>覆盖三条语义：先注册再读当前 version（cursor 即权威 version）、未知 Thread 失败且不遗留注册、畸形 payload 与 LISTEN 重连退化为
 * resync；消费者异常互相隔离，释放后不再收到回调。
 */
class ThreadVersionHubTest {

  private static final UUID THREAD_ID = new UUID(0L, 7L);

  private static HarnessRuntime runtime(Long version) {
    HarnessRuntime runtime = mock(HarnessRuntime.class);
    when(runtime.findThreadVersion(THREAD_ID)).thenReturn(Optional.ofNullable(version));
    return runtime;
  }

  @Test
  void subscribeRegistersFirstThenReturnsCurrentCursorAsAckCursor() {
    ThreadVersionHub hub = new ThreadVersionHub(() -> runtime(5L));
    List<Event> received = new ArrayList<>();

    HarnessChangeSubscription subscribed = hub.subscribe(THREAD_ID, received::add);

    assertEquals(5L, subscribed.cursor(), "ack cursor must be the current durable version");
    assertTrue(received.isEmpty(), "subscribe must not deliver the current value");
    subscribed.close();
  }

  @Test
  void broadcastResyncFanOutsWithoutLeakingClosedSubscribers() {
    ThreadVersionHub hub = new ThreadVersionHub(() -> runtime(5L));
    List<Event> staleSubscriber = new ArrayList<>();
    List<Event> currentSubscriber = new ArrayList<>();
    HarnessChangeSubscription stale = hub.subscribe(THREAD_ID, staleSubscriber::add);
    hub.subscribe(THREAD_ID, currentSubscriber::add);

    hub.broadcastResync();
    assertEquals(List.of(new Event.Resync()), staleSubscriber);
    assertEquals(List.of(new Event.Resync()), currentSubscriber);

    stale.close();
    hub.broadcastResync();
    assertEquals(1, staleSubscriber.size());
    assertEquals(2, currentSubscriber.size());
  }

  @Test
  void subscribeRejectsUnknownThreadWithoutLeakingRegistration() {
    ThreadVersionHub hub = new ThreadVersionHub(() -> runtime(null));

    assertThrows(IllegalArgumentException.class, () -> hub.subscribe(THREAD_ID, ignored -> {}));

    // 失败不遗留注册：后续广播不会触发任何回调。
    hub.broadcastResync();
  }

  @Test
  void failedCursorQueryReleasesRegistrationAndPreservesRuntimeException() {
    HarnessRuntime runtime = mock(HarnessRuntime.class);
    when(runtime.findThreadVersion(THREAD_ID))
        .thenThrow(new IllegalStateException("storage error"));
    ThreadVersionHub hub = new ThreadVersionHub(() -> runtime);
    List<Event> received = new ArrayList<>();

    IllegalStateException failure =
        assertThrows(IllegalStateException.class, () -> hub.subscribe(THREAD_ID, received::add));
    assertEquals("storage error", failure.getMessage());

    hub.broadcastResync();
    assertTrue(received.isEmpty(), "cursor 查询失败的订阅必须已释放，不再收到任何投递");
  }

  @Test
  void failedCursorQueryReleasesRegistrationAndRethrowsError() {
    HarnessRuntime runtime = mock(HarnessRuntime.class);
    AssertionError injected = new AssertionError("injected query error");
    when(runtime.findThreadVersion(THREAD_ID)).thenThrow(injected);
    ThreadVersionHub hub = new ThreadVersionHub(() -> runtime);
    List<Event> received = new ArrayList<>();

    Error failure =
        assertThrows(AssertionError.class, () -> hub.subscribe(THREAD_ID, received::add));
    assertSame(injected, failure, "Error 必须原样抛出");

    hub.broadcastResync();
    assertTrue(received.isEmpty(), "cursor 查询失败的订阅必须已释放，不再收到任何投递");
  }

  @Test
  void failingSubscriberDoesNotBlockOtherSubscribersOfTheSameThread() {
    ThreadVersionHub hub = new ThreadVersionHub(() -> runtime(5L));
    hub.subscribe(
        THREAD_ID,
        ignored -> {
          throw new IllegalStateException("boom");
        });
    List<Event> normal = new ArrayList<>();
    hub.subscribe(THREAD_ID, normal::add);

    // 第一个消费者抛异常只被隔离：同资源其他消费者照常收到，广播循环不被杀死。
    hub.broadcastResync();
    assertEquals(List.of(new Event.Resync()), normal);
    hub.broadcastResync();
    assertEquals(2, normal.size());
  }

  @Test
  void malformedNotificationPayloadBroadcastsResyncAndIsolatesFailingSubscriber() {
    ThreadVersionHub hub = new ThreadVersionHub(() -> runtime(5L));
    hub.subscribe(
        THREAD_ID,
        ignored -> {
          throw new IllegalStateException("boom");
        });
    List<Event> received = new ArrayList<>();
    hub.subscribe(THREAD_ID, received::add);

    // 合法 payload 轻量 fan-out；每个畸形 payload 都降级为 resync，失败订阅者不阻断正常订阅者或后续通知。
    hub.onNotification("00000000-0000-0000-0000-000000000007:6");
    hub.onNotification("00000000-0000-0000-0000-000000000007");
    hub.onNotification("00000000-0000-0000-0000-000000000007:06");
    hub.onNotification("00000000-0000-0000-0000-000000000007:9223372036854775808");
    hub.onNotification("00000000-0000-0000-0000-00000000007:7");
    hub.onNotification("not-a-uuid:7");
    hub.onNotification(null);

    assertEquals(
        List.of(
            new Event.Version(6L),
            new Event.Resync(),
            new Event.Resync(),
            new Event.Resync(),
            new Event.Resync(),
            new Event.Resync(),
            new Event.Resync()),
        received);
  }

  @Test
  void validNotificationWithoutLocalSubscriberDoesNotReadDatabase() {
    HarnessRuntime runtime = mock(HarnessRuntime.class);
    ThreadVersionHub hub = new ThreadVersionHub(() -> runtime);

    hub.onNotification("00000000-0000-0000-0000-000000000007:6");

    verifyNoInteractions(runtime);
  }

  /** 内部观察者只收纯 wake：version 与 resync 都唤醒一次，句柄释放幂等且透传到底层注册。 */
  @Test
  void wakeSubscriptionFoldsEveryEventIntoOneIdempotentWake() {
    ThreadVersionHub hub = new ThreadVersionHub(() -> runtime(5L));
    List<String> wakes = new ArrayList<>();
    HarnessThreadChangeSource.Subscription subscription =
        hub.subscribe(THREAD_ID, () -> wakes.add("wake"));

    hub.onNotification("00000000-0000-0000-0000-000000000007:6");
    hub.broadcastResync();
    assertEquals(2, wakes.size(), "version and resync events must both wake the consumer");

    subscription.close();
    subscription.close();
    hub.broadcastResync();
    assertEquals(2, wakes.size(), "close must be idempotent and stop further wakes");
  }

  /** 内部 wake 源同样拒绝未知 Thread 与空参数，且不遗留注册。 */
  @Test
  void wakeSubscriptionRejectsUnknownThreadAndNulls() {
    ThreadVersionHub hub = new ThreadVersionHub(() -> runtime(null));
    AtomicBoolean woken = new AtomicBoolean();

    assertThrows(
        IllegalArgumentException.class, () -> hub.subscribe(THREAD_ID, () -> woken.set(true)));
    assertThrows(NullPointerException.class, () -> hub.subscribe(null, () -> {}));
    assertThrows(NullPointerException.class, () -> hub.subscribe(THREAD_ID, (Runnable) null));

    hub.broadcastResync();
    assertFalse(woken.get(), "失败的注册不得接收任何唤醒");
  }

  @Test
  void lastReleaseConcurrentWithSubscribeKeepsNewSubscriberLive() throws Exception {
    HarnessRuntime runtime = mock(HarnessRuntime.class);
    CountDownLatch secondQueryEntered = new CountDownLatch(1);
    CountDownLatch secondQueryProceed = new CountDownLatch(1);
    AtomicInteger queries = new AtomicInteger();
    when(runtime.findThreadVersion(THREAD_ID))
        .thenAnswer(
            invocation -> {
              if (queries.incrementAndGet() == 2) {
                secondQueryEntered.countDown();
                secondQueryProceed.await();
              }
              return Optional.of(5L);
            });

    ThreadVersionHub hub = new ThreadVersionHub(() -> runtime);
    HarnessChangeSubscription first = hub.subscribe(THREAD_ID, ignored -> {});
    List<Event> secondReceived = new ArrayList<>();

    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      Future<HarnessChangeSubscription> second =
          executor.submit(() -> hub.subscribe(THREAD_ID, secondReceived::add));
      assertTrue(secondQueryEntered.await(5, TimeUnit.SECONDS), "second subscribe must register");
      first.close(); // 最后释放与 in-flight 新订阅并发
      secondQueryProceed.countDown();
      second.get(5, TimeUnit.SECONDS);

      hub.broadcastResync();
      assertEquals(
          List.of(new Event.Resync()),
          secondReceived,
          "new subscriber must stay in the map's subscriber set");
    } finally {
      executor.shutdownNow();
    }
  }
}
