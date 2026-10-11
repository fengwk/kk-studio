package fun.fengwk.kkstudio.web.environment.ide;

import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.WebSocketClient;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;
import org.springframework.web.socket.server.support.DefaultHandshakeHandler;
import org.springframework.web.socket.server.support.WebSocketHttpRequestHandler;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.environment.daemon.IdeHeader;
import fun.fengwk.kkstudio.harness.environment.server.IdeCommandTransport;
import fun.fengwk.kkstudio.harness.environment.server.IdeSessionIdentity;
import fun.fengwk.kkstudio.harness.environment.server.IdeStreamPairingRegistry;
import fun.fengwk.kkstudio.harness.environment.server.IdeStreamTicketService;
import fun.fengwk.kkstudio.platform.environment.cluster.ClusterIdeOwnerRoute;
import fun.fengwk.kkstudio.platform.environment.cluster.ClusterInternalClient;
import fun.fengwk.kkstudio.platform.environment.cluster.ClusterNodeRegistry;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * IDE 独立 Origin 上浏览器原生 WebSocket 的数据面：本节点是 READY owner 时直接配对 Daemon 反向连接；不是 owner 时把浏览器 WS 单跳到
 * owner 的内部数据端点（owner 侧做 cookie/代际鉴权与 Daemon 配对），本节点只做有界原生分段桥接。
 *
 * <p>两条路径都保持原生分段语义（partial + FIN、≤64 KiB），因此大文件/长连接编辑不会造成整包内存峰值。握手前一律完成精确同源校验； 本地路径额外校验 host-only
 * 会话 cookie 与代际，远端路径由 owner 完成同样的校验。任何失败都以 HTTP 状态码 fail-closed，绝不回退到本地或 其它节点。
 */
@Slf4j
@Component
public final class IdeBrowserWebSocketGateway {

  private final IdeCommandTransport transport;
  private final IdeStreamTicketService streamTickets;
  private final IdeStreamPairingRegistry<IdeStreamPeer> pairings;
  private final IdeGatewayProperties properties;
  private final IdeBrowserSessionRegistry sessions;
  private final ClusterNodeRegistry clusterNodeRegistry;
  private final ClusterInternalClient clusterInternalClient;
  private final WebSocketClient webSocketClient;
  private final Map<String, IdeBrowserStreamSession> active = new ConcurrentHashMap<>();

  private volatile DefaultHandshakeHandler handshakeHandler;

  @Autowired
  public IdeBrowserWebSocketGateway(
      IdeCommandTransport transport,
      IdeStreamTicketService streamTickets,
      IdeStreamPairingRegistry<IdeStreamPeer> pairings,
      IdeGatewayProperties properties,
      IdeBrowserSessionRegistry sessions,
      ClusterNodeRegistry clusterNodeRegistry,
      ClusterInternalClient clusterInternalClient) {
    this(
        transport,
        streamTickets,
        pairings,
        properties,
        sessions,
        clusterNodeRegistry,
        clusterInternalClient,
        new StandardWebSocketClient());
  }

  IdeBrowserWebSocketGateway(
      IdeCommandTransport transport,
      IdeStreamTicketService streamTickets,
      IdeStreamPairingRegistry<IdeStreamPeer> pairings,
      IdeGatewayProperties properties,
      IdeBrowserSessionRegistry sessions,
      ClusterNodeRegistry clusterNodeRegistry,
      ClusterInternalClient clusterInternalClient,
      WebSocketClient webSocketClient) {
    this.transport = Objects.requireNonNull(transport, "transport");
    this.streamTickets = Objects.requireNonNull(streamTickets, "streamTickets");
    this.pairings = Objects.requireNonNull(pairings, "pairings");
    this.properties = Objects.requireNonNull(properties, "properties");
    this.sessions = Objects.requireNonNull(sessions, "sessions");
    this.clusterNodeRegistry = Objects.requireNonNull(clusterNodeRegistry, "clusterNodeRegistry");
    this.clusterInternalClient =
        Objects.requireNonNull(clusterInternalClient, "clusterInternalClient");
    this.webSocketClient = Objects.requireNonNull(webSocketClient, "webSocketClient");
  }

  /** 该请求是否为浏览器 WebSocket 升级。 */
  public static boolean isWebSocketUpgrade(HttpServletRequest request) {
    String upgrade = request.getHeader("Upgrade");
    if (upgrade == null || !upgrade.trim().equalsIgnoreCase("websocket")) {
      return false;
    }
    String connection = request.getHeader("Connection");
    if (connection == null) {
      return false;
    }
    return Arrays.stream(connection.split(","))
        .anyMatch(token -> token.trim().equalsIgnoreCase("upgrade"));
  }

