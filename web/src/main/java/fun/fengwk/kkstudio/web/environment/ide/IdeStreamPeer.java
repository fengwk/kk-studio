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

  /** 阻塞直到有入站帧或超时；返回 {@code null} 表示本次没有入站帧（需结合 {@link #closed()} 判断连接是否已关闭）。 */
  Inbound poll(Duration timeout) throws InterruptedException;

  /** 连接是否已关闭（对端断开、发送失败或本端关闭）。 */
  boolean closed();

  @Override
  void close();

  /** 入站帧：文本控制帧或二进制帧。 */
  sealed interface Inbound {

    record Text(String json) implements Inbound {}

    record Binary(byte[] frame) implements Inbound {}
  }
}
