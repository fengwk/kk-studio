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
 * Daemon 反向数据连接的 WebSocket 端点：消费一次性 stream ticket 并把连接作为 {@link IdeStreamPeer} 交付给等待中的 exchange。
 *
 * <p>握手后依次执行：从路径末段取 ticket → 一次性消费（不存在/已消费/已过期即失败）→ 复核其绑定代际仍是当前 READY 代际（跨进程接管后旧 ticket 自动作废）→
 * 登记配对。任一步失败都以策略违规关闭连接且不产生任何数据面副作用。连接随后只做字节桥接：文本/二进制帧入队， 出站帧由数据面单线程发送。
 */
@Slf4j
public final class IdeReverseStreamWebSocketHandler extends AbstractWebSocketHandler {

  private static final CloseStatus REJECTED = CloseStatus.POLICY_VIOLATION;

  private final IdeStreamTicketService streamTickets;
  private final IdeStreamPairingRegistry<IdeStreamPeer> pairings;
  private final IdeCommandTransport transport;
  private final ClusterNodeRegistry clusterNodeRegistry;
  private final IdeOwnerStreamForwarder ownerForwarder;
  private final Map<String, WebSocketIdeStreamPeer> peers = new ConcurrentHashMap<>();

  public IdeReverseStreamWebSocketHandler(
      IdeStreamTicketService streamTickets,
      IdeStreamPairingRegistry<IdeStreamPeer> pairings,
      IdeCommandTransport transport,
      ClusterNodeRegistry clusterNodeRegistry,
      IdeOwnerStreamForwarder ownerForwarder) {
    this.streamTickets = Objects.requireNonNull(streamTickets, "streamTickets");
    this.pairings = Objects.requireNonNull(pairings, "pairings");
    this.transport = Objects.requireNonNull(transport, "transport");
    this.clusterNodeRegistry = Objects.requireNonNull(clusterNodeRegistry, "clusterNodeRegistry");
    this.ownerForwarder = Objects.requireNonNull(ownerForwarder, "ownerForwarder");
  }

  @Override
  public void afterConnectionEstablished(WebSocketSession session) throws Exception {
    String ticket = ticketOf(session);
    EnvironmentId environmentId = environmentOf(session);
    if (ticket == null || environmentId == null) {
      reject(session, "reverse stream handshake without a ticket or environment");
      return;
    }
    Optional<ClusterIdeOwnerRoute> route = clusterNodeRegistry.route(environmentId);
    if (route.isPresent() && !route.get().local()) {
      // Node 可能落在负载均衡后的非 owner 节点：单跳转发给 READY owner，绝不本地兜底。
      WebSocketIdeStreamPeer nodePeer = new WebSocketIdeStreamPeer(session);
      peers.put(session.getId(), nodePeer);
      ownerForwarder.forward(route.get(), environmentId, ticket, nodePeer);
      return;
    }
    attachLocally(session, ticket);
  }

  /** 本节点即 owner（或单节点部署）：直接消费 ticket 并交付配对。 */
  private void attachLocally(WebSocketSession session, String ticket) throws Exception {
    Optional<IdeStreamTicketService.Consumed> consumed = streamTickets.consume(ticket);
    if (consumed.isEmpty()) {
      reject(session, "reverse stream ticket is unknown, consumed or expired");
      return;
    }
    IdeStreamTicketService.Consumed stream = consumed.get();
    Optional<IdeSessionIdentity> current = transport.ideSessionIdentity(stream.environmentId());
    if (current.isEmpty() || !current.get().equals(stream.identity())) {
      reject(session, "reverse stream ticket generation is no longer current");
      return;
    }
    WebSocketIdeStreamPeer peer = new WebSocketIdeStreamPeer(session);
    if (!pairings.pair(ticket, peer)) {
      peer.onTransportClosed();
      reject(session, "reverse stream exchange is no longer waiting");
      return;
    }
    peers.put(session.getId(), peer);
  }

  private static EnvironmentId environmentOf(WebSocketSession session) {
    if (session.getUri() == null || session.getUri().getQuery() == null) {
      return null;
    }
    for (String pair : session.getUri().getQuery().split("&")) {
      int equals = pair.indexOf('=');
      if (equals > 0 && "environment".equals(pair.substring(0, equals))) {
        try {
          return EnvironmentId.parse(pair.substring(equals + 1));
        } catch (IllegalArgumentException error) {
          return null;
        }
      }
    }
    return null;
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
    log.debug("reverse stream transport error on {}: {}", session.getId(), exception.getMessage());
    detach(session);
    if (session.isOpen()) {
      session.close(CloseStatus.SERVER_ERROR);
    }
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

  private static void reject(WebSocketSession session, String reason) throws Exception {
    log.debug("rejected reverse stream connection {}: {}", session.getId(), reason);
    session.close(REJECTED);
  }

  private static String ticketOf(WebSocketSession session) {
    if (session.getUri() == null) {
      return null;
    }
    String path = session.getUri().getPath();
    if (path == null) {
      return null;
    }
    String trimmed = path.replaceAll("/+$", "");
    int slash = trimmed.lastIndexOf('/');
    String ticket = slash >= 0 ? trimmed.substring(slash + 1) : trimmed;
    return ticket.isEmpty() ? null : ticket;
  }
}
