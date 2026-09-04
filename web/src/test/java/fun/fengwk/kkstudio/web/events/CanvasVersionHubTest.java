package fun.fengwk.kkstudio.web.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import javax.sql.DataSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

class CanvasVersionHubTest {

  @Test
  void subscribeRegistersFirstThenReturnsCurrentCursorAsAckCursor() throws Exception {
    DataSource dataSource = mock(DataSource.class);
    Connection connection = mock(Connection.class);
    PreparedStatement statement = mock(PreparedStatement.class);
    ResultSet result = mock(ResultSet.class);
    when(dataSource.getConnection()).thenReturn(connection);
    when(connection.prepareStatement("select version from canvas_document where id = ?"))
        .thenReturn(statement);
    when(statement.executeQuery()).thenReturn(result);
    when(result.next()).thenReturn(true);
    when(result.getLong(1)).thenReturn(5L);

    CanvasVersionHub hub = new CanvasVersionHub(dataSource);
    UUID canvasId = new UUID(0L, 7L);
    List<CanvasVersionEventSource.Event> received = new ArrayList<>();
    SourceSubscribed subscribed = hub.subscribe(canvasId, received::add);

    assertEquals(5L, subscribed.cursor(), "ack cursor must be the current canvas version");
    verify(statement).setObject(1, canvasId);
    assertTrue(received.isEmpty(), "subscribe must not deliver the current value");
    subscribed.handle().close();
  }

