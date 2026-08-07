package fun.fengwk.kkstudio.web.environment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import fun.fengwk.kkstudio.core.ai.environment.gateway.EnvironmentDaemonConnection;
import fun.fengwk.kkstudio.core.ai.environment.gateway.EnvironmentDaemonGateway;
import fun.fengwk.kkstudio.web.WebPostgresTestSupport;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** Daemon WebSocket 适配器的网络契约；web 层不保存任何协议状态。 */
class EnvironmentDaemonWebSocketEndpointTest extends WebPostgresTestSupport {

  @LocalServerPort private int port;

  /**
   * 用 mock 替换具体的 Gateway（实现了 {@code EnvironmentDaemonEndpoint}），使 web 切片内共享同一 bean 的其他 Core
   * 端口仍能满足装配。
   */
  @MockitoBean private EnvironmentDaemonGateway endpoint;

  /** 端点在两个方向上桥接完整文本帧，并在连接关闭时通知持久化 Gateway。 */
  @Test
  void bridgesDaemonConnectionFramesAndClose() throws Exception {
    BlockingQueue<String> received = new LinkedBlockingQueue<>();
    QueueingListener listener = new QueueingListener(received);
    WebSocket socket =
        HttpClient.newHttpClient()
            .newWebSocketBuilder()
            .buildAsync(endpointUri(), listener)
            .get(10, TimeUnit.SECONDS);

    ArgumentCaptor<EnvironmentDaemonConnection> connectionCaptor =
        ArgumentCaptor.forClass(EnvironmentDaemonConnection.class);
    verify(endpoint, timeout(15_000)).open(connectionCaptor.capture());
    EnvironmentDaemonConnection connection = connectionCaptor.getValue();
    assertNotNull(connection);
    assertTrue(connection.isOpen());

    socket.sendText("daemon-frame", true).get(10, TimeUnit.SECONDS);
    verify(endpoint, timeout(15_000)).receive(eq(connection.connectionId()), eq("daemon-frame"));

    connection.sendText("gateway-frame");
    assertEquals("gateway-frame", received.poll(10, TimeUnit.SECONDS));

    socket.sendClose(WebSocket.NORMAL_CLOSURE, "test complete").get(10, TimeUnit.SECONDS);
    socket.request(1);
    listener.awaitClose();
    verify(endpoint, timeout(15_000)).close(connection.connectionId());
  }

  private URI endpointUri() {
    return URI.create("ws://localhost:" + port + EnvironmentDaemonWebSocketHandler.PATH);
  }

  private static final class QueueingListener implements WebSocket.Listener {

    private final BlockingQueue<String> messages;
    private final StringBuilder currentMessage = new StringBuilder();
    private final CompletableFuture<Void> closed = new CompletableFuture<>();

    private QueueingListener(BlockingQueue<String> messages) {
      this.messages = messages;
    }

    @Override
    public void onOpen(WebSocket webSocket) {
      webSocket.request(1);
    }

    @Override
    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
      currentMessage.append(data);
      if (last) {
        messages.add(currentMessage.toString());
        currentMessage.setLength(0);
      }
      webSocket.request(1);
      return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
      closed.complete(null);
      return CompletableFuture.completedFuture(null);
    }

    private void awaitClose() throws Exception {
      closed.get(5, TimeUnit.SECONDS);
    }
  }
}
