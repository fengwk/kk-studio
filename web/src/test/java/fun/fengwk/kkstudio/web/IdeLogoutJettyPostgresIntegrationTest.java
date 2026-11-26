package fun.fengwk.kkstudio.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.ide.IdeBootstrapTicketService;
import fun.fengwk.kkstudio.ide.IdeViewSnapshotStore;
import fun.fengwk.kkstudio.ide.gateway.IdeGatewayConstants;
import fun.fengwk.kkstudio.platform.account.StudioAccountService;
import fun.fengwk.kkstudio.platform.account.StudioBrowserSession;
import fun.fengwk.kkstudio.platform.account.StudioRole;
import fun.fengwk.kkstudio.platform.cluster.ClusterOwnerRoute;
import fun.fengwk.kkstudio.platform.cluster.JdbcClusterNodeRegistry;
import fun.fengwk.kkstudio.platform.daemon.protocol.IdeViewState;
import fun.fengwk.kkstudio.platform.daemon.server.DaemonConnectionIdentity;
import fun.fengwk.kkstudio.platform.daemon.server.EnvironmentDaemonServer;
import fun.fengwk.kkstudio.platform.daemon.tunnel.EnvironmentTunnel;
import fun.fengwk.kkstudio.platform.daemon.tunnel.TunnelExchange;
import fun.fengwk.kkstudio.platform.daemon.tunnel.TunnelHttpRequest;
import fun.fengwk.kkstudio.platform.daemon.tunnel.TunnelStream;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** Normal Studio logout must close an already-upgraded IDE tunnel in the real web container. */
class IdeLogoutJettyPostgresIntegrationTest extends WebPostgresTestSupport {

  private static final EnvironmentId ENVIRONMENT_ID =
      EnvironmentId.parse("11111111-1111-4111-8111-111111111111");
  private static final UUID VIEW_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
  private static final String WORKSPACE_PATH = "/managed/workspace";

  @LocalServerPort private int port;
  @Autowired private StudioAccountService accounts;
  @Autowired private IdeBootstrapTicketService bootstrapTickets;
  @Autowired private IdeViewSnapshotStore snapshots;

  @MockitoBean private EnvironmentDaemonServer daemonServer;
  @MockitoBean private JdbcClusterNodeRegistry clusterNodeRegistry;

