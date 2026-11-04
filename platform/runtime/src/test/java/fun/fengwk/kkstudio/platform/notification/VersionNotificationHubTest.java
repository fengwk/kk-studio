package fun.fengwk.kkstudio.platform.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * VersionNotificationHub：生产方解码 payload，订阅方按 key 注册；先注册再读权威版本、畸形 payload resync 与消费者隔离。
 *
 * <p>生产方适配器（Harness Thread version / Canvas revision）的通道和事件映射由各自测试覆盖，本测试固定通用推送机制。
 */
class VersionNotificationHubTest {

  private static final UUID KEY_A = new UUID(0L, 7L);
  private static final UUID KEY_B = new UUID(0L, 8L);

  private record TestEvent(Long version, boolean resync) {}

  private static VersionNotificationHub<TestEvent> hub() {
    return new VersionNotificationHub<>(
        VersionNotificationHubTest::decode,
        version -> new TestEvent(version, false),
        () -> new TestEvent(null, true));
  }

  private static VersionNotificationHub.Notification decode(String payload) {
    if (payload == null) {
      return null;
    }
    int colon = payload.indexOf(':');
    if (colon <= 0 || colon != payload.lastIndexOf(':')) {
      return null;
    }
    try {
      return new VersionNotificationHub.Notification(
          UUID.fromString(payload.substring(0, colon)),
          Long.parseLong(payload.substring(colon + 1)));
    } catch (IllegalArgumentException error) {
      return null;
    }
  }

  @Test
  void subscribeRegistersBeforeReadingCurrentVersion() {
    VersionNotificationHub<TestEvent> hub = hub();
    List<TestEvent> received = new ArrayList<>();

    VersionNotificationHub.Subscription subscribed =
        hub.subscribe(
            KEY_A,
            key -> {
              // 读取权威版本期间到达的通知必须已经能送达，证明 consumer 先于读取注册。
              hub.onNotification(key + ":4");
              return 5L;
            },
            received::add);

    assertEquals(List.of(new TestEvent(4L, false)), received);
    assertEquals(5L, subscribed.cursor(), "ack cursor must be the authoritative version");
    subscribed.close();
  }

  @Test
  void failedVersionReadReleasesRegistrationAndPreservesRuntimeException() {
    VersionNotificationHub<TestEvent> hub = hub();
    List<TestEvent> received = new ArrayList<>();

    IllegalStateException failure =
        assertThrows(
            IllegalStateException.class,
            () ->
                hub.subscribe(
                    KEY_A,
                    key -> {
                      throw new IllegalStateException("storage error");
                    },
                    received::add));
    assertEquals("storage error", failure.getMessage());

    hub.broadcastResync();
    assertTrue(received.isEmpty(), "cursor 查询失败的订阅必须已释放");
  }

  @Test
  void failedVersionReadReleasesRegistrationAndRethrowsError() {
    VersionNotificationHub<TestEvent> hub = hub();
    AssertionError injected = new AssertionError("injected query error");
    List<TestEvent> received = new ArrayList<>();

    Error failure =
        assertThrows(
            AssertionError.class,
            () ->
                hub.subscribe(
                    KEY_A,
                    key -> {
                      throw injected;
                    },
                    received::add));
    assertSame(injected, failure, "Error 必须原样抛出");

    hub.broadcastResync();
    assertTrue(received.isEmpty(), "cursor 查询失败的订阅必须已释放");
  }

  @Test
  void validNotificationIsFanOutOnlyToItsKey() {
    VersionNotificationHub<TestEvent> hub = hub();
    List<TestEvent> keyA = new ArrayList<>();
    List<TestEvent> keyB = new ArrayList<>();
    VersionNotificationHub.Subscription subscriptionA = hub.subscribe(KEY_A, key -> 1L, keyA::add);
    hub.subscribe(KEY_B, key -> 2L, keyB::add);

    hub.onNotification(KEY_A + ":6");

    assertEquals(List.of(new TestEvent(6L, false)), keyA);
    assertTrue(keyB.isEmpty(), "其他 key 的通知不得泄漏到本资源");

    subscriptionA.close();
    subscriptionA.close();
    hub.onNotification(KEY_A + ":7");
    assertEquals(List.of(new TestEvent(6L, false)), keyA, "释放必须幂等且停止后续投递");
  }

