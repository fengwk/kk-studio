package fun.fengwk.kkstudio.web.environment.ide;

import java.time.Duration;

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

  @Override
  void close();

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
