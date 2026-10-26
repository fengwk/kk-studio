package fun.fengwk.kkstudio.web.environment.ide;

import org.springframework.web.socket.CloseStatus;

import java.time.Duration;
import java.util.Set;

/**
 * 一条已配对的反向数据连接（Daemon 侧）在 Gateway 内的窄句柄：发送 wire 帧、拉取入站帧、关闭。
 *
 * <p>由 stream WebSocket 端点实现并交给 {@link IdeStreamPairingRegistry} 交付；数据面在单线程内驱动 {@link
 * #sendText}/{@link #sendBinary} 与 {@link #poll}，因此不需要额外的并发保证。
 */
public interface IdeStreamPeer extends AutoCloseable {

  void sendText(String frame);

  void sendBinary(byte[] frame);

  /**
   * 发送一个消费 credit 的 ACK 帧。
   *
   * <p>ACK 只是对已真实交付字节的消费反馈：连接断开后这些反馈可以安全丢弃（不得因此丢弃已入队待交付的响应），因此已关闭的 peer 收到 ACK 不报错。数据与其它控制帧在关闭后仍必须
   * fail-closed。
   */
  void sendAcknowledgement(byte[] frame);

  /** 阻塞直到有入站帧或超时；返回 {@code null} 表示本次没有入站帧（需结合 {@link #closed()} 判断连接是否已关闭）。 */
  Inbound poll(Duration timeout) throws InterruptedException;

  /** 连接是否已关闭（对端断开、发送失败或本端关闭）。 */
  boolean closed();

  /**
   * 对端或传输给出的关闭状态；连接仍打开或由本端主动关闭时为 {@code null}。
   *
   * <p>反向流的关闭码是 Daemon 转述的 code-server 关闭码或协议错误原因，数据面据此记录关闭方并向浏览器原样转述，而不是一律改写成 1011。
   */
  CloseStatus remoteCloseStatus();

  /** 以指定状态关闭连接（跨节点转述对端关闭码、协议错误等）。 */
  void close(CloseStatus status);

  /** 以正常状态关闭连接。 */
  @Override
  void close();

  /** 把对端关闭状态转换为可以在另一条 WebSocket 上发送的状态：合法的应用关闭码原样保留；缺失、无关闭帧（1006）等不能发送的状态统一为 1011 并注明连接丢失。 */
  static CloseStatus relayable(CloseStatus status) {
    if (status == null) {
      return STREAM_LOST;
    }
    int code = status.getCode();
    boolean sendable = SENDABLE_PROTOCOL_CODES.contains(code) || (code >= 3000 && code <= 4999);
    return sendable ? status : STREAM_LOST;
  }

  /** 1012–1014 虽有定义，但 Jakarta WebSocket 实际关闭时会拒绝，因此不在可转述集合内。 */
  Set<Integer> SENDABLE_PROTOCOL_CODES =
      Set.of(1000, 1001, 1002, 1003, 1007, 1008, 1009, 1010, 1011);

  /** 对端没有给出可转述关闭码时的统一状态。 */
  CloseStatus STREAM_LOST = CloseStatus.SERVER_ERROR.withReason("ide stream lost");

  /**
   * 入站帧：文本控制帧或二进制帧。
   *
   * <p>{@code last} 是 WebSocket FIN 标记：反向数据面的帧始终是完整消息（{@code true}），浏览器 WS 单跳桥接则必须逐字保留 partial
   * 语义，因此这里显式携带 FIN 而不是把分段悄悄拼成一条消息。
   */
  sealed interface Inbound {

    record Text(String json, boolean last) implements Inbound {

      Text(String json) {
        this(json, true);
      }
    }

    record Binary(byte[] frame, boolean last) implements Inbound {

      Binary(byte[] frame) {
        this(frame, true);
      }
    }
  }
}
