package fun.fengwk.kkstudio.web.environment.ide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;
import org.apache.catalina.Context;
import org.apache.catalina.startup.Tomcat;
import org.apache.tomcat.websocket.server.WsSci;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.server.support.DefaultHandshakeHandler;
import org.springframework.web.socket.server.support.WebSocketHttpRequestHandler;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonIdeStreamCodec;
import fun.fengwk.kkstudio.harness.environment.daemon.IdeAction;
import fun.fengwk.kkstudio.harness.environment.daemon.IdeCommand;
import fun.fengwk.kkstudio.harness.environment.daemon.IdeReply;
import fun.fengwk.kkstudio.harness.environment.daemon.IdeStreamCreditWindow;
import fun.fengwk.kkstudio.harness.environment.server.EnvironmentDaemonServer;
import fun.fengwk.kkstudio.harness.environment.server.IdeCommandTransport;
import fun.fengwk.kkstudio.harness.environment.server.IdeRequest;
import fun.fengwk.kkstudio.harness.environment.server.IdeSessionIdentity;
import fun.fengwk.kkstudio.harness.environment.server.IdeStreamPairingRegistry;
import fun.fengwk.kkstudio.harness.environment.server.InMemoryIdeStreamTicketService;
import fun.fengwk.kkstudio.platform.environment.cluster.ClusterIdeOwnerRoute;
import fun.fengwk.kkstudio.platform.environment.cluster.ClusterInternalAuthentication;
import fun.fengwk.kkstudio.platform.environment.cluster.ClusterInternalClient;
import fun.fengwk.kkstudio.platform.environment.cluster.ClusterNodeRegistry;
import fun.fengwk.kkstudio.platform.environment.cluster.HmacClusterInternalAuthentication;
import fun.fengwk.kkstudio.platform.environment.cluster.JdbcClusterNodeRegistry;
import fun.fengwk.kkstudio.web.WebPostgresTestSupport;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 跨节点反向数据面的一跳 A/B 真实集成：Node 落在负载均衡后的非 owner 节点 A，A 用内部 HMAC 单跳转发给 READY owner B。
 *
 * <p>B 是真实的应用上下文（PostgreSQL 控制面 + 真实 servlet 过滤器 + 真实反向端点）；A 是独立的内嵌 Tomcat，只承载公开 stream endpoint 与
 * {@link IdeOwnerStreamForwarder}，通过真实 HTTP/WS 一跳连到 B 的内部反向端点。这里验证：20 MiB HTTP 响应体经 A→B
 * 完整传输、hop-by-hop 头被剥离、PG 只承载 registry 控制数据。
 */
class IdeCrossNodeReverseStreamIntegrationTest extends WebPostgresTestSupport {

  private static final EnvironmentId ENVIRONMENT =
      EnvironmentId.parse("33333333-3333-3333-3333-333333333333");

  private static final String SECRET = "cross-node-test-secret";

  private static final int BODY_BYTES = 20 * 1024 * 1024;

  private static Tomcat forwarderNode;

  @DynamicPropertySource
  static void clusterProperties(DynamicPropertyRegistry registry) {
    registry.add("kk-studio.cluster.secret", () -> SECRET);
    registry.add(
        "kk-studio.harness.ide-gateway.stream-url-template",
        () -> "wss://studio-lb" + IdeGatewayProperties.STREAM_PATH);
    registry.add("kk-studio.harness.ide.origin-template", () -> "https://{environmentId}.ide.test");
  }

  @LocalServerPort private int ownerPort;

  @MockitoBean private EnvironmentDaemonServer endpoint;

  @Autowired private IdeBrowserSessionRegistry sessions;

  @Autowired private IdeGatewayProperties gatewayProperties;

  @Autowired private JdbcClusterNodeRegistry nodeRegistry;

  @AfterAll
  static void stopForwarder() throws Exception {
    if (forwarderNode != null) {
      forwarderNode.stop();
      forwarderNode.destroy();
      forwarderNode = null;
    }
  }

  /** Node 落在非 owner 节点 A 时，A 单跳转发给 owner B，20 MiB 响应体完整回到浏览器。 */
  @Test
  void forwardsTwentyMiBThroughNonOwnerNode() throws Exception {
    int forwarderPort = startForwarderNode(nodeRegistry.localNodeId(), ownerPort);
    registerReadyOwner();
    IdeSessionIdentity identity =
        new IdeSessionIdentity(ENVIRONMENT, "daemon-1", UUID.randomUUID());
    when(endpoint.ideSessionIdentity(ENVIRONMENT)).thenReturn(Optional.of(identity));
    NodePeer node = new NodePeer(forwarderPort, ENVIRONMENT);
    when(endpoint.requestIde(eq(ENVIRONMENT), any()))
        .thenAnswer(
            invocation -> {
              IdeCommand command = invocation.getArgument(1);
              assertEquals(IdeAction.OPEN_STREAM, command.action());
              node.dial(command.streamUrl());
              CompletableFuture<IdeReply> reply = new CompletableFuture<>();
              reply.complete(IdeReply.ok(null, null, null));
              return new IdeRequest(identity, UUID.randomUUID(), reply);
            });

    String cookie = openSession(identity, UUID.randomUUID().toString(), "/home/dev/project");
    OkHttpClient browser = localhostClient();
    try {
      Response response =
          browser
              .newCall(
                  new Request.Builder()
                      .url(
                          "http://"
                              + ENVIRONMENT
                              + ".ide.test:"
                              + ownerPort
                              + "/workspace/cross-node.txt")
                      .header("Cookie", cookieHeader(cookie))
                      .build())
              .execute();
      byte[] body = response.body().bytes();
      assertEquals(200, response.code());
      assertEquals(BODY_BYTES, body.length);
      assertEquals(expectedByte(BODY_BYTES - 1), body[BODY_BYTES - 1]);
      assertEquals(expectedByte(777), body[777]);
      assertNull(response.header("Proxy-Authenticate"), "hop-by-hop header must be stripped");
      assertTrue(node.completed.await(10, TimeUnit.SECONDS), "node peer must finish the response");
      assertControlOnlyDatabase();
    } finally {
      shutdown(browser);
      node.awaitStopped();
    }
  }