  @Test
  void ordinaryUserLogoutClosesHealthyIdeWebSocket() throws Exception {
    String username = "ide-logout-" + UUID.randomUUID().toString().substring(0, 8);
    String password = UUID.randomUUID().toString();
    var user = accounts.create(username, password, StudioRole.USER);
    DaemonConnectionIdentity identity = new DaemonConnectionIdentity(ENVIRONMENT_ID, "local-test");
    LocalIdeTunnel tunnel = new LocalIdeTunnel(identity);
    when(daemonServer.connectionIdentity(ENVIRONMENT_ID)).thenReturn(Optional.of(identity));
    when(daemonServer.local(ENVIRONMENT_ID)).thenReturn(Optional.of(tunnel));
    when(clusterNodeRegistry.route(ENVIRONMENT_ID))
        .thenReturn(Optional.of(ClusterOwnerRoute.local(UUID.randomUUID())));
    snapshots.accept(
        identity,
        1L,
        List.of(
            new IdeViewSnapshotStore.View(
                VIEW_ID,
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                1L,
                "/managed/dir",
                WORKSPACE_PATH,
                IdeViewState.OPEN)));

    try (HttpClient client = HttpClient.newHttpClient()) {
      URI base = URI.create("http://127.0.0.1:" + port);
      String origin = base.toString();
      String viewPath = "/ide/" + ENVIRONMENT_ID + "/" + VIEW_ID;
      HttpResponse<Void> login =
          client.send(
              HttpRequest.newBuilder(base.resolve("/api/auth/login"))
                  .timeout(Duration.ofSeconds(10))
                  .header("Origin", origin)
                  .header("Content-Type", "application/json")
                  .POST(
                      HttpRequest.BodyPublishers.ofString(
                          "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                  .build(),
              HttpResponse.BodyHandlers.discarding());
      assertEquals(200, login.statusCode());
      String studioCookie =
          StudioBrowserSession.COOKIE + "=" + cookieValue(login, StudioBrowserSession.COOKIE);
      String bootstrapTicket =
          bootstrapTickets.issue(identity, VIEW_ID.toString(), 1L, WORKSPACE_PATH, user.id());
      URI bootstrapUri =
          base.resolve(
              viewPath + IdeGatewayConstants.BOOTSTRAP_ENDPOINT + "?token=" + bootstrapTicket);
      HttpResponse<Void> bootstrap =
          client.send(
              HttpRequest.newBuilder(bootstrapUri)
                  .timeout(Duration.ofSeconds(10))
                  .header("Cookie", studioCookie)
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.discarding());
      assertEquals(303, bootstrap.statusCode());
      String ideCookie = cookieValue(bootstrap, IdeGatewayConstants.SESSION_COOKIE_NAME);

      SocketFrames frames = new SocketFrames();
      WebSocket socket =
          client
              .newWebSocketBuilder()
              .header(
                  "Cookie",
                  studioCookie + "; " + IdeGatewayConstants.SESSION_COOKIE_NAME + "=" + ideCookie)
              .header("Origin", origin)
              .buildAsync(URI.create("ws://127.0.0.1:" + port + viewPath + "/ws-terminal"), frames)
              .get(10, TimeUnit.SECONDS);
      try {
        assertEquals("healthy", frames.messages.poll(10, TimeUnit.SECONDS));
        assertFalse(frames.closed.isDone());

        HttpResponse<Void> logout =
            client.send(
                HttpRequest.newBuilder(base.resolve("/api/auth/logout"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Origin", origin)
                    .header("Cookie", studioCookie)
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build(),
                HttpResponse.BodyHandlers.discarding());
        assertEquals(204, logout.statusCode());
        frames.closed.get(15, TimeUnit.SECONDS);
        tunnel.stream.closed.get(5, TimeUnit.SECONDS);
      } finally {
        socket.abort();
      }
    }
  }

  private static String cookieValue(HttpResponse<?> response, String name) {
    return response.headers().allValues("set-cookie").stream()
        .filter(value -> value.startsWith(name + "="))
        .findFirst()
        .orElseThrow(() -> new AssertionError("missing expected response cookie"))
        .substring(name.length() + 1)
        .split(";", 2)[0];
  }

  private static final class SocketFrames implements WebSocket.Listener {
    private final LinkedBlockingQueue<String> messages = new LinkedBlockingQueue<>();
    private final CompletableFuture<Integer> closed = new CompletableFuture<>();
    private final StringBuilder text = new StringBuilder();

    @Override
    public void onOpen(WebSocket webSocket) {
      webSocket.request(1);
    }

    @Override
    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
      text.append(data);
      if (last) {
        messages.add(text.toString());
        text.setLength(0);
      }
      webSocket.request(1);
      return null;
    }

    @Override
    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
      closed.complete(statusCode);
      return null;
    }
  }

  private static final class LocalIdeTunnel implements EnvironmentTunnel {
    private final DaemonConnectionIdentity identity;
    private final HealthyWebSocketStream stream = new HealthyWebSocketStream();

    private LocalIdeTunnel(DaemonConnectionIdentity identity) {
      this.identity = identity;
    }

    @Override
    public DaemonConnectionIdentity identity() {
      return identity;
    }

    @Override
    public TunnelExchange openHttp(TunnelHttpRequest request) {
      throw new UnsupportedOperationException("HTTP is not used by this test");
    }

    @Override
    public TunnelExchange openWebSocket(TunnelHttpRequest request) {
      String key =
          request.headers().entrySet().stream()
              .filter(entry -> entry.getKey().equalsIgnoreCase("sec-websocket-key"))
              .flatMap(entry -> entry.getValue().stream())
              .findFirst()
              .orElseThrow();
      String accept = webSocketAccept(key);
      return new TunnelExchange() {
        @Override
        public CompletionStage<TunnelExchange.Response> responseHeaders() {
          return CompletableFuture.completedFuture(
              new TunnelExchange.Response(200, Map.of("sec-websocket-accept", List.of(accept))));
        }

        @Override
        public TunnelStream stream() {
          return stream;
        }

        @Override
        public void close() {
          stream.close();
        }
      };
    }

    private static String webSocketAccept(String key) {
      try {
        byte[] digest =
            MessageDigest.getInstance("SHA-1")
                .digest(
                    (key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11")
                        .getBytes(StandardCharsets.ISO_8859_1));
        return Base64.getEncoder().encodeToString(digest);
      } catch (NoSuchAlgorithmException error) {
        throw new IllegalStateException(error);
      }
    }
  }

  private static final class HealthyWebSocketStream implements TunnelStream {
    private final CompletableFuture<ByteBuffer> initialFrame =
        CompletableFuture.completedFuture(
            ByteBuffer.wrap(new byte[] {(byte) 0x81, 7, 'h', 'e', 'a', 'l', 't', 'h', 'y'}));
    private final CompletableFuture<ByteBuffer> nextRead = new CompletableFuture<>();
    private final CompletableFuture<Void> closed = new CompletableFuture<>();
    private volatile boolean firstRead = true;

    @Override
    public CompletionStage<ByteBuffer> read() {
      if (firstRead) {
        firstRead = false;
        return initialFrame;
      }
      return nextRead;
    }

    @Override
    public CompletionStage<Void> write(ByteBuffer source) {
      source.position(source.limit());
      return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletionStage<Void> endOutput() {
      return CompletableFuture.completedFuture(null);
    }

    @Override
    public void close() {
      nextRead.complete(null);
      closed.complete(null);
    }
  }
}
