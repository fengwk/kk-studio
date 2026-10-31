package fun.fengwk.kkstudio.web.environment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.ee11.servlet.ServletContextHandler;
import org.eclipse.jetty.ee11.websocket.server.config.JettyWebSocketServletContainerInitializer;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.io.ArrayByteBufferPool;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.thread.ScheduledExecutorScheduler;
import org.eclipse.jetty.util.thread.Scheduler;
import org.eclipse.jetty.websocket.core.CloseStatus;
import org.eclipse.jetty.websocket.core.CoreSession;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.FrameHandler;
import org.eclipse.jetty.websocket.core.OpCode;
import org.eclipse.jetty.websocket.core.client.CoreClientUpgradeRequest;
import org.eclipse.jetty.websocket.core.client.WebSocketCoreClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.environment.protocol.Envelope;
import fun.fengwk.kkstudio.harness.environment.server.DaemonAdmission;
import fun.fengwk.kkstudio.harness.environment.server.DaemonControlChannel;
import fun.fengwk.kkstudio.harness.environment.server.DaemonHandshake;
import fun.fengwk.kkstudio.harness.environment.server.DaemonSession;
import fun.fengwk.kkstudio.harness.environment.server.DaemonSessions;
import fun.fengwk.kkstudio.harness.environment.transport.WebSocketEndPoint;
import fun.fengwk.kkstudio.harness.environment.tunnel.EnvironmentTunnel;
import fun.fengwk.kkstudio.harness.environment.tunnel.TunnelExchange;
import fun.fengwk.kkstudio.harness.environment.tunnel.TunnelHttpRequest;
import fun.fengwk.kkstudio.harness.environment.tunnel.TunnelStream;
import fun.fengwk.kkstudio.platform.environment.update.DaemonReleaseProvider;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

class EnvironmentDaemonWebSocketIntegrationTest {

  private static final String TEST_TOKEN = "test-secret-token-123";
  private static final String TEST_INSTANCE = "3f2a9c1e-7b4d-4e2a-9c6f-1a2b3c4d5e60";
  private static final DaemonAdmission.Admitted ADMITTED =
      new DaemonAdmission.Admitted(
          EnvironmentId.parse("3f2a9c1e-7b4d-4e2a-9c6f-1a2b3c4d5e6f"), TEST_INSTANCE);

  private Server server;
  private ServerConnector connector;
  private Scheduler scheduler;
  private ExecutorService executor;
  private ByteBufferPool bufferPool;
  private WebSocketCoreClient wsClient;
  private TestSessions testSessions;

  @BeforeEach
  void setUp() throws Exception {
    scheduler = new ScheduledExecutorScheduler("test-transport-", false);
    scheduler.start();
    executor = Executors.newVirtualThreadPerTaskExecutor();
    bufferPool = new ArrayByteBufferPool();
    testSessions = new TestSessions();

    EnvironmentDaemonAdmissionController controller =
        new EnvironmentDaemonAdmissionController(
            testSessions,
            new StaticListableBeanFactory().getBeanProvider(DaemonReleaseProvider.class),
            scheduler,
            executor,
            bufferPool);

    server = new Server();
    connector = new ServerConnector(server);
    connector.setPort(0);
    server.addConnector(connector);

    ServletContextHandler context = new ServletContextHandler("/");
    JettyWebSocketServletContainerInitializer.configure(context, null);

    context.addServlet(
        new HttpServlet() {
          @Override
          protected void doGet(HttpServletRequest req, HttpServletResponse resp)
              throws IOException {
            controller.handle(req, resp);
          }
        },
        EnvironmentDaemonAdmissionController.PATH);

    server.setHandler(context);
    server.start();

    wsClient = new WebSocketCoreClient();
    wsClient.start();
  }

  @AfterEach
  void tearDown() throws Exception {
    if (wsClient != null) {
      wsClient.stop();
    }
    if (server != null) {
      server.stop();
    }
    if (scheduler != null) {
      scheduler.stop();
    }
    if (executor != null) {
      executor.shutdownNow();
    }
  }

  private URI getEndpointUri() {
    return URI.create(
        "ws://127.0.0.1:" + connector.getLocalPort() + EnvironmentDaemonAdmissionController.PATH);
  }

  @Test
  void nonUpgradeRequestReturnsBadRequest() throws Exception {
    HttpClient client = HttpClient.newHttpClient();
    HttpRequest request =
        HttpRequest.newBuilder()
            .uri(
                URI.create(
                    "http://127.0.0.1:"
                        + connector.getLocalPort()
                        + EnvironmentDaemonAdmissionController.PATH))
            .GET()
            .build();
    HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
    assertEquals(400, response.statusCode());
  }

