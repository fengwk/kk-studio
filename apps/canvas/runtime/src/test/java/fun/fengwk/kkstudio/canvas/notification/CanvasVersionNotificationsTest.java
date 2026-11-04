package fun.fengwk.kkstudio.canvas.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.canvas.CanvasDocument;
import fun.fengwk.kkstudio.canvas.CanvasQueryService;
import fun.fengwk.kkstudio.platform.events.SourceSubscribed;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Canvas revision 通知 Hub：订阅先注册后读游标、畸形通知与重连 resync、退订与并发注册/释放语义。 */
class CanvasVersionNotificationsTest {

  private static CanvasQueryService createQueryService(UUID canvasId, Long revision) {
    CanvasQueryService queryService = mock(CanvasQueryService.class);
    if (revision != null) {
      when(queryService.findDocument(canvasId))
          .thenReturn(Optional.of(document(canvasId, revision)));
    } else {
      when(queryService.findDocument(canvasId)).thenReturn(Optional.empty());
    }
    return queryService;
  }

  private static CanvasDocument document(UUID canvasId, long revision) {
    Instant createdAt = Instant.parse("2026-01-01T00:00:00Z");
    return new CanvasDocument(canvasId, "canvas", revision, createdAt, createdAt);
  }

  @Test
  void subscribeRegistersFirstThenReturnsCurrentRevisionAsAckCursor() throws Exception {
    UUID canvasId = new UUID(0L, 7L);
    CanvasQueryService queryService = createQueryService(canvasId, 5L);

    CanvasVersionNotifications hub = new CanvasVersionNotifications(queryService);
    List<CanvasVersionEventSource.Event> received = new ArrayList<>();
    SourceSubscribed subscribed = hub.subscribe(canvasId, received::add);

    assertEquals(5L, subscribed.cursor(), "ack cursor must be the current durable revision");
    assertTrue(received.isEmpty(), "subscribe must not deliver the current value");
    subscribed.handle().close();
  }

  @Test
  void broadcastResyncFanOutsWithoutLeakingClosedSubscribers() throws Exception {
    UUID canvasId = new UUID(0L, 7L);
    CanvasQueryService queryService = createQueryService(canvasId, 5L);

    CanvasVersionNotifications hub = new CanvasVersionNotifications(queryService);
    List<CanvasVersionEventSource.Event> staleSubscriber = new ArrayList<>();
    List<CanvasVersionEventSource.Event> currentSubscriber = new ArrayList<>();
    SourceSubscribed stale = hub.subscribe(canvasId, staleSubscriber::add);
    hub.subscribe(canvasId, currentSubscriber::add);

    hub.broadcastResync();
    assertEquals(List.of(new CanvasVersionEventSource.Event(null, true)), staleSubscriber);
    assertEquals(List.of(new CanvasVersionEventSource.Event(null, true)), currentSubscriber);

    stale.handle().close();
    hub.broadcastResync();
    assertEquals(1, staleSubscriber.size());
    assertEquals(2, currentSubscriber.size());
  }

  @Test
  void subscribeRejectsUnknownCanvasWithoutLeakingRegistration() {
    UUID canvasId = new UUID(0L, 8L);
    CanvasQueryService queryService = createQueryService(canvasId, null);

    CanvasVersionNotifications hub = new CanvasVersionNotifications(queryService);
    assertThrows(IllegalArgumentException.class, () -> hub.subscribe(canvasId, ignored -> {}));

    hub.broadcastResync();
  }

  @Test
  void failedCursorQueryReleasesRegistrationAndPreservesRuntimeException() {
    CanvasQueryService queryService = mock(CanvasQueryService.class);
    UUID canvasId = new UUID(0L, 8L);
    when(queryService.findDocument(canvasId)).thenThrow(new IllegalStateException("storage error"));

    CanvasVersionNotifications hub = new CanvasVersionNotifications(queryService);
    List<CanvasVersionEventSource.Event> received = new ArrayList<>();
    IllegalStateException failure =
        assertThrows(IllegalStateException.class, () -> hub.subscribe(canvasId, received::add));
    assertEquals("storage error", failure.getMessage());

    hub.broadcastResync();
    assertTrue(received.isEmpty(), "cursor 查询失败的订阅必须已释放，不再收到任何投递");
  }

  @Test
  void failedCursorQueryReleasesRegistrationAndRethrowsError() {
    CanvasQueryService queryService = mock(CanvasQueryService.class);
    UUID canvasId = new UUID(0L, 8L);
    AssertionError injected = new AssertionError("injected query error");
    when(queryService.findDocument(canvasId)).thenThrow(injected);

    CanvasVersionNotifications hub = new CanvasVersionNotifications(queryService);
    List<CanvasVersionEventSource.Event> received = new ArrayList<>();
    Error failure =
        assertThrows(AssertionError.class, () -> hub.subscribe(canvasId, received::add));
    assertSame(injected, failure, "Error 必须原样抛出");

    hub.broadcastResync();
    assertTrue(received.isEmpty(), "cursor 查询失败的订阅必须已释放，不再收到任何投递");
  }