  /**
   * 处理一个 IDE Origin 上的浏览器 WS 升级：先做精确同源校验（本地路径还要 cookie/代际），再执行容器级握手。
   *
   * <p>校验失败直接以 HTTP 状态码拒绝，不建立任何反向连接。
   */
  public void handleUpgrade(
      EnvironmentId environmentId,
      String rawPathAndQuery,
      HttpServletRequest request,
      HttpServletResponse response)
      throws ServletException, IOException {
    Objects.requireNonNull(environmentId, "environmentId");
    if (!properties.configured()) {
      response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
      return;
    }
    if (!IdeOriginPolicy.webSocketHandshakeAllowed(request)) {
      response.sendError(HttpServletResponse.SC_FORBIDDEN);
      return;
    }
    Optional<ClusterIdeOwnerRoute> route = clusterNodeRegistry.route(environmentId);
    AbstractWebSocketHandler handler;
    if (route.isPresent() && !route.get().local()) {
      // 非 owner：cookie/代际由 owner 校验；本节点只做原生分段桥接。
      handler = new RemoteBridgeHandler(route.get(), environmentId, rawPathAndQuery, request);
    } else {
      IdeBrowserSessionRegistry.Session session = requireSession(environmentId, request);
      if (session == null) {
        response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
        return;
      }
      if (!generationCurrent(environmentId, session)) {
        response.sendError(HttpServletResponse.SC_CONFLICT);
        return;
      }
      handler =
          new BrowserHandler(
              environmentId,
              session,
              rawPathAndQuery,
              IdeGatewayDataPlane.forwardRequestHeaders(request));
    }
    WebSocketHttpRequestHandler upgrade =
        new WebSocketHttpRequestHandler(handler, handshakeHandler(request.getServletContext()));
    upgrade.handleRequest(request, response);
  }

  private DefaultHandshakeHandler handshakeHandler(ServletContext servletContext) {
    DefaultHandshakeHandler handler = handshakeHandler;
    if (handler == null) {
      synchronized (this) {
        handler = handshakeHandler;
        if (handler == null) {
          handler = new DefaultHandshakeHandler();
          handler.setServletContext(servletContext);
          handler.start();
          handshakeHandler = handler;
        }
      }
    }
    return handler;
  }

  private IdeBrowserSessionRegistry.Session requireSession(
      EnvironmentId environmentId, HttpServletRequest request) {
    Cookie[] cookies = request.getCookies();
    if (cookies == null) {
      return null;
    }
    String name = properties.requireSessionCookieName();
    for (Cookie cookie : cookies) {
      if (!name.equals(cookie.getName())) {
        continue;
      }
      Optional<IdeBrowserSessionRegistry.Session> session = sessions.lookup(cookie.getValue());
      if (session.isPresent() && session.get().environmentId().equals(environmentId)) {
        return session.get();
      }
    }
    return null;
  }

  private boolean generationCurrent(
      EnvironmentId environmentId, IdeBrowserSessionRegistry.Session session) {
    Optional<IdeSessionIdentity> current = transport.ideSessionIdentity(environmentId);
    return current.isPresent() && current.get().equals(session.identity());
  }

  private IdeStreamDeps deps() {
    return new IdeStreamDeps(transport, streamTickets, pairings, properties);
  }

  /** 本地 owner 的浏览器连接侧：入站分段进入按字节有界的队列，出站用原生 basicRemote 分段发送。 */
  private final class BrowserHandler extends AbstractWebSocketHandler {

    private final EnvironmentId environmentId;
    private final IdeBrowserSessionRegistry.Session session;
    private final String rawPathAndQuery;
    private final List<IdeHeader> headers;

    private BrowserHandler(
        EnvironmentId environmentId,
        IdeBrowserSessionRegistry.Session session,
        String rawPathAndQuery,
        List<IdeHeader> headers) {
      this.environmentId = environmentId;
      this.session = session;
      this.rawPathAndQuery = rawPathAndQuery;
      this.headers = headers;
    }

    @Override
    public boolean supportsPartialMessages() {
      return true;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession browser) {
      browser.setTextMessageSizeLimit(IdeBrowserStreamSession.SEGMENT_BYTES);
      browser.setBinaryMessageSizeLimit(IdeBrowserStreamSession.SEGMENT_BYTES);
      IdeBrowserStreamSession stream =
          new IdeBrowserStreamSession(
              browser,
              environmentId,
              session,
              rawPathAndQuery,
              headers,
              deps(),
              () -> active.remove(browser.getId()));
      active.put(browser.getId(), stream);
      stream.start();
    }

