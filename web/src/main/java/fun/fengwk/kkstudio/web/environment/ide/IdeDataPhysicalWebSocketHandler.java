package fun.fengwk.kkstudio.web.environment.ide;

import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.PongMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.WebSocketClient;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;
import org.springframework.web.util.UriComponentsBuilder;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.ide.cluster.IdeClusterClient;
import fun.fengwk.kkstudio.ide.data.IdeDataConnection;
import fun.fengwk.kkstudio.ide.data.IdeDataConnectionRegistry;
import fun.fengwk.kkstudio.ide.mux.MuxLimits;
import fun.fengwk.kkstudio.platform.environment.cluster.ClusterNodeRegistry;
import fun.fengwk.kkstudio.platform.environment.cluster.ClusterOwnerRoute;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Daemon 复用数据连接的物理 WS 端点。
 *
 * <p>public 端点只接受当前 READY owner 的 ticket；非 owner 节点把整条连接单跳桥给 owner 的 internal 端点，不消费
 * ticket。internal 端点只在 HMAC 与本节点 owner 归属校验通过后消费 ticket 并驱动 mux。message 边界即 wire 帧边界，任何协议错误都
 * fail-stop。
 */
final class IdeDataPhysicalWebSocketHandler extends AbstractWebSocketHandler {

  static final String SOCKET_ATTRIBUTE = "kk.ide.data.socket";

  private static final long PING_INTERVAL_MILLIS = 15_000L;

  private static final long PONG_TIMEOUT_MILLIS = 45_000L;

  private final IdeDataConnectionRegistry registry;
  private final ClusterNodeRegistry clusterNodeRegistry;
  private final IdeClusterClient clusterInternalClient;
  private final WebSocketClient webSocketClient;
  private final ScheduledExecutorService heartbeatScheduler;
  private final IdeGatewayProperties properties;
  private final boolean internal;

  IdeDataPhysicalWebSocketHandler(
      IdeDataConnectionRegistry registry,
      ClusterNodeRegistry clusterNodeRegistry,
      IdeClusterClient clusterInternalClient,
      WebSocketClient webSocketClient,
      ScheduledExecutorService heartbeatScheduler,
      IdeGatewayProperties properties,
      boolean internal) {
    this.registry = Objects.requireNonNull(registry, "registry");
    this.clusterNodeRegistry = Objects.requireNonNull(clusterNodeRegistry, "clusterNodeRegistry");
    this.clusterInternalClient =
        Objects.requireNonNull(clusterInternalClient, "clusterInternalClient");
    this.webSocketClient = Objects.requireNonNull(webSocketClient, "webSocketClient");
    this.heartbeatScheduler = Objects.requireNonNull(heartbeatScheduler, "heartbeatScheduler");
    this.properties = Objects.requireNonNull(properties, "properties");
    this.internal = internal;
  }