  @Test
  void malformedPayloadBroadcastsResyncToEverySubscriber() {
    VersionNotificationHub<TestEvent> hub = hub();
    List<TestEvent> keyA = new ArrayList<>();
    List<TestEvent> keyB = new ArrayList<>();
    hub.subscribe(KEY_A, key -> 1L, keyA::add);
    hub.subscribe(KEY_B, key -> 2L, keyB::add);

    hub.onNotification("not-a-version-notification");
    hub.onNotification("00000000-0000-0000-0000-000000000007:not-a-version");
    hub.onNotification(null);
    hub.broadcastResync();

    assertEquals(
        List.of(
            new TestEvent(null, true),
            new TestEvent(null, true),
            new TestEvent(null, true),
            new TestEvent(null, true)),
        keyA);
    assertEquals(4, keyB.size());
    assertTrue(keyB.stream().allMatch(TestEvent::resync));
  }

  @Test
  void failingSubscriberDoesNotBlockOtherSubscribersOfTheSameKey() {
    VersionNotificationHub<TestEvent> hub = hub();
    hub.subscribe(
        KEY_A,
        key -> 1L,
        ignored -> {
          throw new IllegalStateException("boom");
        });
    List<TestEvent> normal = new ArrayList<>();
    hub.subscribe(KEY_A, key -> 2L, normal::add);

    hub.broadcastResync();
    hub.onNotification(KEY_A + ":6");

    assertEquals(
        List.of(new TestEvent(null, true), new TestEvent(6L, false)),
        normal,
        "单个消费者异常只隔离自身，不阻断同 key 其他订阅者或后续通知");
  }

  @Test
  void notificationForUnknownLocalKeyDoesNotReadAuthoritativeVersion() {
    VersionNotificationHub<TestEvent> hub = hub();
    AtomicInteger reads = new AtomicInteger();
    hub.subscribe(KEY_A, key -> reads.incrementAndGet(), ignored -> {});

    hub.onNotification(KEY_B + ":9");

    assertEquals(1, reads.get(), "只有订阅时读取一次权威版本，通知本身不回读");
  }

  @Test
  void lastReleaseConcurrentWithSubscribeKeepsNewSubscriberLive() throws Exception {
    VersionNotificationHub<TestEvent> hub = hub();
    CountDownLatch secondReadEntered = new CountDownLatch(1);
    CountDownLatch secondReadProceed = new CountDownLatch(1);
    AtomicInteger reads = new AtomicInteger();

    VersionNotificationHub.Subscription first =
        hub.subscribe(
            KEY_A,
            key -> {
              reads.incrementAndGet();
              return 1L;
            },
            ignored -> {});
    List<TestEvent> secondReceived = new ArrayList<>();

    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      Future<VersionNotificationHub.Subscription> second =
          executor.submit(
              () ->
                  hub.subscribe(
                      KEY_A,
                      key -> {
                        if (reads.incrementAndGet() == 2) {
                          secondReadEntered.countDown();
                          try {
                            secondReadProceed.await();
                          } catch (InterruptedException error) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(error);
                          }
                        }
                        return 2L;
                      },
                      secondReceived::add));
      assertTrue(secondReadEntered.await(5, TimeUnit.SECONDS), "second subscribe must register");
      first.close(); // 最后释放与 in-flight 新订阅并发
      secondReadProceed.countDown();
      second.get(5, TimeUnit.SECONDS);

      hub.broadcastResync();
      assertEquals(
          List.of(new TestEvent(null, true)),
          secondReceived,
          "new subscriber must stay in the map's subscriber set");
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void notificationAndSubscriptionRejectInvalidValues() {
    assertThrows(
        NullPointerException.class, () -> new VersionNotificationHub.Notification(null, 1L));
    assertThrows(
        IllegalArgumentException.class, () -> new VersionNotificationHub.Notification(KEY_A, -1L));
    assertThrows(
        IllegalArgumentException.class,
        () -> new VersionNotificationHub.Subscription(-1L, () -> {}));
    assertThrows(
        NullPointerException.class, () -> new VersionNotificationHub.Subscription(0L, null));

    VersionNotificationHub<TestEvent> hub = hub();
    assertThrows(NullPointerException.class, () -> hub.subscribe(null, key -> 0L, ignored -> {}));
    assertThrows(NullPointerException.class, () -> hub.subscribe(KEY_A, null, ignored -> {}));
    assertThrows(NullPointerException.class, () -> hub.subscribe(KEY_A, key -> 0L, null));
  }
}
