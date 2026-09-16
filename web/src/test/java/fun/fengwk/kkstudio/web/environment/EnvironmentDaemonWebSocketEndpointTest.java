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
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Daemon WebSocket 适配器的网络契约；web 层不保存任何协议状态。
 *
 * <p>客户端使用 OkHttp 是因为 JDK {@code HttpClient} 的 WebSocket 不支持扩展协商，无法满足端点强制的 {@code
 * permessage-deflate} 要求。
 */
class EnvironmentDaemonWebSocketEndpointTest extends WebPostgresTestSupport {

  @LocalServerPort private int port;

  /** 用 mock 替换会话核心端点：本测试只验证 WebSocket 适配器的字节桥接，不启动真实协议状态机； 同一 DaemonEndpoint 类型在组合根内唯一。 */
  @MockitoBean private EnvironmentDaemonServer endpoint;

  /** 端点在两个方向上桥接完整文本帧，并在连接关闭时通知会话核心。 */
  @Test
  void bridgesDaemonConnectionFramesAndClose() throws Exception {
    BlockingQueue<String> received = new LinkedBlockingQueue<>();
    QueueingListener listener = new QueueingListener(received);
    OkHttpClient client = new OkHttpClient();
    try {
      WebSocket socket =
          client.newWebSocket(
              new Request.Builder().url(endpointUri().toASCIIString()).build(), listener);

      ArgumentCaptor<DaemonChannel> connectionCaptor = ArgumentCaptor.forClass(DaemonChannel.class);
      verify(endpoint, timeout(15_000)).open(connectionCaptor.capture());
      DaemonChannel connection = connectionCaptor.getValue();
      assertNotNull(connection);
      assertTrue(connection.isOpen());

      assertTrue(socket.send("daemon-frame"));
      verify(endpoint, timeout(15_000)).receive(eq(connection.connectionId()), eq("daemon-frame"));

      assertEquals(DaemonOfferResult.ACCEPTED, connection.offerText("gateway-frame"));
      assertEquals("gateway-frame", received.poll(10, TimeUnit.SECONDS));

      socket.close(1000, "test complete");
      listener.awaitClose();
      verify(endpoint, timeout(15_000)).close(connection.connectionId());
    } finally {
      client.dispatcher().executorService().shutdown();
      client.connectionPool().evictAll();
    }
  }

  private URI endpointUri() {
    return URI.create("ws://localhost:" + port + EnvironmentDaemonWebSocketHandler.PATH);
  }

  /** 收集完整文本帧并记录关闭事件，验证端点确实协商了 permessage-deflate。 */
  private static final class QueueingListener extends WebSocketListener {

    private final BlockingQueue<String> messages;
    private final CompletableFuture<String> negotiatedExtension = new CompletableFuture<>();
    private final CompletableFuture<Void> closed = new CompletableFuture<>();

    private QueueingListener(BlockingQueue<String> messages) {
      this.messages = messages;
    }

    @Override
    public void onOpen(WebSocket webSocket, Response response) {
      String extensions = response.header("Sec-WebSocket-Extensions");
      negotiatedExtension.complete(extensions == null ? "" : extensions);
    }

    @Override
    public void onMessage(WebSocket webSocket, String text) {
      messages.add(text);
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

    private void awaitClose() throws Exception {
      closed.get(5, TimeUnit.SECONDS);
      assertTrue(
          negotiatedExtension.get(5, TimeUnit.SECONDS).contains("permessage-deflate"),
          "gateway must negotiate permessage-deflate");
    }
  }
}
