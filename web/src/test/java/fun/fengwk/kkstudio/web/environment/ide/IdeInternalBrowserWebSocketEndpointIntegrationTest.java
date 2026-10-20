package fun.fengwk.kkstudio.web.environment.ide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonIdeStreamCodec;
import fun.fengwk.kkstudio.harness.environment.daemon.IdeAction;
import fun.fengwk.kkstudio.harness.environment.daemon.IdeCommand;
import fun.fengwk.kkstudio.harness.environment.daemon.IdeReply;
import fun.fengwk.kkstudio.harness.environment.server.EnvironmentDaemonServer;
import fun.fengwk.kkstudio.harness.environment.server.IdeRequest;
import fun.fengwk.kkstudio.harness.environment.server.IdeSessionIdentity;
import fun.fengwk.kkstudio.platform.environment.cluster.ClusterIdeOwnerRoute;
import fun.fengwk.kkstudio.platform.environment.cluster.ClusterInternalClient;
import fun.fengwk.kkstudio.platform.environment.cluster.ClusterInternalPaths;
import fun.fengwk.kkstudio.platform.environment.cluster.JdbcClusterNodeRegistry;
import fun.fengwk.kkstudio.web.WebPostgresTestSupport;

import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 内部浏览器 WS 端点（{@code /internal/environment-ide/{env}/ws?path=...}）在真实 Spring Boot + 内嵌 Tomcat
 * 上的网络契约。
 *
 * <p>用与生产完全相同的 {@link StandardWebSocketClient} 从外部拨入：HMAC 认证头 + 仅 IDE 会话 cookie（不带浏览器 Origin
 * 等凭据）必须能完成 握手；随后按原始 path 发 {@code OPEN_STREAM}、配对真实反向连接、把浏览器分段交给 Daemon。此测试把「非 owner → owner」这一跳从
 * A 的宿主里 单独隔离出来：它不依赖 A 的测试宿主，因此「握手是否真的完成」这一网络契约有独立证据。
 */
class IdeInternalBrowserWebSocketEndpointIntegrationTest extends WebPostgresTestSupport {

  private static final EnvironmentId ENVIRONMENT =
      EnvironmentId.parse("55555555-5555-5555-5555-555555555555");

  private static final String SECRET = "internal-browser-ws-secret";

  @DynamicPropertySource
  static void clusterProperties(DynamicPropertyRegistry registry) {
    registry.add("kk-studio.cluster.secret", () -> SECRET);
  }

  @LocalServerPort private int port;

  @MockitoBean private EnvironmentDaemonServer endpoint;

  @Autowired private IdeBrowserSessionRegistry sessions;

  @Autowired private IdeGatewayProperties gatewayProperties;

  @Autowired private ClusterInternalClient clusterInternalClient;

  @Autowired private JdbcClusterNodeRegistry nodeRegistry;

  /** 未认证的内部 WS 握手必须在任何 Daemon 副作用之前失败：不升级到 101，也不触发 OPEN_STREAM。 */
  @Test
  void rejectsUnAuthenticatedHandshakeBeforeDaemonSideEffect() throws Exception {
    registerReadyOwner();
    AtomicInteger opens = new AtomicInteger();
    when(endpoint.requestIde(eq(ENVIRONMENT), any()))
        .thenAnswer(
            invocation -> {
              opens.incrementAndGet();
              CompletableFuture<IdeReply> reply = new CompletableFuture<>();
              reply.complete(IdeReply.ok(null, null, null));
              return new IdeRequest(
                  new IdeSessionIdentity(ENVIRONMENT, "daemon-1", UUID.randomUUID()),
                  UUID.randomUUID(),
                  reply);
            });
    Map<String, String> forged =
        Map.of(
            "X-KK-Cluster-Target", UUID.randomUUID().toString(),
            "X-KK-Cluster-Timestamp", "1",
            "X-KK-Cluster-Nonce", "n",
            "X-KK-Cluster-Signature", "forged");

    String response =
        upgradeProbe(ClusterInternalPaths.dataWsPath(ENVIRONMENT, "/ws/echo"), forged);

    assertFalse(response.startsWith("HTTP/1.1 101"), "forged HMAC must not upgrade: " + response);
    assertEquals(0, opens.get(), "unauthenticated handshake must not reach the data plane");
  }

