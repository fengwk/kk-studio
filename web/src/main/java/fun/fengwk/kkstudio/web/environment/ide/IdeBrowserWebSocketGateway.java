package fun.fengwk.kkstudio.web.environment.ide;

import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.WebSocketClient;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;
import org.springframework.web.socket.server.support.DefaultHandshakeHandler;
import org.springframework.web.socket.server.support.WebSocketHttpRequestHandler;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.ide.cluster.IdeClusterClient;
import fun.fengwk.kkstudio.ide.data.IdeDataConnectionRegistry;
import fun.fengwk.kkstudio.ide.mux.MuxHeader;
import fun.fengwk.kkstudio.platform.environment.cluster.ClusterNodeRegistry;
import fun.fengwk.kkstudio.platform.environment.cluster.ClusterOwnerRoute;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * IDE 网关上浏览器原生 WebSocket 的数据面入口。
 *
 * <p>本地 READY owner 直接用 {@link IdeDataViewAuthorizer} 校验 cookie 的 view+epoch 授权，再为同源浏览器会话建立一条 mux
 * 逻辑流；非 owner 把浏览器 partial 分段单跳到 owner 的内部 {@code data-ws}，不解析帧、不持有浏览器会话状态。任何路径都不在节点间重放请求。
 */
@Component
public final class IdeBrowserWebSocketGateway {

  private final IdeGatewayProperties properties;
  private final IdeDataConnectionRegistry dataConnections;
  private final ClusterNodeRegistry clusterNodeRegistry;
  private final IdeClusterClient clusterInternalClient;
  private final WebSocketClient webSocketClient;
  private final IdeDataViewAuthorizer viewAuthorizer;

  private volatile DefaultHandshakeHandler handshakeHandler;

