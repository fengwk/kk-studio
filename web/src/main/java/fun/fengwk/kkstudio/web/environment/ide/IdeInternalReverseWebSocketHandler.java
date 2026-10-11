package fun.fengwk.kkstudio.web.environment.ide;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.environment.server.IdeCommandTransport;
import fun.fengwk.kkstudio.harness.environment.server.IdeSessionIdentity;
import fun.fengwk.kkstudio.harness.environment.server.IdeStreamPairingRegistry;
import fun.fengwk.kkstudio.harness.environment.server.IdeStreamTicketService;
import fun.fengwk.kkstudio.platform.environment.cluster.ClusterIdeOwnerRoute;
import fun.fengwk.kkstudio.platform.environment.cluster.ClusterNodeRegistry;

import java.nio.ByteBuffer;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * owner 节点接收其它节点单跳转发的内部反向数据端点（{@code /internal/environment-ide/{env}/reverse?ticket=...}）。
 *
 * <p>入站请求必须已通过 {@link InternalReverseHandshakeInterceptor} 的 HMAC 认证与 owner 归属校验（在任何握手前
 * fail-closed）。连接建立后本 端点只做：校验 ticket 仍属当前 READY 代际 → 一次性消费 → 交付配对。帧不做任何解释，逐帧桥接给等待中的 exchange，因此
 * credit 语义与 Node 直连完全一致。未知 ticket、已消费 ticket 或代际变更一律以策略违规关闭。
 */
@Slf4j
public final class IdeInternalReverseWebSocketHandler extends AbstractWebSocketHandler {

  /** 握手属性：已认证的目标 Environment。 */
  static final String ATTRIBUTE_ENVIRONMENT = "kk-studio.ide.internal.environment";

  private static final CloseStatus REJECTED = CloseStatus.POLICY_VIOLATION;

  private final IdeStreamTicketService streamTickets;
  private final IdeStreamPairingRegistry<IdeStreamPeer> pairings;
  private final IdeCommandTransport transport;
  private final ClusterNodeRegistry clusterNodeRegistry;
  private final Map<String, WebSocketIdeStreamPeer> peers = new ConcurrentHashMap<>();

  public IdeInternalReverseWebSocketHandler(
      IdeStreamTicketService streamTickets,
      IdeStreamPairingRegistry<IdeStreamPeer> pairings,
      IdeCommandTransport transport,
      ClusterNodeRegistry clusterNodeRegistry) {
    this.streamTickets = Objects.requireNonNull(streamTickets, "streamTickets");
    this.pairings = Objects.requireNonNull(pairings, "pairings");
    this.transport = Objects.requireNonNull(transport, "transport");
    this.clusterNodeRegistry = Objects.requireNonNull(clusterNodeRegistry, "clusterNodeRegistry");
  }

  @Override
  public void afterConnectionEstablished(WebSocketSession session) throws Exception {
    EnvironmentId environmentId = attribute(session);
    String ticket = ticketOf(session);
    if (environmentId == null || ticket == null) {
      reject(session, "internal reverse handshake without an authenticated environment or ticket");
      return;
    }
    if (!owns(environmentId)) {
      reject(session, "internal reverse request does not match this node's READY owner route");
      return;
    }
    Optional<IdeStreamTicketService.Consumed> consumed = streamTickets.consume(ticket);
    if (consumed.isEmpty() || !consumed.get().environmentId().equals(environmentId)) {
      reject(session, "internal reverse ticket is unknown, consumed or expired");
      return;
    }
    Optional<IdeSessionIdentity> current = transport.ideSessionIdentity(environmentId);
    if (current.isEmpty() || !current.get().equals(consumed.get().identity())) {
      reject(session, "internal reverse ticket generation is no longer current");
      return;
    }
    WebSocketIdeStreamPeer peer = new WebSocketIdeStreamPeer(session);
    if (!pairings.pair(ticket, peer)) {
      peer.onTransportClosed();
      reject(session, "internal reverse exchange is no longer waiting");
      return;
    }
    peers.put(session.getId(), peer);
  }

  @Override
  protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
    offer(session, new IdeStreamPeer.Inbound.Text(message.getPayload()));
  }

  @Override
  protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message)
      throws Exception {
    ByteBuffer payload = message.getPayload();
    byte[] frame = new byte[payload.remaining()];
    payload.get(frame);
    offer(session, new IdeStreamPeer.Inbound.Binary(frame));
  }

  @Override
  public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
    detach(session);
  }

  @Override
  public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
    log.debug(
        "internal reverse transport error on {}: {}", session.getId(), exception.getMessage());
    detach(session);
    if (session.isOpen()) {
      session.close(CloseStatus.SERVER_ERROR);
    }
  }

  private boolean owns(EnvironmentId environmentId) {
    return clusterNodeRegistry.route(environmentId).filter(ClusterIdeOwnerRoute::local).isPresent();
  }

  private void offer(WebSocketSession session, IdeStreamPeer.Inbound frame) throws Exception {
    WebSocketIdeStreamPeer peer = peers.get(session.getId());
    if (peer == null) {
      return;
    }
    if (!peer.offer(frame)) {
      // 超出有界入站预算：fail-closed 关闭连接，绝不无界缓冲。
      detach(session);
      session.close(CloseStatus.TOO_BIG_TO_PROCESS);
    }
  }

  private void detach(WebSocketSession session) {
    WebSocketIdeStreamPeer peer = peers.remove(session.getId());
    if (peer != null) {
      peer.onTransportClosed();
    }
  }

  private static EnvironmentId attribute(WebSocketSession session) {
    Object value = session.getAttributes().get(ATTRIBUTE_ENVIRONMENT);
    return value instanceof EnvironmentId environmentId ? environmentId : null;
  }

  private static void reject(WebSocketSession session, String reason) throws Exception {
    log.debug("rejected internal reverse connection {}: {}", session.getId(), reason);
    session.close(REJECTED);
  }

  private static String ticketOf(WebSocketSession session) {
    if (session.getUri() == null || session.getUri().getQuery() == null) {
      return null;
    }
    for (String pair : session.getUri().getQuery().split("&")) {
      int equals = pair.indexOf('=');
      if (equals > 0 && "ticket".equals(pair.substring(0, equals))) {
        String value = pair.substring(equals + 1);
        return value.isEmpty() ? null : value;
      }
    }
    return null;
  }
}