  /** 已认证但缺少 IDE 会话 cookie 的内部握手同样必须在握手前失败，绝不建立反向连接。 */
  @Test
  void rejectsHandshakeWithoutBrowserSessionCookie() throws Exception {
    registerReadyOwner();
    AtomicInteger opens = new AtomicInteger();
    when(endpoint.requestIde(eq(ENVIRONMENT), any()))
        .thenAnswer(
            invocation -> {
              opens.incrementAndGet();
              CompletableFuture<IdeReply> reply = new CompletableFuture<>();
              reply.complete(IdeReply.ok(null, null, null));
              return new IdeRequest(
                  new IdeSessionIdentity(ENVIRONMENT, "daemon-1", UUID.randomUUID()),
                  UUID.randomUUID(),
                  reply);
            });
    String path = ClusterInternalPaths.dataWsPath(ENVIRONMENT, "/ws/echo");
    Map<String, String> authHeaders =
        clusterInternalClient.signHeaders(
            "GET",
            path,
            ClusterIdeOwnerRoute.remote(
                nodeRegistry.localNodeId(), URI.create("http://localhost:" + port)));

    String response = upgradeProbe(path, authHeaders);

    assertFalse(
        response.startsWith("HTTP/1.1 101"),
        "handshake without an authorized browser session must not upgrade: " + response);
    assertEquals(0, opens.get(), "unauthorized handshake must not reach the data plane");
  }

  /** 用原始 socket 发一次真实的 HTTP/1.1 WebSocket 升级请求，返回原始响应（含状态行）。 */
  private String upgradeProbe(String path, Map<String, String> headers) throws Exception {
    StringBuilder request = new StringBuilder();
    request.append("GET ").append(path).append(" HTTP/1.1\r\n");
    request.append("Host: localhost:").append(port).append("\r\n");
    headers.forEach(
        (name, value) -> request.append(name).append(": ").append(value).append("\r\n"));
    request.append("Upgrade: websocket\r\n");
    request.append("Connection: Upgrade\r\n");
    request.append("Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n");
    request.append("Sec-WebSocket-Version: 13\r\n");
    request.append("\r\n");
    StringBuilder response = new StringBuilder();
    try (Socket socket = new Socket("localhost", port)) {
      socket.getOutputStream().write(request.toString().getBytes(StandardCharsets.US_ASCII));
      socket.getOutputStream().flush();
      socket.setSoTimeout(3_000);
      byte[] buffer = new byte[2048];
      int read = socket.getInputStream().read(buffer);
      while (read > 0) {
        response.append(new String(buffer, 0, read, StandardCharsets.ISO_8859_1));
        read = socket.getInputStream().read(buffer);
      }
    } catch (SocketTimeoutException ignored) {
      // 响应头读完后等待剩余数据超时。
    }
    return response.toString();
  }

  /** HMAC + IDE cookie 的内部握手必须完成，并且原始 path 被还原后驱动完整数据面。 */
  @Test
  void completesInternalHandshakeAndEchoesThroughDaemon() throws Exception {
    registerReadyOwner();
    IdeSessionIdentity identity =
        new IdeSessionIdentity(ENVIRONMENT, "daemon-1", UUID.randomUUID());
    when(endpoint.ideSessionIdentity(ENVIRONMENT)).thenReturn(Optional.of(identity));
    EchoDaemon daemon = new EchoDaemon();
    when(endpoint.requestIde(eq(ENVIRONMENT), any()))
        .thenAnswer(
            invocation -> {
              IdeCommand command = invocation.getArgument(1);
              assertEquals(IdeAction.OPEN_STREAM, command.action());
              daemon.dial(reverseUrl(command.ticket()));
              CompletableFuture<IdeReply> reply = new CompletableFuture<>();
              reply.complete(IdeReply.ok(null, null, null));
              return new IdeRequest(identity, UUID.randomUUID(), reply);
            });
    String cookie = openSession(identity, UUID.randomUUID().toString(), "/home/dev/project");

    String path = ClusterInternalPaths.dataWsPath(ENVIRONMENT, "/ws/echo");
    Map<String, String> authHeaders =
        clusterInternalClient.signHeaders(
            "GET",
            path,
            ClusterIdeOwnerRoute.remote(
                nodeRegistry.localNodeId(), URI.create("http://localhost:" + port)));
    WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
    headers.add("Cookie", gatewayProperties.requireSessionCookieName() + "=" + cookie);
    authHeaders.forEach(headers::add);

    BlockingQueue<String> echoed = new LinkedBlockingQueue<>();
    StandardWebSocketClient client = new StandardWebSocketClient();
    WebSocketSession session =
        client
            .execute(
                new AbstractWebSocketHandler() {
                  @Override
                  protected void handleTextMessage(
                      WebSocketSession webSocketSession, TextMessage message) {
                    echoed.add(message.getPayload());
                  }
                },
                headers,
                URI.create("ws://localhost:" + port + path))
            .get(20, TimeUnit.SECONDS);
    try {
      assertTrue(session.isOpen(), "internal handshake must complete");
      String payload = "internal-世界";
      session.sendMessage(new TextMessage(payload));
      assertTrue(daemon.sawMessage.await(20, TimeUnit.SECONDS), "daemon must see a WS segment");
      assertEquals(payload, echoed.poll(20, TimeUnit.SECONDS));
    } finally {
      session.close();
      daemon.awaitStopped();
    }
  }

