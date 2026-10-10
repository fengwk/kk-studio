package fun.fengwk.kkstudio.web.environment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import fun.fengwk.kkstudio.harness.environment.server.DaemonChannel;
import fun.fengwk.kkstudio.harness.environment.server.DaemonOfferResult;
import fun.fengwk.kkstudio.harness.environment.server.EnvironmentDaemonServer;
import fun.fengwk.kkstudio.web.WebPostgresTestSupport;

import java.net.URI;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Daemon WebSocket 适配器的网络契约；web 层不保存任何协议状态，逻辑消息全部经共享 carrier 分片。
 *
 * <p>客户端使用 OkHttp 是因为 JDK {@code HttpClient} 的 WebSocket 不支持扩展协商，无法满足端点强制的 {@code
 * permessage-deflate} 要求；客户端侧用生产 {@link TestPeer} 载体收发，而非第二个 encoder。
 */
class EnvironmentDaemonWebSocketEndpointTest extends WebPostgresTestSupport {

  @LocalServerPort private int port;

  /** 用 mock 替换会话核心端点：本测试只验证 WebSocket 适配器的 carrier 桥接，不启动真实协议状态机。 */
  @MockitoBean private EnvironmentDaemonServer endpoint;

  /** 端点在两个方向上桥接完整逻辑消息，并在连接关闭时通知会话核心。 */
  @Test
  void bridgesDaemonConnectionFramesAndClose() throws Exception {
    TestPeer client = new TestPeer(UUID.randomUUID());
    CarrierListener listener = new CarrierListener(client);
    OkHttpClient httpClient = new OkHttpClient();
    try {
      WebSocket socket =
          httpClient.newWebSocket(
              new Request.Builder().url(endpointUri().toASCIIString()).build(), listener);

      ArgumentCaptor<DaemonChannel> connectionCaptor = ArgumentCaptor.forClass(DaemonChannel.class);
      verify(endpoint, timeout(15_000)).open(connectionCaptor.capture());
      DaemonChannel connection = connectionCaptor.getValue();
      assertNotNull(connection);
      assertTrue(connection.isOpen());

      for (String frame : client.fragments("daemon-frame")) {
        assertTrue(socket.send(frame));
      }
      verify(endpoint, timeout(15_000)).receive(eq(connection.connectionId()), eq("daemon-frame"));

      assertEquals(DaemonOfferResult.ACCEPTED, connection.offerText("gateway-frame"));
      assertTrue(listener.awaitInbox("gateway-frame"));

      socket.close(1000, "test complete");
      listener.awaitClose();
      verify(endpoint, timeout(15_000)).close(connection.connectionId());
    } finally {
      httpClient.dispatcher().executorService().shutdown();
      httpClient.connectionPool().evictAll();
    }
  }

  private URI endpointUri() {
    return URI.create("ws://localhost:" + port + EnvironmentDaemonWebSocketHandler.PATH);
  }

  /** 用生产 TestPeer 重组入站 carrier 并记录关闭事件，验证端点确实协商了 permessage-deflate。 */
  private static final class CarrierListener extends WebSocketListener {

    private final TestPeer peer;
    private final CompletableFuture<String> negotiatedExtension = new CompletableFuture<>();
    private final CompletableFuture<Void> closed = new CompletableFuture<>();

    private CarrierListener(TestPeer peer) {
      this.peer = peer;
    }

    @Override
    public void onOpen(WebSocket webSocket, Response response) {
      String extensions = response.header("Sec-WebSocket-Extensions");
      negotiatedExtension.complete(extensions == null ? "" : extensions);
    }

    @Override
    public void onMessage(WebSocket webSocket, String text) {
      peer.accept(text);
    }

    @Override
    public void onMessage(WebSocket webSocket, ByteString bytes) {
      // 端点只传输文本帧。
    }

    @Override
    public void onClosed(WebSocket webSocket, int code, String reason) {
      closed.complete(null);
    }

    @Override
    public void onFailure(WebSocket webSocket, Throwable error, Response response) {
      closed.complete(null);
    }

    private boolean awaitInbox(String expected) throws InterruptedException {
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
      while (System.nanoTime() < deadline) {
        if (peer.inbox().contains(expected)) {
          return true;
        }
        Thread.sleep(10);
      }
      return peer.inbox().contains(expected);
    }

    private void awaitClose() throws Exception {
      closed.get(5, TimeUnit.SECONDS);
      assertTrue(
          negotiatedExtension.get(5, TimeUnit.SECONDS).contains("permessage-deflate"),
          "gateway must negotiate permessage-deflate");
    }
  }
}
