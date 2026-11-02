package fun.fengwk.kkstudio.web.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.canvas.CanvasDocument;
import fun.fengwk.kkstudio.canvas.CanvasStore;

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

class CanvasVersionHubTest {

  private CanvasStore createStore(UUID canvasId, Long revision) {
    CanvasStore store = mock(CanvasStore.class);
    if (revision != null) {
      CanvasDocument doc = mock(CanvasDocument.class);
      when(doc.revision()).thenReturn(revision);
      when(store.findDocument(canvasId)).thenReturn(Optional.of(doc));
    } else {
      when(store.findDocument(canvasId)).thenReturn(Optional.empty());
    }
    return store;
  }

  @Test
  void subscribeRegistersFirstThenReturnsCurrentRevisionAsAckCursor() throws Exception {
    UUID canvasId = new UUID(0L, 7L);
    CanvasStore store = createStore(canvasId, 5L);

    CanvasVersionHub hub = new CanvasVersionHub(store);
    List<CanvasVersionEventSource.Event> received = new ArrayList<>();
    SourceSubscribed subscribed = hub.subscribe(canvasId, received::add);

    assertEquals(5L, subscribed.cursor(), "ack cursor must be the current durable revision");
    assertTrue(received.isEmpty(), "subscribe must not deliver the current value");
    subscribed.handle().close();
  }

  @Test
  void broadcastResyncFanOutsWithoutLeakingClosedSubscribers() throws Exception {
    UUID canvasId = new UUID(0L, 7L);
    CanvasStore store = createStore(canvasId, 5L);

    CanvasVersionHub hub = new CanvasVersionHub(store);
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
    CanvasStore store = createStore(canvasId, null);

    CanvasVersionHub hub = new CanvasVersionHub(store);
    assertThrows(IllegalArgumentException.class, () -> hub.subscribe(canvasId, ignored -> {}));

    hub.broadcastResync();
  }

  @Test
  void failedCursorQueryReleasesRegistrationAndPreservesRuntimeException() {
    CanvasStore store = mock(CanvasStore.class);
    UUID canvasId = new UUID(0L, 8L);
    when(store.findDocument(canvasId)).thenThrow(new IllegalStateException("storage error"));

    CanvasVersionHub hub = new CanvasVersionHub(store);
    List<CanvasVersionEventSource.Event> received = new ArrayList<>();
    IllegalStateException failure =
        assertThrows(IllegalStateException.class, () -> hub.subscribe(canvasId, received::add));
    assertEquals("storage error", failure.getMessage());

    hub.broadcastResync();
    assertTrue(received.isEmpty(), "cursor 查询失败的订阅必须已释放，不再收到任何投递");
  }

  @Test
  void failedCursorQueryReleasesRegistrationAndRethrowsError() {
    CanvasStore store = mock(CanvasStore.class);
    UUID canvasId = new UUID(0L, 8L);
    AssertionError injected = new AssertionError("injected query error");
    when(store.findDocument(canvasId)).thenThrow(injected);

    CanvasVersionHub hub = new CanvasVersionHub(store);
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
    CanvasStore store = createStore(canvasId, 5L);

    CanvasVersionHub hub = new CanvasVersionHub(store);
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
    CanvasStore store = mock(CanvasStore.class);
    CanvasDocument doc = mock(CanvasDocument.class);
    when(doc.revision()).thenReturn(5L);

    CountDownLatch secondQueryEntered = new CountDownLatch(1);
    CountDownLatch secondQueryProceed = new CountDownLatch(1);
    AtomicInteger queries = new AtomicInteger();
    when(store.findDocument(canvasId))
        .thenAnswer(
            inv -> {
              if (queries.incrementAndGet() == 2) {
                secondQueryEntered.countDown();
                secondQueryProceed.await();
              }
              return Optional.of(doc);
            });

    CanvasVersionHub hub = new CanvasVersionHub(store);
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
    CanvasStore store = createStore(canvasId, 5L);

    CanvasVersionHub hub = new CanvasVersionHub(store);
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
    CanvasStore store = mock(CanvasStore.class);
    CanvasVersionHub hub = new CanvasVersionHub(store);

    hub.onNotification("00000000-0000-0000-0000-000000000007:6");

    verifyNoInteractions(store);
  }
}