    @Override
    protected void handleTextMessage(WebSocketSession browser, TextMessage message) {
      offer(
          browser,
          new IdeBrowserStreamSession.Frame(
              true, message.getPayload().getBytes(StandardCharsets.UTF_8), message.isLast()));
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession browser, BinaryMessage message) {
      ByteBuffer payload = message.getPayload();
      byte[] bytes = new byte[payload.remaining()];
      payload.get(bytes);
      offer(browser, new IdeBrowserStreamSession.Frame(false, bytes, message.isLast()));
    }

    @Override
    public void afterConnectionClosed(WebSocketSession browser, CloseStatus status) {
      detach(browser, false);
    }

    @Override
    public void handleTransportError(WebSocketSession browser, Throwable exception) {
      detach(browser, true);
    }

    private void offer(WebSocketSession browser, IdeBrowserStreamSession.Frame frame) {
      IdeBrowserStreamSession stream = active.get(browser.getId());
      if (stream != null && !stream.offer(frame)) {
        // 有界入站预算/关闭：fail-closed 关闭浏览器连接，绝不无界缓冲。
        closeBrowser(browser, CloseStatus.POLICY_VIOLATION);
      }
    }

    private void detach(WebSocketSession browser, boolean error) {
      IdeBrowserStreamSession stream = active.remove(browser.getId());
      if (stream != null) {
        stream.onBrowserDetached();
      }
      if (error) {
        closeBrowser(browser, CloseStatus.SERVER_ERROR);
      }
    }
  }

  /** 非 owner 的浏览器连接侧：把浏览器 partial 帧透传给 owner 的内部 WS，并回传 owner 的分段。 */
  private final class RemoteBridgeHandler extends AbstractWebSocketHandler {

    private final ClusterIdeOwnerRoute owner;
    private final EnvironmentId environmentId;
    private final String rawPathAndQuery;
    private final List<IdeHeader> headers;
    private final String browserScheme;
    private volatile IdeRemoteBrowserBridge bridge;

    private RemoteBridgeHandler(
        ClusterIdeOwnerRoute owner,
        EnvironmentId environmentId,
        String rawPathAndQuery,
        HttpServletRequest request) {
      this.owner = owner;
      this.environmentId = environmentId;
      this.rawPathAndQuery = rawPathAndQuery;
      this.headers = IdeRemoteDataPlaneForwarder.forwardedBrowserHeaders(request, properties);
      this.browserScheme = IdeRemoteDataPlaneForwarder.browserScheme(request);
    }

    @Override
    public boolean supportsPartialMessages() {
      return true;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession browser) {
      browser.setTextMessageSizeLimit(IdeBrowserStreamSession.SEGMENT_BYTES);
      browser.setBinaryMessageSizeLimit(IdeBrowserStreamSession.SEGMENT_BYTES);
      IdeRemoteBrowserBridge current =
          new IdeRemoteBrowserBridge(
              webSocketClient,
              clusterInternalClient,
              owner,
              environmentId,
              rawPathAndQuery,
              headers,
              browserScheme,
              browser);
      bridge = current;
      current.start();
    }

    @Override
    protected void handleTextMessage(WebSocketSession browser, TextMessage message) {
      IdeRemoteBrowserBridge current = bridge;
      if (current != null) {
        current.forwardBrowserText(message.getPayload(), message.isLast());
      }
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession browser, BinaryMessage message) {
      ByteBuffer payload = message.getPayload();
      byte[] bytes = new byte[payload.remaining()];
      payload.get(bytes);
      IdeRemoteBrowserBridge current = bridge;
      if (current != null) {
        current.forwardBrowserBinary(bytes, message.isLast());
      }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession browser, CloseStatus status) {
      closeBridge();
    }

    @Override
    public void handleTransportError(WebSocketSession browser, Throwable exception) {
      closeBridge();
      closeBrowser(browser, CloseStatus.SERVER_ERROR);
    }

    private void closeBridge() {
      IdeRemoteBrowserBridge current = bridge;
      if (current != null) {
        current.closeOwner();
      }
    }
  }

  /** 直接关闭浏览器连接（原始句柄，用于握手后无会话的 fail-closed 场景）。 */
  private static void closeBrowser(WebSocketSession browser, CloseStatus status) {
    synchronized (browser) {
      if (browser.isOpen()) {
        try {
          browser.close(status);
        } catch (IOException ignored) {
          // 浏览器侧已断开。
        }
      }
    }
  }
}
