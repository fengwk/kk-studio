package fun.fengwk.kkstudio.web.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
}
