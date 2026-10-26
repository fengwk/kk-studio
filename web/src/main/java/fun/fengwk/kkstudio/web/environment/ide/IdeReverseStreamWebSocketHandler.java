package fun.fengwk.kkstudio.web.environment.ide;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.ide.IdeCommandTransport;
import fun.fengwk.kkstudio.ide.IdeGenerationState;
import fun.fengwk.kkstudio.ide.stream.IdeStreamPairingRegistry;
import fun.fengwk.kkstudio.ide.stream.IdeStreamTicketService;
import fun.fengwk.kkstudio.platform.environment.cluster.ClusterNodeRegistry;
import fun.fengwk.kkstudio.platform.environment.cluster.ClusterOwnerRoute;

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

  private static final CloseStatus FRAME_BUDGET_EXCEEDED =
      CloseStatus.POLICY_VIOLATION.withReason("inbound frame budget exceeded");

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
    session.setBinaryMessageSizeLimit(IdeStreamFrameBridge.MAX_BINARY_FRAME_BYTES);
    session.setTextMessageSizeLimit(IdeStreamFrameBridge.MAX_TEXT_FRAME_BYTES);
    String ticket = ticketOf(session);
    EnvironmentId environmentId = environmentOf(session);
    if (ticket == null || environmentId == null) {
      reject(session, "reverse stream handshake without a ticket or environment");
      return;
    }
    Optional<ClusterOwnerRoute> route = clusterNodeRegistry.route(environmentId);
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
    // 同一实例在控制连接短暂断开时回拨的反向流仍属当前代际；只拒绝已被另一个实例接管的代际。
    if (IdeGenerationState.of(transport, consumed.get().identity())
        == IdeGenerationState.REPLACED) {
      reject(session, "reverse stream ticket generation has been replaced");
      return;
    }
    WebSocketIdeStreamPeer peer = new WebSocketIdeStreamPeer(session);
    if (!pairings.pair(ticket, peer)) {
      peer.onTransportClosed(REJECTED);
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
    detach(session, status);
  }

  @Override
  public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
    log.info(
        "reverse stream transport failed; phase=IDE_REVERSE_TRANSPORT; exception={}",
        exception.getClass().getSimpleName());
    detach(
        session,
        CloseStatus.NO_CLOSE_FRAME.withReason(
            "transport error: " + exception.getClass().getSimpleName()));
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
      // 守约的对端不可能超出由 credit 推导的入站上界：这是协议错误，fail-closed 关闭连接，绝不无界缓冲。
      log.warn(
          "reverse stream {} exceeded the credit-derived inbound frame budget; closing",
          session.getId());
      detach(session, FRAME_BUDGET_EXCEEDED);
      session.close(FRAME_BUDGET_EXCEEDED);
    }
  }

  private void detach(WebSocketSession session, CloseStatus status) {
    WebSocketIdeStreamPeer peer = peers.remove(session.getId());
    if (peer != null) {
      peer.onTransportClosed(status);
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