  @Test
  void textFrameClosesWith1003() throws Exception {
    CompletableFuture<CloseStatus> closedFuture = new CompletableFuture<>();
    CompletableFuture<CoreSession> openFuture = new CompletableFuture<>();

    CoreClientUpgradeRequest upgradeRequest =
        CoreClientUpgradeRequest.from(
            wsClient,
            getEndpointUri(),
            new FrameHandler() {
              private volatile CoreSession coreSession;

              @Override
              public void onOpen(CoreSession session, Callback callback) {
                this.coreSession = session;
                openFuture.complete(session);
                session.demand();
                callback.succeeded();
              }

              @Override
              public void onFrame(Frame frame, Callback callback) {
                if (coreSession != null) {
                  coreSession.demand();
                }
                callback.succeeded();
              }

              @Override
              public void onError(Throwable cause, Callback callback) {
                callback.succeeded();
              }

              @Override
              public void onClosed(CloseStatus closeStatus, Callback callback) {
                closedFuture.complete(closeStatus);
                callback.succeeded();
              }
            });

    upgradeRequest.headers(
        h -> {
          h.put("Authorization", "Bearer " + TEST_TOKEN);
          h.put("KK-Daemon-Instance", TEST_INSTANCE);
          h.put("KK-Daemon-Protocol", "1");
        });

    upgradeRequest.sendAsync();
    CoreSession session = openFuture.get(5, TimeUnit.SECONDS);
    assertNotNull(session);

    // 发送一条文本帧
    session.sendFrame(
        new Frame(OpCode.TEXT, ByteBuffer.wrap("text-message".getBytes(StandardCharsets.UTF_8))),
        Callback.NOOP,
        false);

    CloseStatus closeStatus = closedFuture.get(5, TimeUnit.SECONDS);
    assertEquals(1003, closeStatus.getCode());
  }

  @Test
  void abandonTimerTriggersAfterTenSecondsOfInactivity() throws Exception {
    CompletableFuture<CloseStatus> closedFuture = new CompletableFuture<>();
    CompletableFuture<CoreSession> openFuture = new CompletableFuture<>();

    CoreClientUpgradeRequest upgradeRequest =
        CoreClientUpgradeRequest.from(
            wsClient,
            getEndpointUri(),
            new FrameHandler() {
              private volatile CoreSession coreSession;

              @Override
              public void onOpen(CoreSession session, Callback callback) {
                this.coreSession = session;
                openFuture.complete(session);
                session.demand();
                callback.succeeded();
              }

              @Override
              public void onFrame(Frame frame, Callback callback) {
                if (coreSession != null) {
                  coreSession.demand();
                }
                callback.succeeded();
              }

              @Override
              public void onError(Throwable cause, Callback callback) {
                callback.succeeded();
              }

              @Override
              public void onClosed(CloseStatus closeStatus, Callback callback) {
                closedFuture.complete(closeStatus);
                callback.succeeded();
              }
            });

    upgradeRequest.headers(
        h -> {
          h.put("Authorization", "Bearer " + TEST_TOKEN);
          h.put("KK-Daemon-Instance", TEST_INSTANCE);
          h.put("KK-Daemon-Protocol", "1");
        });

    upgradeRequest.sendAsync();
    CoreSession session = openFuture.get(5, TimeUnit.SECONDS);
    assertNotNull(session);

    // 客户端保持静默，等待 10s abandon timer 触发
    CloseStatus closeStatus = closedFuture.get(15, TimeUnit.SECONDS);
    assertEquals(1000, closeStatus.getCode());
    assertTrue(testSessions.abandoned.contains(ADMITTED));
  }

