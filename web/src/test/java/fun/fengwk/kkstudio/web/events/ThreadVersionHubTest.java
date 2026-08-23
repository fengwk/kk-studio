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

class ThreadVersionHubTest {

  @Test
  void subscribeRegistersFirstThenReturnsCurrentCursorAsAckCursor() throws Exception {
    DataSource dataSource = mock(DataSource.class);
    Connection connection = mock(Connection.class);
    PreparedStatement statement = mock(PreparedStatement.class);
    ResultSet result = mock(ResultSet.class);
    when(dataSource.getConnection()).thenReturn(connection);
    when(connection.prepareStatement("select version from harness_thread where id = ?"))
        .thenReturn(statement);
    when(statement.executeQuery()).thenReturn(result);
    when(result.next()).thenReturn(true);
    when(result.getLong(1)).thenReturn(5L);

    ThreadVersionHub hub = new ThreadVersionHub(dataSource);
    UUID threadId = new UUID(0L, 7L);
    List<ThreadVersionEventSource.Event> received = new ArrayList<>();
    SourceSubscribed subscribed = hub.subscribe(threadId, received::add);

    assertEquals(5L, subscribed.cursor(), "ack cursor must be the current durable version");
    verify(statement).setObject(1, threadId);
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
    when(connection.prepareStatement("select version from harness_thread where id = ?"))
        .thenReturn(statement);
    when(statement.executeQuery()).thenReturn(result);
    when(result.next()).thenReturn(true);
    when(result.getLong(1)).thenReturn(5L);

    ThreadVersionHub hub = new ThreadVersionHub(dataSource);
    UUID threadId = new UUID(0L, 7L);
    List<ThreadVersionEventSource.Event> staleSubscriber = new ArrayList<>();
    List<ThreadVersionEventSource.Event> currentSubscriber = new ArrayList<>();
    SourceSubscribed stale = hub.subscribe(threadId, staleSubscriber::add);
    hub.subscribe(threadId, currentSubscriber::add);

    hub.broadcastResync();
    assertEquals(List.of(new ThreadVersionEventSource.Event(null, true)), staleSubscriber);
    assertEquals(List.of(new ThreadVersionEventSource.Event(null, true)), currentSubscriber);

    stale.handle().close();
    hub.broadcastResync();
    assertEquals(1, staleSubscriber.size());
    assertEquals(2, currentSubscriber.size());
  }

  @Test
  void subscribeRejectsUnknownThreadWithoutLeakingRegistration() throws Exception {
    DataSource dataSource = mock(DataSource.class);
    Connection connection = mock(Connection.class);
    PreparedStatement statement = mock(PreparedStatement.class);
    ResultSet result = mock(ResultSet.class);
    when(dataSource.getConnection()).thenReturn(connection);
    when(connection.prepareStatement("select version from harness_thread where id = ?"))
        .thenReturn(statement);
    when(statement.executeQuery()).thenReturn(result);
    when(result.next()).thenReturn(false);

    ThreadVersionHub hub = new ThreadVersionHub(dataSource);
    UUID threadId = new UUID(0L, 8L);
    assertThrows(IllegalArgumentException.class, () -> hub.subscribe(threadId, ignored -> {}));

    // 失败不遗留注册：后续广播不会触发任何回调。
    hub.broadcastResync();
    verify(statement, times(1)).setObject(1, threadId);
  }

