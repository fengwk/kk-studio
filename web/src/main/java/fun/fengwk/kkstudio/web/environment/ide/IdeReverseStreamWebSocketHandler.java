package fun.fengwk.kkstudio.web.environment.ide;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

import fun.fengwk.kkstudio.harness.environment.server.IdeCommandTransport;
import fun.fengwk.kkstudio.harness.environment.server.IdeSessionIdentity;
import fun.fengwk.kkstudio.harness.environment.server.IdeStreamPairingRegistry;
import fun.fengwk.kkstudio.harness.environment.server.IdeStreamTicketService;

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
  private final Map<String, WebSocketIdeStreamPeer> peers = new ConcurrentHashMap<>();

  public IdeReverseStreamWebSocketHandler(
      IdeStreamTicketService streamTickets,
      IdeStreamPairingRegistry<IdeStreamPeer> pairings,
      IdeCommandTransport transport) {
    this.streamTickets = Objects.requireNonNull(streamTickets, "streamTickets");
    this.pairings = Objects.requireNonNull(pairings, "pairings");
    this.transport = Objects.requireNonNull(transport, "transport");
  }

  @Override
  public void afterConnectionEstablished(WebSocketSession session) throws Exception {
    String ticket = ticketOf(session);
    if (ticket == null) {
      reject(session, "reverse stream handshake without a ticket");
      return;
    }
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

  @Override
  protected void handleTextMessage(WebSocketSession session, TextMessage message) {
    offer(session, new IdeStreamPeer.Inbound.Text(message.getPayload()));
  }

  @Override
  protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
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

  private void offer(WebSocketSession session, IdeStreamPeer.Inbound frame) {
    WebSocketIdeStreamPeer peer = peers.get(session.getId());
    if (peer != null) {
      peer.offer(frame);
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
