package fun.fengwk.kkstudio.web.environment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.websocket.Session;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.adapter.NativeWebSocketSession;

import fun.fengwk.kkstudio.platform.environment.gateway.EnvironmentDaemonConnection;
import fun.fengwk.kkstudio.platform.environment.gateway.EnvironmentDaemonEndpoint;
import fun.fengwk.kkstudio.platform.environment.gateway.EnvironmentGatewayProperties;

/** Environment Daemon handler 的连接装配、入站桥接与幂等解绑契约。 */
class EnvironmentDaemonWebSocketHandlerTest {

  @Test
  void exposesTheHarnessEnvironmentDaemonPath() {
    assertEquals("/api/harness/environment-daemon/v1", EnvironmentDaemonWebSocketHandler.PATH);
  }

  /** 关闭事件先且只向 Gateway 解绑一次；sender 随后独立关闭。 */
  @Test
  void forwardsInboundAndDisconnectsGatewayExactlyOnce() throws Exception {
    EnvironmentDaemonEndpoint endpoint = mock(EnvironmentDaemonEndpoint.class);
    EnvironmentDaemonWebSocketHandler handler =
        new EnvironmentDaemonWebSocketHandler(endpoint, gatewayProperties(16L * 1024 * 1024));
    WebSocketSession session = mock(WebSocketSession.class);
    when(session.getId()).thenReturn("connection-id");
    when(session.isOpen()).thenReturn(true);
    handler.afterConnectionEstablished(session);
    ArgumentCaptor<EnvironmentDaemonConnection> connectionCaptor =
        ArgumentCaptor.forClass(EnvironmentDaemonConnection.class);
    verify(endpoint).open(connectionCaptor.capture());
    assertEquals("connection-id", connectionCaptor.getValue().connectionId());

    handler.handleTextMessage(session, new TextMessage("inbound"));
    verify(endpoint).receive("connection-id", "inbound");
    handler.handleTransportError(session, new IllegalStateException("transport failed"));
    handler.afterConnectionClosed(session, CloseStatus.NORMAL);
    verify(endpoint, times(1)).close(eq("connection-id"));
  }

  /** 新连接使用部署配置的单帧上限。 */
  @Test
  void appliesConfiguredMaxMessageBytesOnEachConnection() {
    EnvironmentDaemonEndpoint endpoint = mock(EnvironmentDaemonEndpoint.class);
    EnvironmentDaemonWebSocketHandler handler =
        new EnvironmentDaemonWebSocketHandler(endpoint, gatewayProperties(4L * 1024 * 1024));
    NativeWebSocketSession session = mock(NativeWebSocketSession.class);
    Session jsrSession = mock(Session.class);
    when(session.getId()).thenReturn("connection-id");
    when(session.isOpen()).thenReturn(true);
    when(session.getNativeSession(Session.class)).thenReturn(jsrSession);

    handler.afterConnectionEstablished(session);
    verify(session).setTextMessageSizeLimit(4 * 1024 * 1024);
    verify(session).setBinaryMessageSizeLimit(4 * 1024 * 1024);
    verify(jsrSession).setMaxTextMessageBufferSize(4 * 1024 * 1024);
    verify(jsrSession).setMaxBinaryMessageBufferSize(4 * 1024 * 1024);
  }

  private static EnvironmentGatewayProperties gatewayProperties(long maxMessageBytes) {
    EnvironmentGatewayProperties properties = new EnvironmentGatewayProperties();
    properties.setMaxMessageBytes(maxMessageBytes);
    return properties;
  }
}
