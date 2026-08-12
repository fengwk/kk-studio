package fun.fengwk.kkstudio.web.controller;

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

class CanvasVersionSseHubTest {

  @Test
  void subscribeBridgesVersionGapAndReconnectResyncWithoutLeakingClosedSubscribers()
      throws Exception {
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

    CanvasVersionSseHub hub = new CanvasVersionSseHub(dataSource);
    List<CanvasVersionEventSource.Event> staleSubscriber = new ArrayList<>();
    List<CanvasVersionEventSource.Event> currentSubscriber = new ArrayList<>();
    UUID canvasId = new UUID(0L, 7L);
    AutoCloseable stale = hub.subscribe(canvasId, 4L, staleSubscriber::add);
    hub.subscribe(canvasId, 5L, currentSubscriber::add);

    verify(statement, times(2)).setObject(1, canvasId);
    assertEquals(List.of(new CanvasVersionEventSource.Event(5L, false)), staleSubscriber);
    assertTrue(currentSubscriber.isEmpty());

    hub.broadcastResync();
    assertEquals(new CanvasVersionEventSource.Event(null, true), staleSubscriber.get(1));
    assertEquals(List.of(new CanvasVersionEventSource.Event(null, true)), currentSubscriber);

    stale.close();
    hub.broadcastResync();
    assertEquals(2, staleSubscriber.size());
    assertEquals(2, currentSubscriber.size());
  }

  @Test
  void subscribeRejectsUnknownCanvasAndNegativeAfterVersion() throws Exception {
    DataSource dataSource = mock(DataSource.class);
    Connection connection = mock(Connection.class);
    PreparedStatement statement = mock(PreparedStatement.class);
    ResultSet result = mock(ResultSet.class);
    when(dataSource.getConnection()).thenReturn(connection);
    when(connection.prepareStatement("select version from canvas_document where id = ?"))
        .thenReturn(statement);
    when(statement.executeQuery()).thenReturn(result);
    when(result.next()).thenReturn(false);

    CanvasVersionSseHub hub = new CanvasVersionSseHub(dataSource);
    UUID canvasId = new UUID(0L, 8L);
    assertThrows(IllegalArgumentException.class, () -> hub.subscribe(canvasId, 0L, ignored -> {}));
    assertThrows(IllegalArgumentException.class, () -> hub.subscribe(canvasId, -1L, ignored -> {}));
  }

  @Test
  void parsesCanvasIdFromNotifyPayloadIgnoringGarbage() {
    assertEquals(
        new UUID(0L, 9L),
        CanvasVersionSseHub.parseCanvasId("00000000-0000-0000-0000-000000000009:7"));
    assertEquals(null, CanvasVersionSseHub.parseCanvasId("not-a-uuid:7"));
    assertEquals(null, CanvasVersionSseHub.parseCanvasId("00000000-0000-0000-0000-000000000009"));
    assertEquals(null, CanvasVersionSseHub.parseCanvasId(null));
  }
}
