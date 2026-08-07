package fun.fengwk.kkstudio.web.environment;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

import fun.fengwk.kkstudio.core.ai.environment.gateway.EnvironmentDaemonConnection;
import fun.fengwk.kkstudio.core.ai.environment.gateway.EnvironmentDaemonEndpoint;

import java.io.IOException;

/** 针对那些无法在 Tomcat 上确定性引发的传输异常的单元契约。 */
class EnvironmentDaemonWebSocketHandlerTest {

  @Test
  void exposesTheAiEnvironmentDaemonPath() {
    assertEquals("/api/ai/environment/daemon/v2", EnvironmentDaemonWebSocketHandler.PATH);
  }

  /** Socket I/O 失败以 gateway 发送失败的形式呈现，传输错误会丢弃该句柄。 */
  @Test
  void translatesSessionIoFailuresAndForwardsTransportErrors() throws Exception {
    EnvironmentDaemonEndpoint endpoint = mock(EnvironmentDaemonEndpoint.class);
    EnvironmentDaemonWebSocketHandler handler = new EnvironmentDaemonWebSocketHandler(endpoint);
    WebSocketSession session = mock(WebSocketSession.class);
    when(session.getId()).thenReturn("connection-id");
    handler.afterConnectionEstablished(session);
    ArgumentCaptor<EnvironmentDaemonConnection> connectionCaptor =
        ArgumentCaptor.forClass(EnvironmentDaemonConnection.class);
    verify(endpoint).open(connectionCaptor.capture());
    EnvironmentDaemonConnection connection = connectionCaptor.getValue();

    doThrow(new IOException("send failed")).when(session).sendMessage(any(TextMessage.class));
    assertThrows(IllegalStateException.class, () -> connection.sendText("payload"));
    doThrow(new IOException("close failed")).when(session).close();
    assertThrows(IllegalStateException.class, connection::close);

    handler.handleTextMessage(session, new TextMessage("inbound"));
    verify(endpoint).receive("connection-id", "inbound");
    handler.handleTransportError(session, new IOException("transport failed"));
    handler.afterConnectionClosed(session, CloseStatus.NORMAL);
    verify(endpoint, times(2)).close(eq("connection-id"));
  }
}
