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
  void parsesCanvasIdFromNotifyPayloadIgnoringGarbage() {
    assertEquals(
        new UUID(0L, 9L), CanvasVersionHub.parseCanvasId("00000000-0000-0000-0000-000000000009:7"));
    assertEquals(null, CanvasVersionHub.parseCanvasId("not-a-uuid:7"));
    assertEquals(null, CanvasVersionHub.parseCanvasId("00000000-0000-0000-0000-000000000009"));
    assertEquals(null, CanvasVersionHub.parseCanvasId(null));
  }
}