  @Test
  void broadcastResyncFanOutsWithoutLeakingClosedSubscribers() throws Exception {
    DataSource dataSource = mock(DataSource.class);
    Connection connection = mock(Connection.class);
    PreparedStatement statement = mock(PreparedStatement.class);
    ResultSet result = mock(ResultSet.class);
    when(dataSource.getConnection()).thenReturn(connection);
    when(connection.prepareStatement("select version from canvas_document where id = ?"))
        .thenReturn(statement);
    when(statement.executeQuery()).thenReturn(result);
    when(result.next()).thenReturn(true);
    when(result.getLong(1)).thenReturn(5L);

    CanvasVersionHub hub = new CanvasVersionHub(dataSource);
    UUID canvasId = new UUID(0L, 7L);
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
  void subscribeRejectsUnknownCanvasWithoutLeakingRegistration() throws Exception {
    DataSource dataSource = mock(DataSource.class);
    Connection connection = mock(Connection.class);
    PreparedStatement statement = mock(PreparedStatement.class);
    ResultSet result = mock(ResultSet.class);
    when(dataSource.getConnection()).thenReturn(connection);
    when(connection.prepareStatement("select version from canvas_document where id = ?"))
        .thenReturn(statement);
    when(statement.executeQuery()).thenReturn(result);
    when(result.next()).thenReturn(false);

    CanvasVersionHub hub = new CanvasVersionHub(dataSource);
    UUID canvasId = new UUID(0L, 8L);
    assertThrows(IllegalArgumentException.class, () -> hub.subscribe(canvasId, ignored -> {}));

    hub.broadcastResync();
    verify(statement, times(1)).setObject(1, canvasId);
  }

  @Test
  void failingSubscriberDoesNotBlockOtherSubscribersOfTheSameCanvas() throws Exception {
    DataSource dataSource = mock(DataSource.class);
    Connection connection = mock(Connection.class);
    PreparedStatement statement = mock(PreparedStatement.class);
    ResultSet result = mock(ResultSet.class);
    when(dataSource.getConnection()).thenReturn(connection);
    when(connection.prepareStatement("select version from canvas_document where id = ?"))
        .thenReturn(statement);
    when(statement.executeQuery()).thenReturn(result);
    when(result.next()).thenReturn(true);
    when(result.getLong(1)).thenReturn(5L);

    CanvasVersionHub hub = new CanvasVersionHub(dataSource);
    UUID canvasId = new UUID(0L, 7L);
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
    // 确定性交错：新订阅者已完成注册（compute 内 add 原子完成）但卡在 cursor 读取时，最后释放并发执行；
    // remove-if-empty 在 computeIfPresent 内原子完成，存活订阅者始终留在 map 中并继续收到事件。
    DataSource dataSource = mock(DataSource.class);
    Connection connection = mock(Connection.class);
    PreparedStatement statement = mock(PreparedStatement.class);
    ResultSet result = mock(ResultSet.class);
    when(dataSource.getConnection()).thenReturn(connection);
    when(connection.prepareStatement(any())).thenReturn(statement);
    CountDownLatch secondQueryEntered = new CountDownLatch(1);
    CountDownLatch secondQueryProceed = new CountDownLatch(1);
    AtomicInteger queries = new AtomicInteger();
    when(statement.executeQuery())
        .thenAnswer(
            inv -> {
              if (queries.incrementAndGet() == 2) {
                secondQueryEntered.countDown();
                secondQueryProceed.await();
              }
              return result;
            });
    when(result.next()).thenReturn(true);
    when(result.getLong(1)).thenReturn(5L);

    CanvasVersionHub hub = new CanvasVersionHub(dataSource);
    UUID canvasId = new UUID(0L, 7L);
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
  void malformedNotificationPayloadBroadcastsResyncAndIsolatesFailingSubscriber() throws Exception {
    DataSource dataSource = mock(DataSource.class);
    Connection connection = mock(Connection.class);
    PreparedStatement statement = mock(PreparedStatement.class);
    ResultSet result = mock(ResultSet.class);
    when(dataSource.getConnection()).thenReturn(connection);
    when(connection.prepareStatement("select version from canvas_document where id = ?"))
        .thenReturn(statement);
    when(statement.executeQuery()).thenReturn(result);
    when(result.next()).thenReturn(true);
    when(result.getLong(1)).thenReturn(5L);

    CanvasVersionHub hub = new CanvasVersionHub(dataSource);
    UUID canvasId = new UUID(0L, 9L);
    hub.subscribe(
        canvasId,
        ignored -> {
          throw new IllegalStateException("boom");
        });
    List<CanvasVersionEventSource.Event> received = new ArrayList<>();
    hub.subscribe(canvasId, received::add);

    // 合法 payload 轻量 fan-out；每个畸形 payload 都降级为 resync，失败订阅者不阻断正常订阅者或后续通知。
    hub.onNotification("00000000-0000-0000-0000-000000000009:7");
    hub.onNotification("not-a-uuid:7");
    hub.onNotification("00000000-0000-0000-0000-000000000009");
    hub.onNotification("00000000-0000-0000-0000-000000000009:07");
    hub.onNotification("00000000-0000-0000-0000-000000000009:-1");
    hub.onNotification("00000000-0000-0000-0000-000000000009:9223372036854775808");
    hub.onNotification("00000000-0000-0000-0000-00000000009:7");
    hub.onNotification(null);

    assertEquals(
        List.of(
            new CanvasVersionEventSource.Event(7L, false),
            new CanvasVersionEventSource.Event(null, true),
            new CanvasVersionEventSource.Event(null, true),
            new CanvasVersionEventSource.Event(null, true),
            new CanvasVersionEventSource.Event(null, true),
            new CanvasVersionEventSource.Event(null, true),
            new CanvasVersionEventSource.Event(null, true),
            new CanvasVersionEventSource.Event(null, true)),
        received);
    verify(dataSource, times(2)).getConnection();
  }

  @Test
  void validNotificationWithoutLocalSubscriberDoesNotReadDatabase() {
    DataSource dataSource = mock(DataSource.class);
    CanvasVersionHub hub = new CanvasVersionHub(dataSource);

    hub.onNotification("00000000-0000-0000-0000-000000000009:7");

    verifyNoInteractions(dataSource);
  }
}
