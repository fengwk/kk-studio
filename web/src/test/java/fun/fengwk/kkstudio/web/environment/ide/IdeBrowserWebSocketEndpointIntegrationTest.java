package fun.fengwk.kkstudio.web.environment.ide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.environment.server.DaemonConnectionIdentity;
import fun.fengwk.kkstudio.ide.IdeCommandTransport;
import fun.fengwk.kkstudio.ide.IdeRequest;
import fun.fengwk.kkstudio.ide.protocol.DaemonIdeStreamCodec;
import fun.fengwk.kkstudio.ide.protocol.IdeAction;
import fun.fengwk.kkstudio.ide.protocol.IdeCommand;
import fun.fengwk.kkstudio.ide.protocol.IdeReply;
import fun.fengwk.kkstudio.ide.protocol.IdeStreamCreditWindow;
import fun.fengwk.kkstudio.ide.protocol.IdeStreamMode;
import fun.fengwk.kkstudio.web.WebPostgresTestSupport;

import java.net.InetAddress;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 浏览器原生 WebSocket 数据面的端到端网络契约：真实 Tomcat 上由真实反向 WS Daemon peer 完成 UTF-8 echo。
 *
 * <p>覆盖 HTTP 数据面之外的 WS 语义：浏览器 WS 升级经 host guard 与 cookie 校验后，由 {@code OPEN_STREAM(WS)}
 * 与反向连接配对，Daemon 回 {@code WS_READY} 后按分段透传；浏览器的一条消息被重组为单条消息返回。
 */
class IdeBrowserWebSocketEndpointIntegrationTest extends WebPostgresTestSupport {

  private static final EnvironmentId ENVIRONMENT =
      EnvironmentId.parse("22222222-2222-2222-2222-222222222222");

  @LocalServerPort private int port;

  @MockitoBean private IdeCommandTransport endpoint;

  @Autowired private IdeBrowserSessionRegistry sessions;

  @Autowired private IdeGatewayProperties gatewayProperties;

  /** 浏览器 WS 消息经反向连接 echo 回来，且 UTF-8 文本语义保持。 */
  @Test
  void echoesBrowserWebSocketMessageThroughReverseStream() throws Exception {
    DaemonConnectionIdentity identity =
        new DaemonConnectionIdentity(ENVIRONMENT, "daemon-1", UUID.randomUUID());
    when(endpoint.connectionIdentity(ENVIRONMENT)).thenReturn(Optional.of(identity));
    EchoDaemon daemon = new EchoDaemon();
    when(endpoint.requestIde(eq(ENVIRONMENT), any()))
        .thenAnswer(
            invocation -> {
              IdeCommand command = invocation.getArgument(1);
              assertEquals(IdeAction.OPEN_STREAM, command.action());
              assertEquals(IdeStreamMode.WS, command.mode());
              daemon.dial(reverseUrl(command.ticket()));
              CompletableFuture<IdeReply> reply = new CompletableFuture<>();
              reply.complete(IdeReply.ok(null, null, null));
              return new IdeRequest(identity, UUID.randomUUID(), reply);
            });

    String cookie = openSession(identity, UUID.randomUUID().toString(), "/home/dev/project");
    OkHttpClient browser = localhostClient();
    try {
      BlockingQueue<String> echoed = new LinkedBlockingQueue<>();
      CountDownLatch opened = new CountDownLatch(1);
      WebSocket socket =
          browser.newWebSocket(
              new Request.Builder()
                  .url("ws://localhost:" + port + "/environment-ide/" + ENVIRONMENT + "/ws/echo")
                  .header("Cookie", cookieHeader(cookie))
                  .header("Origin", "http://localhost:" + port)
                  .build(),
              new WebSocketListener() {
                @Override
                public void onOpen(WebSocket webSocket, Response response) {
                  opened.countDown();
                }

                @Override
                public void onMessage(WebSocket webSocket, String text) {
                  echoed.add(text);
                }
              });
      assertTrue(opened.await(15, TimeUnit.SECONDS), "browser websocket must be established");
      String payload = "hello-ide-世界";
      assertTrue(socket.send(payload));
      assertTrue(daemon.sawMessage.await(15, TimeUnit.SECONDS), "daemon must see a WS segment");
      assertEquals(payload, echoed.poll(15, TimeUnit.SECONDS));
      socket.close(1000, "done");
    } finally {
      shutdown(browser);
      daemon.awaitStopped();
    }
  }

  private String reverseUrl(String ticket) {
    return "ws://localhost:"
        + port
        + IdeGatewayProperties.STREAM_PATH
        + "/"
        + ticket
        + "?environment="
        + ENVIRONMENT;
  }

  private String cookieHeader(String cookie) {
    return gatewayProperties.requireSessionCookieName() + "=" + cookie;
  }

  private OkHttpClient localhostClient() {
    return new OkHttpClient.Builder()
        .dns(hostname -> List.of(InetAddress.getByName("127.0.0.1")))
        .build();
  }

  private static void shutdown(OkHttpClient client) {
    client.dispatcher().executorService().shutdown();
    client.connectionPool().evictAll();
  }

  /** 回 {@code WS_READY} 后按分段 echo 的最小 Daemon peer。 */
  private final class EchoDaemon extends WebSocketListener {

    private final DaemonIdeStreamCodec codec = new DaemonIdeStreamCodec();
    private final CountDownLatch sawMessage = new CountDownLatch(1);
    private final Thread worker = new Thread(this::serve, "fake-ws-ide-daemon");

    private volatile WebSocket socket;
    private volatile OkHttpClient client;

    EchoDaemon() {
      worker.setDaemon(true);
    }

    void dial(String streamUrl) {
      client = new OkHttpClient();
      client.newWebSocket(new Request.Builder().url(streamUrl).build(), this);
      worker.start();
    }

    private void serve() {
      try {
        // 浏览器消息到达后由 onMessage 直接回显；此处仅等待其发生。
        sawMessage.await(20, TimeUnit.SECONDS);
      } catch (InterruptedException error) {
        Thread.currentThread().interrupt();
      }
    }

    void awaitStopped() throws InterruptedException {
      worker.join(5_000);
      WebSocket current = socket;
      if (current != null) {
        current.cancel();
      }
      OkHttpClient currentClient = client;
      if (currentClient != null) {
        shutdown(currentClient);
      }
    }

    @Override
    public void onOpen(WebSocket webSocket, Response response) {
      this.socket = webSocket;
      webSocket.send("{\"type\":\"WS_READY\"}");
    }

    @Override
    public void onMessage(WebSocket webSocket, ByteString bytes) {
      byte[] frame = bytes.toByteArray();
      if (frame.length == 0) {
        return;
      }
      if (frame[0] == 4 || frame[0] == 5) {
        // 浏览器 → Daemon 分段：原样 echo 回浏览器，并 ACK 已消费字节。
        sawMessage.countDown();
        webSocket.send(ByteString.of(frame));
        webSocket.send(
            ByteString.of(codec.encodeAck(IdeStreamCreditWindow.frameCost(frame.length - 2))));
      } else if (frame[0] == 3) {
        // 网关对 echo 字节的 ACK：无需处理。
      }
    }
  }

  /** bootstrap 消费的真实前置：先由真实 OPEN_VIEW 授权视图，再消费一次性票据换 cookie。 */
  private String openSession(
      DaemonConnectionIdentity identity, String viewId, String workspacePath) {
    sessions.viewOpened(identity, viewId, workspacePath);
    return sessions.open(identity, viewId, workspacePath);
  }
}
