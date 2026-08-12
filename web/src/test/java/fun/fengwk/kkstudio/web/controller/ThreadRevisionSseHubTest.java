package fun.fengwk.kkstudio.web.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

class ThreadRevisionSseHubTest {

  @Test
  void subscribeBridgesRevisionGapAndReconnectResyncWithoutLeakingClosedSubscribers()
      throws Exception {
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

    ThreadRevisionSseHub hub = new ThreadRevisionSseHub(dataSource);
    List<ThreadRevisionEventSource.Event> staleSubscriber = new ArrayList<>();
    List<ThreadRevisionEventSource.Event> currentSubscriber = new ArrayList<>();
    UUID threadId = new UUID(0L, 7L);
    AutoCloseable stale = hub.subscribe(threadId, 4L, staleSubscriber::add);
    hub.subscribe(threadId, 5L, currentSubscriber::add);

    verify(statement, times(2)).setObject(1, threadId);
    assertEquals(List.of(new ThreadRevisionEventSource.Event("5", false)), staleSubscriber);
    assertTrue(currentSubscriber.isEmpty());

    hub.broadcastResync();
    assertEquals(new ThreadRevisionEventSource.Event(null, true), staleSubscriber.get(1));
    assertEquals(List.of(new ThreadRevisionEventSource.Event(null, true)), currentSubscriber);

    stale.close();
    hub.broadcastResync();
    assertEquals(2, staleSubscriber.size());
    assertEquals(2, currentSubscriber.size());
  }
}