  @Test
  void fullTunnelEndToEndWithSettingsReadyHttpAndExtendedConnect() throws Exception {
    CompletableFuture<CoreSession> openFuture = new CompletableFuture<>();
    DaemonClientBridge bridge = new DaemonClientBridge(openFuture, connector, scheduler);

    CoreClientUpgradeRequest upgradeRequest =
        CoreClientUpgradeRequest.from(wsClient, getEndpointUri(), bridge);
    upgradeRequest.headers(
        h -> {
          h.put("Authorization", "Bearer " + TEST_TOKEN);
          h.put("KK-Daemon-Instance", TEST_INSTANCE);
          h.put("KK-Daemon-Protocol", "1");
        });

    upgradeRequest.sendAsync();
    CoreSession session = openFuture.get(5, TimeUnit.SECONDS);
    assertNotNull(session);

    // 等待握手完成并触发 DaemonSessions.open
    TestSessions.OpenResult openResult = testSessions.opened.get(10, TimeUnit.SECONDS);
    assertNotNull(openResult);
    assertEquals(ADMITTED, openResult.admitted());
    EnvironmentTunnel tunnel = openResult.tunnel();
    assertNotNull(tunnel);

    // 1. 测试 HTTP 隧道
    TunnelHttpRequest httpReq =
        new TunnelHttpRequest("GET", "/test-tunnel", Collections.emptyMap());
    TunnelExchange exchange = tunnel.openHttp(httpReq);
    TunnelExchange.Response httpResp =
        exchange.responseHeaders().toCompletableFuture().get(10, TimeUnit.SECONDS);
    assertEquals(HttpStatus.OK_200, httpResp.status());
    ByteBuffer bodyBuf = exchange.stream().read().toCompletableFuture().get(10, TimeUnit.SECONDS);
    byte[] body = new byte[bodyBuf.remaining()];
    bodyBuf.get(body);
    assertEquals("hello-from-daemon", new String(body, StandardCharsets.UTF_8));
    exchange.close();

    // 2. 测试 extended CONNECT WebSocket 隧道
    TunnelHttpRequest wsReq = new TunnelHttpRequest("GET", "/ws-echo", Collections.emptyMap());
    TunnelExchange wsExchange = tunnel.openWebSocket(wsReq);
    TunnelExchange.Response wsResp =
        wsExchange.responseHeaders().toCompletableFuture().get(10, TimeUnit.SECONDS);
    assertEquals(HttpStatus.OK_200, wsResp.status());
    TunnelStream wsStream = wsExchange.stream();

    byte[] testBytes = "extended-connect-payload".getBytes(StandardCharsets.UTF_8);
    wsStream.write(ByteBuffer.wrap(testBytes)).toCompletableFuture().get(5, TimeUnit.SECONDS);

    ByteBuffer echoBuf = wsStream.read().toCompletableFuture().get(10, TimeUnit.SECONDS);
    byte[] echoBytes = new byte[echoBuf.remaining()];
    echoBuf.get(echoBytes);
    assertEquals("extended-connect-payload", new String(echoBytes, StandardCharsets.UTF_8));

    wsStream.close();
  }

  private static final class DaemonClientBridge implements FrameHandler {
    private final CompletableFuture<CoreSession> openFuture;
    private final ServerConnector connector;
    private final Scheduler scheduler;
    private volatile CoreSession session;
    private volatile DaemonTestClientPipe pipe;

    DaemonClientBridge(
        CompletableFuture<CoreSession> openFuture, ServerConnector connector, Scheduler scheduler) {
      this.openFuture = openFuture;
      this.connector = connector;
      this.scheduler = scheduler;
    }

    @Override
    public void onOpen(CoreSession session, Callback callback) {
      this.session = session;
      AtomicBoolean closed = new AtomicBoolean();
      this.pipe =
          new DaemonTestClientPipe(
              payload -> {
                session.sendFrame(new Frame(OpCode.BINARY, payload), Callback.NOOP, false);
              },
              session::demand,
              reason -> {
                if (closed.compareAndSet(false, true)) {
                  session.close(1000, reason, Callback.NOOP);
                }
              },
              session.getLocalAddress(),
              session.getRemoteAddress());

      WebSocketEndPoint ep = pipe.createEndPoint(scheduler);
      Connection daemonConn =
          DaemonTestHttp2Server.createConnection(
              connector, ep, ADMITTED.environmentId().value().toString());
      ep.onOpen();
      daemonConn.onOpen();

      openFuture.complete(session);
      callback.succeeded();
    }

    @Override
    public void onFrame(Frame frame, Callback callback) {
      if (frame.getOpCode() == OpCode.BINARY && pipe != null) {
        pipe.deliverInbound(frame.getPayload(), callback::succeeded, callback::failed);
      } else {
        callback.succeeded();
        session.demand();
      }
    }

    @Override
    public void onError(Throwable cause, Callback callback) {
      if (pipe != null) {
        pipe.deliverError(cause);
      }
      callback.succeeded();
    }

    @Override
    public void onClosed(CloseStatus closeStatus, Callback callback) {
      if (pipe != null) {
        pipe.deliverEof();
      }
      callback.succeeded();
    }
  }

  private static final class TestSessions implements DaemonSessions {
    private final CopyOnWriteArrayList<DaemonAdmission.Admitted> abandoned =
        new CopyOnWriteArrayList<>();
    private final CompletableFuture<OpenResult> opened = new CompletableFuture<>();

    record OpenResult(
        DaemonAdmission.Admitted admitted,
        DaemonControlChannel control,
        EnvironmentTunnel tunnel) {}

    @Override
    public DaemonAdmission admit(DaemonHandshake handshake) {
      if (TEST_TOKEN.equals(handshake.registrationToken())) {
        return ADMITTED;
      }
      return new DaemonAdmission.Rejected();
    }

    @Override
    public void abandon(DaemonAdmission.Admitted admitted) {
      abandoned.add(admitted);
    }

    @Override
    public DaemonSession open(
        DaemonAdmission.Admitted admitted, DaemonControlChannel control, EnvironmentTunnel tunnel) {
      opened.complete(new OpenResult(admitted, control, tunnel));
      return new DaemonSession() {
        @Override
        public void receive(Envelope envelope) {}

        @Override
        public void close() {}

        @Override
        public void closed() {}
      };
    }
  }
}