  @Test
  void failingSubscriberDoesNotBlockOtherSubscribersOfTheSameThread() throws Exception {
    DataSource dataSource = mock(DataSource.class);
    Connection connection = mock(Connection.class);
    PreparedStatement statement = mock(PreparedStatement.class);
    ResultSet result = mock(ResultSet.class);
    when(dataSource.getConnection()).thenReturn(connection);
    when(connection.prepareStatement("select version from harness_thread where id = ?"))
        .thenReturn(statement);
    when(statement.executeQuery()).thenReturn(result);
    when(result.next()).thenReturn(true);
    when(result.getLong(1)).thenReturn(5L);

    ThreadVersionHub hub = new ThreadVersionHub(dataSource);
    UUID threadId = new UUID(0L, 7L);
    hub.subscribe(
        threadId,
        ignored -> {
          throw new IllegalStateException("boom");
        });
    List<ThreadVersionEventSource.Event> normal = new ArrayList<>();
    hub.subscribe(threadId, normal::add);

    // 第一个消费者抛异常只被隔离：同资源其他消费者照常收到，全局广播循环不被杀死。
    hub.broadcastResync();
    assertEquals(List.of(new ThreadVersionEventSource.Event(null, true)), normal);
    hub.broadcastResync();
    assertEquals(2, normal.size());
  }

  @Test
  void malformedNotificationPayloadBroadcastsResyncAndIsolatesFailingSubscriber() throws Exception {
    DataSource dataSource = mock(DataSource.class);
    Connection connection = mock(Connection.class);
    PreparedStatement statement = mock(PreparedStatement.class);
    ResultSet result = mock(ResultSet.class);
    when(dataSource.getConnection()).thenReturn(connection);
    when(connection.prepareStatement("select version from harness_thread where id = ?"))
        .thenReturn(statement);
    when(statement.executeQuery()).thenReturn(result);
    when(result.next()).thenReturn(true);
    when(result.getLong(1)).thenReturn(5L);

    ThreadVersionHub hub = new ThreadVersionHub(dataSource);
    UUID threadId = new UUID(0L, 7L);
    hub.subscribe(
        threadId,
        ignored -> {
          throw new IllegalStateException("boom");
        });
    List<ThreadVersionEventSource.Event> received = new ArrayList<>();
    hub.subscribe(threadId, received::add);

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
            new ThreadVersionEventSource.Event("6", false),
            new ThreadVersionEventSource.Event(null, true),
            new ThreadVersionEventSource.Event(null, true),
            new ThreadVersionEventSource.Event(null, true),
            new ThreadVersionEventSource.Event(null, true),
            new ThreadVersionEventSource.Event(null, true),
            new ThreadVersionEventSource.Event(null, true)),
        received);
    verify(dataSource, times(2)).getConnection();
  }

  @Test
  void validNotificationWithoutLocalSubscriberDoesNotReadDatabase() {
    DataSource dataSource = mock(DataSource.class);
    ThreadVersionHub hub = new ThreadVersionHub(dataSource);

    hub.onNotification("00000000-0000-0000-0000-000000000007:6");

    verifyNoInteractions(dataSource);
  }

  @Test
  void lastReleaseConcurrentWithSubscribeKeepsNewSubscriberLive() throws Exception {
    // 确定性交错：新订阅者已完成注册（compute 内 add 原子完成）但卡在 cursor 读取时，最后释放并发执行；
    // 修复后 remove-if-empty 在 computeIfPresent 内原子完成，存活订阅者必然留在 map 的集合中并继续收到事件。
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

    ThreadVersionHub hub = new ThreadVersionHub(dataSource);
    UUID threadId = new UUID(0L, 7L);
    SourceSubscribed first = hub.subscribe(threadId, ignored -> {});
    List<ThreadVersionEventSource.Event> secondReceived = new ArrayList<>();

    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      Future<SourceSubscribed> second =
          executor.submit(() -> hub.subscribe(threadId, secondReceived::add));
      assertTrue(secondQueryEntered.await(5, TimeUnit.SECONDS), "second subscribe must register");
      first.handle().close(); // 最后释放与 in-flight 新订阅并发
      secondQueryProceed.countDown();
      second.get(5, TimeUnit.SECONDS);

      hub.broadcastResync();
      assertEquals(
          List.of(new ThreadVersionEventSource.Event(null, true)),
          secondReceived,
          "new subscriber must stay in the map's subscriber set");
    } finally {
      executor.shutdownNow();
    }
  }
}