  @Test
  void failingSubscriberDoesNotBlockOtherSubscribersOfTheSameCanvas() {
    UUID canvasId = new UUID(0L, 7L);
    CanvasQueryService queryService = createQueryService(canvasId, 5L);

    CanvasVersionNotifications hub = new CanvasVersionNotifications(queryService);
    hub.subscribe(
        canvasId,
        ignored -> {
          throw new IllegalStateException("boom");
        });
    List<CanvasVersionEventSource.Event> normal = new ArrayList<>();
    hub.subscribe(canvasId, normal::add);

    hub.broadcastResync();
    assertEquals(List.of(new CanvasVersionEventSource.Event(null, true)), normal);
    hub.broadcastResync();
    assertEquals(2, normal.size());
  }

  @Test
  void lastReleaseConcurrentWithSubscribeKeepsNewSubscriberLive() throws Exception {
    UUID canvasId = new UUID(0L, 7L);
    CanvasQueryService queryService = mock(CanvasQueryService.class);

    CountDownLatch secondQueryEntered = new CountDownLatch(1);
    CountDownLatch secondQueryProceed = new CountDownLatch(1);
    AtomicInteger queries = new AtomicInteger();
    when(queryService.findDocument(canvasId))
        .thenAnswer(
            inv -> {
              if (queries.incrementAndGet() == 2) {
                secondQueryEntered.countDown();
                secondQueryProceed.await();
              }
              return Optional.of(document(canvasId, 5L));
            });

    CanvasVersionNotifications hub = new CanvasVersionNotifications(queryService);
    SourceSubscribed first = hub.subscribe(canvasId, ignored -> {});
    List<CanvasVersionEventSource.Event> secondReceived = new ArrayList<>();

    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      Future<SourceSubscribed> second =
          executor.submit(() -> hub.subscribe(canvasId, secondReceived::add));
      assertTrue(secondQueryEntered.await(5, TimeUnit.SECONDS), "second subscribe must register");
      first.handle().close(); // 最后释放与 in-flight 新订阅并发
      secondQueryProceed.countDown();
      second.get(5, TimeUnit.SECONDS);

      hub.broadcastResync();
      assertEquals(
          List.of(new CanvasVersionEventSource.Event(null, true)),
          secondReceived,
          "new subscriber must stay in the map's subscriber set");
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void malformedNotificationPayloadBroadcastsResyncAndIsolatesFailingSubscriber() {
    UUID canvasId = new UUID(0L, 7L);
    CanvasQueryService queryService = createQueryService(canvasId, 5L);

    CanvasVersionNotifications hub = new CanvasVersionNotifications(queryService);
    hub.subscribe(
        canvasId,
        ignored -> {
          throw new IllegalStateException("boom");
        });
    List<CanvasVersionEventSource.Event> received = new ArrayList<>();
    hub.subscribe(canvasId, received::add);

    hub.onNotification("00000000-0000-0000-0000-000000000007:6");
    hub.onNotification("00000000-0000-0000-0000-000000000007");
    hub.onNotification("00000000-0000-0000-0000-000000000007:06");
    hub.onNotification("00000000-0000-0000-0000-000000000007:9223372036854775808");
    hub.onNotification("00000000-0000-0000-0000-00000000007:7");
    hub.onNotification("not-a-uuid:7");
    hub.onNotification(null);

    assertEquals(
        List.of(
            new CanvasVersionEventSource.Event(6L, false),
            new CanvasVersionEventSource.Event(null, true),
            new CanvasVersionEventSource.Event(null, true),
            new CanvasVersionEventSource.Event(null, true),
            new CanvasVersionEventSource.Event(null, true),
            new CanvasVersionEventSource.Event(null, true),
            new CanvasVersionEventSource.Event(null, true)),
        received);
  }

  @Test
  void validNotificationWithoutLocalSubscriberDoesNotReadDatabase() {
    CanvasQueryService queryService = mock(CanvasQueryService.class);
    CanvasVersionNotifications hub = new CanvasVersionNotifications(queryService);

    hub.onNotification("00000000-0000-0000-0000-000000000007:6");

    verifyNoInteractions(queryService);
  }

  /** 通道名只有一处定义：写侧 notifier 与宿主 LISTEN 注册都取用 Hub 常量。 */
  @Test
  void channelIsOwnedByTheHub() {
    assertEquals("canvas_revision", CanvasVersionNotifications.CHANNEL);
  }
}