  @Override
  public void afterConnectionEstablished(WebSocketSession session) {
    session.setBinaryMessageSizeLimit(MuxLimits.HEADER_BYTES + MuxLimits.MAX_PAYLOAD_BYTES);
    EnvironmentId environmentId = environmentOf(session);
    if (environmentId == null) {
      closeAfterEstablishment(
          session, CloseStatus.BAD_DATA.withReason("ide data route is invalid"));
      return;
    }
    List<String> tickets =
        UriComponentsBuilder.fromUri(session.getUri()).build().getQueryParams().get("ticket");
    if (tickets == null || tickets.size() != 1 || tickets.getFirst().isBlank()) {
      closeAfterEstablishment(
          session, CloseStatus.POLICY_VIOLATION.withReason("ide data ticket is required"));
      return;
    }
    String ticket = tickets.getFirst();
    if (!internal) {
      Optional<ClusterOwnerRoute> route = clusterNodeRegistry.route(environmentId);
      if (route.isEmpty()) {
        closeAfterEstablishment(session, new CloseStatus(1013, "ide owner is unavailable"));
        return;
      }
      if (!route.get().local()) {
        IdePhysicalDataBridge bridge =
            new IdePhysicalDataBridge(
                webSocketClient,
                clusterInternalClient,
                route.get(),
                environmentId,
                ticket,
                properties.requireDataConnectTimeout(),
                session);
        session.getAttributes().put(SOCKET_ATTRIBUTE, bridge);
        bridge.start();
        return;
      }
    }

    IdeDataWebSocketTransport transport = new IdeDataWebSocketTransport(session);
    Optional<IdeDataConnection> accepted = registry.accept(environmentId, ticket, transport);
    if (accepted.isEmpty()) {
      closeAfterEstablishment(
          session, CloseStatus.POLICY_VIOLATION.withReason("ide data ticket is not accepted"));
      return;
    }
    IdeDataPhysicalSession physical =
        new IdeDataPhysicalSession(
            transport, accepted.get(), () -> session.getAttributes().remove(SOCKET_ATTRIBUTE));
    IdeDataPhysicalSocket socket =
        new IdeDataPhysicalSocket() {
          @Override
          public void onBinary(byte[] frame) {
            if (!physical.offer(frame)) {
              close(session, CloseStatus.POLICY_VIOLATION.withReason("ide data queue is full"));
            }
          }

          @Override
          public void onPong() {
            physical.onPong();
          }

          @Override
          public void onClosed(CloseStatus status) {
            physical.onTransportClosed(status);
          }

          @Override
          public void onError(Throwable error) {
            physical.onTransportError(error);
          }
        };
    session.getAttributes().put(SOCKET_ATTRIBUTE, socket);
    physical.start(heartbeatScheduler, PING_INTERVAL_MILLIS, PONG_TIMEOUT_MILLIS);
  }

  @Override
  protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
    IdeDataPhysicalSocket socket = socket(session);
    if (socket == null || !message.isLast()) {
      close(
          session,
          CloseStatus.PROTOCOL_ERROR.withReason("one binary message must contain one frame"));
      return;
    }
    byte[] frame = new byte[message.getPayload().remaining()];
    message.getPayload().get(frame);
    socket.onBinary(frame);
  }

  @Override
  protected void handleTextMessage(WebSocketSession session, TextMessage message) {
    IdeDataPhysicalSocket socket = socket(session);
    if (socket != null) {
      socket.onError(new IOException("IDE data websocket requires binary frames"));
    }
    close(session, CloseStatus.BAD_DATA.withReason("IDE data websocket requires binary frames"));
  }

  @Override
  protected void handlePongMessage(WebSocketSession session, PongMessage message) {
    IdeDataPhysicalSocket socket = socket(session);
    if (socket != null) {
      socket.onPong();
    }
  }

  @Override
  public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
    IdeDataPhysicalSocket socket = socket(session);
    if (socket != null) {
      socket.onClosed(status);
    }
  }

  @Override
  public void handleTransportError(WebSocketSession session, Throwable exception) {
    IdeDataPhysicalSocket socket = socket(session);
    if (socket != null) {
      socket.onError(exception);
    }
    close(session, CloseStatus.SERVER_ERROR);
  }

  /** 握手回调返回前直接 close 可能被容器忽略；把拒绝关闭排到回调之后，保证客户端收到明确 close 而不是悬挂连接。 */
  private void closeAfterEstablishment(WebSocketSession session, CloseStatus status) {
    heartbeatScheduler.schedule(() -> close(session, status), 100L, TimeUnit.MILLISECONDS);
  }

  private static IdeDataPhysicalSocket socket(WebSocketSession session) {
    Object socket = session.getAttributes().get(SOCKET_ATTRIBUTE);
    return socket instanceof IdeDataPhysicalSocket value ? value : null;
  }

  private static EnvironmentId environmentOf(WebSocketSession session) {
    Object attribute = session.getAttributes().get(IdeDataHandshakeInterceptor.ENVIRONMENT);
    if (attribute instanceof EnvironmentId environmentId) {
      return environmentId;
    }
    String rawPath = session.getUri().getRawPath();
    String prefix = IdeGatewayProperties.DATA_PATH + "/";
    if (rawPath == null || !rawPath.startsWith(prefix)) {
      return null;
    }
    String candidate = rawPath.substring(prefix.length());
    if (candidate.contains("/")) {
      return null;
    }
    try {
      return EnvironmentId.parse(candidate);
    } catch (IllegalArgumentException error) {
      return null;
    }
  }

  private static void close(WebSocketSession session, CloseStatus status) {
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