  @Autowired
  public IdeBrowserWebSocketGateway(
      IdeGatewayProperties properties,
      IdeDataConnectionRegistry dataConnections,
      ClusterNodeRegistry clusterNodeRegistry,
      IdeClusterClient clusterInternalClient,
      WebSocketClient webSocketClient,
      IdeDataViewAuthorizer viewAuthorizer) {
    this.properties = Objects.requireNonNull(properties, "properties");
    this.dataConnections = Objects.requireNonNull(dataConnections, "dataConnections");
    this.clusterNodeRegistry = Objects.requireNonNull(clusterNodeRegistry, "clusterNodeRegistry");
    this.clusterInternalClient =
        Objects.requireNonNull(clusterInternalClient, "clusterInternalClient");
    this.webSocketClient = Objects.requireNonNull(webSocketClient, "webSocketClient");
    this.viewAuthorizer = Objects.requireNonNull(viewAuthorizer, "viewAuthorizer");
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
   * 处理一个视图作用域的浏览器 WS 升级。
   *
   * @param rawPathAndQuery view 基址之后的原始 path（含 query）
   */
  public void handleUpgrade(
      EnvironmentId environmentId,
      UUID viewId,
      String rawPathAndQuery,
      HttpServletRequest request,
      HttpServletResponse response)
      throws ServletException, IOException {
    Objects.requireNonNull(environmentId, "environmentId");
    Objects.requireNonNull(viewId, "viewId");
    if (!IdeOriginPolicy.webSocketHandshakeAllowed(request)) {
      response.sendError(HttpServletResponse.SC_FORBIDDEN);
      return;
    }
    Optional<ClusterOwnerRoute> route = clusterNodeRegistry.route(environmentId);
    if (route.isEmpty()) {
      response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
      return;
    }
    AbstractWebSocketHandler handler;
    if (!route.get().local()) {
      handler =
          new RemoteUpgradeHandler(
              route.get(),
              environmentId,
              viewId,
              rawPathAndQuery,
              IdeRemoteDataPlaneForwarder.forwardedBrowserHeaders(request, properties),
              IdeRemoteDataPlaneForwarder.browserScheme(request));
    } else {
      IdeDataViewAuthorizer.IdeDataViewAccess access =
          authorize(environmentId, viewId, request.getHeader("Cookie"));
      if (access == null) {
        response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
        return;
      }
      handler =
          new IdeBrowserDataSessionHandler(
              environmentId,
              viewId,
              access.bindingEpoch(),
              rawPathAndQuery,
              IdeDataHeaderPolicy.forwardRequestHeaders(request, true),
              access.controlIdentity(),
              dataConnections);
    }
    WebSocketHttpRequestHandler upgrade =
        new WebSocketHttpRequestHandler(handler, handshakeHandler(request.getServletContext()));
    upgrade.handleRequest(request, response);
  }

  /** owner 内部代理入口：HMAC/owner 归属已由握手拦截器校验。 */
  IdeBrowserDataSessionHandler handleInternalUpgrade(
      WebSocketSession session,
      EnvironmentId environmentId,
      UUID viewId,
      String rawPathAndQuery,
      String cookieHeader,
      List<MuxHeader> headers) {
    IdeDataViewAuthorizer.IdeDataViewAccess access = authorize(environmentId, viewId, cookieHeader);
    if (access == null) {
      closeQuietly(session, CloseStatus.POLICY_VIOLATION.withReason("ide view is not active"));
      return null;
    }
    IdeBrowserDataSessionHandler handler =
        new IdeBrowserDataSessionHandler(
            environmentId,
            viewId,
            access.bindingEpoch(),
            rawPathAndQuery,
            headers,
            access.controlIdentity(),
            dataConnections);
    try {
      handler.afterConnectionEstablished(session);
      return handler;
    } catch (RuntimeException error) {
      closeQuietly(session, CloseStatus.SERVER_ERROR.withReason("ide gateway unavailable"));
      return null;
    }
  }

  private IdeDataViewAuthorizer.IdeDataViewAccess authorize(
      EnvironmentId environmentId, UUID viewId, String cookieHeader) {
    return viewAuthorizer
        .authorize(environmentId, viewId, cookieHeader)
        .filter(access -> access.viewId().equals(viewId))
        .orElse(null);
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

  /** 非 owner 的浏览器侧入口：握手完成后按 owner 路由建立单跳桥。 */
  private final class RemoteUpgradeHandler extends AbstractWebSocketHandler {

    private final ClusterOwnerRoute owner;
    private final EnvironmentId environmentId;
    private final UUID viewId;
    private final String rawPathAndQuery;
    private final List<MuxHeader> headers;
    private final String browserScheme;
    private volatile IdeRemoteBrowserDataBridge bridge;
    private final ArrayDeque<WebSocketMessage<?>> pending = new ArrayDeque<>();
    private long pendingBytes;
    private boolean browserClosed;
    private CloseStatus browserCloseStatus;
    private static final int MAX_PENDING_MESSAGES = 64;
    private static final long MAX_PENDING_BYTES = 1024L * 1024L;

    private RemoteUpgradeHandler(
        ClusterOwnerRoute owner,
        EnvironmentId environmentId,
        UUID viewId,
        String rawPathAndQuery,
        List<MuxHeader> headers,
        String browserScheme) {
      this.owner = owner;
      this.environmentId = environmentId;
      this.viewId = viewId;
      this.rawPathAndQuery = rawPathAndQuery;
      this.headers = headers;
      this.browserScheme = browserScheme;
    }

    @Override
    public boolean supportsPartialMessages() {
      return true;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession browser) {
      IdeRemoteBrowserDataBridge current =
          new IdeRemoteBrowserDataBridge(
              webSocketClient,
              clusterInternalClient,
              owner,
              environmentId,
              viewId,
              rawPathAndQuery,
              headers,
              browserScheme,
              properties.requireDataConnectTimeout(),
              browser);
      current.afterConnectionEstablished(browser);
      publishAndDrain(current, browser);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
      IdeRemoteBrowserDataBridge current;
      boolean overflow = false;
      synchronized (pending) {
        current = bridge;
        if (current == null) {
          overflow = !enqueueLocked(new TextMessage(message.getPayload(), message.isLast()));
        }
      }
      if (current == null) {
        if (overflow) {
          closeQuietly(session, CloseStatus.POLICY_VIOLATION);
        }
        return;
      }
      current.handleTextMessage(session, message);
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
      IdeRemoteBrowserDataBridge current;
      boolean overflow = false;
      synchronized (pending) {
        current = bridge;
        if (current == null) {
          ByteBuffer payload = message.getPayload();
          byte[] bytes = new byte[payload.remaining()];
          payload.get(bytes);
          overflow = !enqueueLocked(new BinaryMessage(ByteBuffer.wrap(bytes), message.isLast()));
        }
      }
      if (current == null) {
        if (overflow) {
          closeQuietly(session, CloseStatus.POLICY_VIOLATION);
        }
        return;
      }
      current.handleBinaryMessage(session, message);
    }

    /** 在 pending 锁内发布 bridge；只有队列排空后才允许新消息绕过队列。 */
    private void publishAndDrain(IdeRemoteBrowserDataBridge current, WebSocketSession browser) {
      while (true) {
        WebSocketMessage<?> message;
        boolean closedEarly;
        synchronized (pending) {
          closedEarly = browserClosed;
          if (!closedEarly) {
            message = pending.pollFirst();
            if (message == null) {
              bridge = current;
              return;
            }
            pendingBytes -= message.getPayloadLength();
          } else {
            message = null;
          }
        }
        if (closedEarly) {
          current.afterConnectionClosed(browser, browserCloseStatus);
          return;
        }
        if (message instanceof TextMessage text) {
          current.handleTextMessage(browser, text);
        } else if (message instanceof BinaryMessage binary) {
          current.handleBinaryMessage(browser, binary);
        }
      }
    }

    private boolean enqueueLocked(WebSocketMessage<?> message) {
      long bytes = message.getPayloadLength();
      if (pending.size() >= MAX_PENDING_MESSAGES || pendingBytes + bytes > MAX_PENDING_BYTES) {
        return false;
      }
      pending.addLast(message);
      pendingBytes += bytes;
      return true;
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
      IdeRemoteBrowserDataBridge current;
      synchronized (pending) {
        browserClosed = true;
        browserCloseStatus = status;
        current = bridge;
      }
      if (current != null) {
        current.afterConnectionClosed(session, status);
      }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
      IdeRemoteBrowserDataBridge current;
      synchronized (pending) {
        browserClosed = true;
        browserCloseStatus = CloseStatus.SERVER_ERROR;
        current = bridge;
      }
      if (current != null) {
        current.handleTransportError(session, exception);
      }
    }
  }

  private static void closeQuietly(WebSocketSession session, CloseStatus status) {
    synchronized (session) {
      if (!session.isOpen()) {
        return;
      }
      try {
        session.close(status);
      } catch (IOException ignored) {
        // 对端已断开。
      }
    }
  }
}
