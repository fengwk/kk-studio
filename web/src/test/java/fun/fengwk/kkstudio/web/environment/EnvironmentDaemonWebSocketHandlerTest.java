package fun.fengwk.kkstudio.web.environment;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import fun.fengwk.kkstudio.core.environment.gateway.EnvironmentDaemonConnection;
import fun.fengwk.kkstudio.core.environment.gateway.EnvironmentDaemonGateway;

import java.io.IOException;

/** Unit contracts for transport exceptions that cannot be deterministically induced over Tomcat. */
class EnvironmentDaemonWebSocketHandlerTest {

  /**
   * Socket I/O failures surface as gateway-send failures and transport errors discard the handle.
   */
  @Test
  void translatesSessionIoFailuresAndForwardsTransportErrors() throws Exception {
    EnvironmentDaemonGateway gateway = mock(EnvironmentDaemonGateway.class);
    EnvironmentDaemonWebSocketHandler handler = new EnvironmentDaemonWebSocketHandler(gateway);
    WebSocketSession session = mock(WebSocketSession.class);
    when(session.getId()).thenReturn("connection-id");
    handler.afterConnectionEstablished(session);
    ArgumentCaptor<EnvironmentDaemonConnection> connectionCaptor =
        ArgumentCaptor.forClass(EnvironmentDaemonConnection.class);
    verify(gateway).open(connectionCaptor.capture());
    EnvironmentDaemonConnection connection = connectionCaptor.getValue();

    doThrow(new IOException("send failed")).when(session).sendMessage(any(TextMessage.class));
    assertThrows(IllegalStateException.class, () -> connection.sendText("payload"));
    doThrow(new IOException("close failed")).when(session).close();
    assertThrows(IllegalStateException.class, connection::close);

    handler.handleTextMessage(session, new TextMessage("inbound"));
    verify(gateway).receive("connection-id", "inbound");
    handler.handleTransportError(session, new IOException("transport failed"));
    handler.afterConnectionClosed(session, CloseStatus.NORMAL);
    verify(gateway, times(2)).close(eq("connection-id"));
  }
}
