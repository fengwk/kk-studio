package fun.fengwk.kkstudio.web.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
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

class ThreadRevisionHubTest {

  @Test
  void subscribeRegistersFirstThenReturnsCurrentCursorAsAckCursor() throws Exception {
    DataSource dataSource = mock(DataSource.class);
    Connection connection = mock(Connection.class);
    PreparedStatement statement = mock(PreparedStatement.class);
    ResultSet result = mock(ResultSet.class);
    when(dataSource.getConnection()).thenReturn(connection);
    when(connection.prepareStatement("select revision from harness_thread where id = ?"))
        .thenReturn(statement);
    when(statement.executeQuery()).thenReturn(result);
    when(result.next()).thenReturn(true);
    when(result.getLong(1)).thenReturn(5L);

    ThreadRevisionHub hub = new ThreadRevisionHub(dataSource);
    UUID threadId = new UUID(0L, 7L);
    List<ThreadRevisionEventSource.Event> received = new ArrayList<>();
    SourceSubscribed subscribed = hub.subscribe(threadId, received::add);

    assertEquals(5L, subscribed.cursor(), "ack cursor must be the current durable revision");
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
    when(connection.prepareStatement("select revision from harness_thread where id = ?"))
        .thenReturn(statement);
    when(statement.executeQuery()).thenReturn(result);
    when(result.next()).thenReturn(true);
    when(result.getLong(1)).thenReturn(5L);

    ThreadRevisionHub hub = new ThreadRevisionHub(dataSource);
    UUID threadId = new UUID(0L, 7L);
    List<ThreadRevisionEventSource.Event> staleSubscriber = new ArrayList<>();
    List<ThreadRevisionEventSource.Event> currentSubscriber = new ArrayList<>();
    SourceSubscribed stale = hub.subscribe(threadId, staleSubscriber::add);
    hub.subscribe(threadId, currentSubscriber::add);

    hub.broadcastResync();
    assertEquals(List.of(new ThreadRevisionEventSource.Event(null, true)), staleSubscriber);
    assertEquals(List.of(new ThreadRevisionEventSource.Event(null, true)), currentSubscriber);

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
    when(connection.prepareStatement("select revision from harness_thread where id = ?"))
        .thenReturn(statement);
    when(statement.executeQuery()).thenReturn(result);
    when(result.next()).thenReturn(false);

    ThreadRevisionHub hub = new ThreadRevisionHub(dataSource);
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
    when(connection.prepareStatement("select revision from harness_thread where id = ?"))
        .thenReturn(statement);
    when(statement.executeQuery()).thenReturn(result);
    when(result.next()).thenReturn(true);
    when(result.getLong(1)).thenReturn(5L);

    ThreadRevisionHub hub = new ThreadRevisionHub(dataSource);
    UUID threadId = new UUID(0L, 7L);
    hub.subscribe(
        threadId,
        ignored -> {
          throw new IllegalStateException("boom");
        });
    List<ThreadRevisionEventSource.Event> normal = new ArrayList<>();
    hub.subscribe(threadId, normal::add);

    // 第一个消费者抛异常只被隔离：同资源其他消费者照常收到，全局广播循环不被杀死。
    hub.broadcastResync();
    assertEquals(List.of(new ThreadRevisionEventSource.Event(null, true)), normal);
    hub.broadcastResync();
    assertEquals(2, normal.size());
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

    ThreadRevisionHub hub = new ThreadRevisionHub(dataSource);
    UUID threadId = new UUID(0L, 7L);
    SourceSubscribed first = hub.subscribe(threadId, ignored -> {});
    List<ThreadRevisionEventSource.Event> secondReceived = new ArrayList<>();

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
          List.of(new ThreadRevisionEventSource.Event(null, true)),
          secondReceived,
          "new subscriber must stay in the map's subscriber set");
    } finally {
      executor.shutdownNow();
    }
  }
}