  /** 建立本 Environment 的 READY owner 行：owner 为本节点实例，租约在未来。 */
  private void registerReadyOwner() throws Exception {
    try (Connection conn =
            DriverManager.getConnection(
                sharedJdbcUrl(), sharedJdbcUsername(), sharedJdbcPassword());
        Statement st = conn.createStatement()) {
      st.execute(
          "insert into environment (id, name, registration_token_hash) values ('"
              + ENVIRONMENT
              + "', 'internal-browser-ws-env', repeat('a', 64))"
              + " on conflict (id) do nothing");
      st.execute(
          "insert into environment_connection"
              + " (environment_id, owner_node_id, lease_token, status, runtime_info, last_seen_at,"
              + " lease_until)"
              + " values ('"
              + ENVIRONMENT
              + "', '"
              + nodeRegistry.localNodeId()
              + "', '"
              + UUID.randomUUID()
              + "', 'READY', '{}'::jsonb, statement_timestamp(),"
              + " statement_timestamp() + interval '60 seconds')"
              + " on conflict (environment_id) do update set owner_node_id = excluded.owner_node_id,"
              + " lease_token = excluded.lease_token, status = excluded.status,"
              + " runtime_info = excluded.runtime_info, last_seen_at = excluded.last_seen_at,"
              + " lease_until = excluded.lease_until");
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

  /** 回 {@code WS_READY} 后按分段 echo 的最小 Daemon peer。 */
  private final class EchoDaemon extends WebSocketListener {

    private final DaemonIdeStreamCodec codec = new DaemonIdeStreamCodec();
    private final CountDownLatch sawMessage = new CountDownLatch(1);
    private final Thread worker = new Thread(this::serve, "internal-browser-ws-daemon");

    private volatile WebSocket socket;
    private volatile OkHttpClient client;

    EchoDaemon() {
      worker.setDaemon(true);
    }

    void dial(String streamUrl) {
      client =
          new OkHttpClient.Builder()
              .dns(hostname -> List.of(InetAddress.getByName("127.0.0.1")))
              .build();
      client.newWebSocket(new Request.Builder().url(streamUrl).build(), this);
      worker.start();
    }

    private void serve() {
      try {
        sawMessage.await(30, TimeUnit.SECONDS);
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
        currentClient.dispatcher().executorService().shutdown();
        currentClient.connectionPool().evictAll();
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
      if (frame.length < 2) {
        return;
      }
      if (frame[0] == 4 || frame[0] == 5) {
        sawMessage.countDown();
        webSocket.send(ByteString.of(frame));
        webSocket.send(ByteString.of(codec.encodeAck(frame.length - 2)));
      }
    }
  }

  /** bootstrap 消费的真实前置：先由真实 OPEN_VIEW 授权视图，再消费一次性票据换 cookie。 */
  private String openSession(IdeSessionIdentity identity, String viewId, String workspacePath) {
    sessions.viewOpened(identity, viewId, workspacePath);
    return sessions.open(identity, viewId, workspacePath);
  }
}