  private static byte expectedByte(int index) {
    return (byte) (index * 31 + 7);
  }

  /** 建立本 Environment 的 READY owner 行：owner 为 B 的节点实例，租约在未来。 */
  private void registerReadyOwner() throws Exception {
    try (Connection conn =
            DriverManager.getConnection(
                sharedJdbcUrl(), sharedJdbcUsername(), sharedJdbcPassword());
        Statement st = conn.createStatement()) {
      st.execute(
          "insert into environment (id, name, registration_token) values ('"
              + ENVIRONMENT
              + "', 'cross-node-env', 'cross-node-token') on conflict (id) do nothing");
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

  /** PG 只承载 registry 控制数据：只有一行连接租约与一行节点注册，且 runtime_info 极小。 */
  private void assertControlOnlyDatabase() throws Exception {
    try (Connection conn =
            DriverManager.getConnection(
                sharedJdbcUrl(), sharedJdbcUsername(), sharedJdbcPassword());
        Statement st = conn.createStatement()) {
      try (var rs = st.executeQuery("select count(*) from environment_connection")) {
        assertTrue(rs.next());
        assertEquals(1L, rs.getLong(1));
      }
      try (var rs =
          st.executeQuery(
              "select coalesce(max(octet_length(runtime_info::text)), 0),"
                  + " coalesce(max(octet_length(skill_state::text)), 0),"
                  + " coalesce(max(octet_length(recent_events::text)), 0)"
                  + " from environment_connection")) {
        assertTrue(rs.next());
        assertTrue(rs.getInt(1) < 4096, "connection runtime_info must stay control-sized");
        assertTrue(rs.getInt(2) < 4096, "connection skill_state must stay control-sized");
        assertTrue(rs.getInt(3) < 16 * 1024, "connection recent_events must stay control-sized");
      }
    }
  }

  /** 启动非 owner 节点 A：独立内嵌 Tomcat + 真实反向端点与转发器，仅公开 stream endpoint。 */
  private static synchronized int startForwarderNode(UUID ownerNodeId, int ownerPort)
      throws Exception {
    if (forwarderNode != null) {
      return forwarderNode.getConnector().getLocalPort();
    }
    int port = freePort();
    ClusterInternalAuthentication authentication =
        new HmacClusterInternalAuthentication(
            SECRET, ownerNodeId, Clock.systemUTC(), Duration.ofSeconds(30), 1024);
    ClusterInternalClient client =
        new ClusterInternalClient(HttpClient.newHttpClient(), new ObjectMapper(), authentication);
    ClusterNodeRegistry registry =
        environmentId ->
            Optional.of(
                ClusterIdeOwnerRoute.remote(
                    ownerNodeId, URI.create("http://localhost:" + ownerPort)));
    IdeReverseStreamWebSocketHandler handler =
        new IdeReverseStreamWebSocketHandler(
            new InMemoryIdeStreamTicketService(),
            new IdeStreamPairingRegistry<>(),
            unavailableTransport(),
            registry,
            new IdeOwnerStreamForwarder(client, new StandardWebSocketClient()));
    DefaultHandshakeHandler handshakeHandler = new DefaultHandshakeHandler();

    Tomcat tomcat = new Tomcat();
    tomcat.setPort(port);
    tomcat.setBaseDir(System.getProperty("java.io.tmpdir"));
    tomcat.getConnector();
    Context context = tomcat.addContext("", new File(".").getAbsolutePath());
    context.addServletContainerInitializer(new WsSci(), null);
    Tomcat.addServlet(
        context,
        "ideReverseStream",
        new HttpServlet() {
          @Override
          protected void service(HttpServletRequest request, HttpServletResponse response)
              throws ServletException, IOException {
            handshakeHandler.setServletContext(request.getServletContext());
            handshakeHandler.start();
            new WebSocketHttpRequestHandler(handler, handshakeHandler)
                .handleRequest(request, response);
          }
        });
    context.addServletMappingDecoded(IdeGatewayProperties.STREAM_PATH + "/*", "ideReverseStream");
    tomcat.start();
    // 帧上限一律由生产代码按会话显式设置（反向端点与 owner 转发客户端），不再用容器级整包缓冲兜底。
    forwarderNode = tomcat;
    return tomcat.getConnector().getLocalPort();
  }

  private static IdeCommandTransport unavailableTransport() {
    return new IdeCommandTransport() {
      @Override
      public IdeRequest requestIde(EnvironmentId environmentId, IdeCommand command) {
        throw new IllegalStateException("non-owner node must not issue IDE commands");
      }

      @Override
      public void abandonIde(UUID invocationId) {}

      @Override
      public Optional<IdeSessionIdentity> ideSessionIdentity(EnvironmentId environmentId) {
        return Optional.empty();
      }
    };
  }

  private static int freePort() throws Exception {
    try (ServerSocket socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    }
  }

  private String cookieHeader(String cookie) {
    return gatewayProperties.requireSessionCookieName() + "=" + cookie;
  }

  private static OkHttpClient localhostClient() {
    return new OkHttpClient.Builder()
        .dns(hostname -> List.of(InetAddress.getByName("127.0.0.1")))
        .build();
  }

  private static void shutdown(OkHttpClient client) {
    client.dispatcher().executorService().shutdown();
    client.connectionPool().evictAll();
  }

  /** 充当 Daemon 的 Node peer：把受控 streamUrl 重写到 A 的公网地址后按 credit 回 20 MiB。 */
  private static final class NodePeer extends WebSocketListener {

    private final DaemonIdeStreamCodec codec = new DaemonIdeStreamCodec();
    private final IdeStreamCreditWindow window = new IdeStreamCreditWindow();
    private final BlockingQueue<byte[]> inbound = new LinkedBlockingQueue<>();
    private final CountDownLatch completed = new CountDownLatch(1);
    private final int forwarderPort;
    private final EnvironmentId environmentId;
    private final Thread worker = new Thread(this::serve, "cross-node-peer");

    private volatile WebSocket socket;
    private volatile OkHttpClient client;

    NodePeer(int forwarderPort, EnvironmentId environmentId) {
      this.forwarderPort = forwarderPort;
      this.environmentId = environmentId;
      this.worker.setDaemon(true);
    }

    void dial(String streamUrl) {
      URI original = URI.create(streamUrl);
      String ticket = original.getPath().substring(original.getPath().lastIndexOf('/') + 1);
      String target =
          "ws://localhost:"
              + forwarderPort
              + IdeGatewayProperties.STREAM_PATH
              + "/"
              + ticket
              + "?environment="
              + environmentId;
      client = new OkHttpClient();
      client.newWebSocket(new Request.Builder().url(target).build(), this);
      worker.start();
    }

    private void serve() {
      try {
        byte[] frame = inbound.poll(30, TimeUnit.SECONDS);
        if (frame == null || frame[0] != 2) {
          return;
        }
        socket.send(
            "{\"type\":\"HTTP_RESPONSE\",\"status\":200,\"headers\":"
                + "[[\"content-type\",\"text/plain\"],[\"content-length\",\""
                + BODY_BYTES
                + "\"],[\"proxy-authenticate\",\"Basic realm=hop\"]]}");
        byte[] chunk = new byte[DaemonIdeStreamCodec.MAX_PAYLOAD_BYTES];
        int sent = 0;
        while (sent < BODY_BYTES) {
          int size = Math.min(chunk.length, BODY_BYTES - sent);
          for (int i = 0; i < size; i++) {
            chunk[i] = expectedByte(sent + i);
          }
          byte[] payload = new byte[size];
          System.arraycopy(chunk, 0, payload, 0, size);
          while (!window.canSend(size)) {
            byte[] ack = inbound.poll(30, TimeUnit.SECONDS);
            if (ack == null || ack[0] != 3) {
              return;
            }
            window.onAck(unsignedInt(ack));
          }
          window.onSend(size);
          socket.send(ByteString.of(codec.encodeHttpBody(payload)));
          sent += size;
        }
        socket.send(ByteString.of(codec.encodeHttpEnd()));
        completed.countDown();
      } catch (InterruptedException error) {
        Thread.currentThread().interrupt();
      }
    }

    private static long unsignedInt(byte[] frame) {
      return ((long) (frame[1] & 0xFF) << 24)
          | ((long) (frame[2] & 0xFF) << 16)
          | ((long) (frame[3] & 0xFF) << 8)
          | (long) (frame[4] & 0xFF);
    }

    void awaitStopped() throws InterruptedException {
      worker.join(10_000);
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
    }

    @Override
    public void onMessage(WebSocket webSocket, ByteString bytes) {
      inbound.add(bytes.toByteArray());
    }
  }

  /** bootstrap 消费的真实前置：先由真实 OPEN_VIEW 授权视图，再消费一次性票据换 cookie。 */
  private String openSession(IdeSessionIdentity identity, String viewId, String workspacePath) {
    sessions.viewOpened(identity, viewId, workspacePath);
    return sessions.open(identity, viewId, workspacePath);
  }
}
