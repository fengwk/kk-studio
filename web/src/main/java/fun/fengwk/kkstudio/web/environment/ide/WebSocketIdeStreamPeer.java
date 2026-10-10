package fun.fengwk.kkstudio.web.environment.ide;

import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 由真实反向 WebSocket 连接支撑的 {@link IdeStreamPeer}：入站帧入有界队列，出站帧在会话锁内串行发送。
 *
 * <p>数据面线程在单线程内驱动 {@link #sendText}/{@link #sendBinary}/{@link #poll}，WebSocket 容器线程只负责入队，因此
 * 两线程之间通过队列交接；Spring {@link WebSocketSession} 的并发发送不被允许，故出站发送加锁串行化。
 */
final class WebSocketIdeStreamPeer implements IdeStreamPeer {

  private final WebSocketSession session;
  private final LinkedBlockingQueue<Inbound> inbound = new LinkedBlockingQueue<>();
  private final Object sendLock = new Object();
  private volatile boolean closed;

  WebSocketIdeStreamPeer(WebSocketSession session) {
    this.session = Objects.requireNonNull(session, "session");
  }

  @Override
  public void sendText(String frame) {
    send(new TextMessage(frame));
  }

  @Override
  public void sendBinary(byte[] frame) {
    send(new BinaryMessage(frame));
  }

  private void send(WebSocketMessage<?> message) {
    if (closed) {
      throw new IllegalStateException("reverse stream peer is closed");
    }
    synchronized (sendLock) {
      try {
        session.sendMessage(message);
      } catch (IOException error) {
        closed = true;
        throw new IllegalStateException("failed to send on the reverse stream", error);
      }
    }
  }

  @Override
  public Inbound poll(Duration timeout) throws InterruptedException {
    long millis = timeout.toMillis();
    if (millis <= 0L) {
      return inbound.poll();
    }
    return inbound.poll(millis, TimeUnit.MILLISECONDS);
  }

  /** WebSocket 容器线程入队一个入站帧。 */
  void offer(Inbound frame) {
    inbound.add(frame);
  }

  @Override
  public boolean closed() {
    return closed;
  }

  @Override
  public void close() {
    if (closed) {
      return;
    }
    closed = true;
    try {
      session.close(CloseStatus.NORMAL);
    } catch (IOException ignored) {
      // 会话在关闭前已断开。
    }
  }

  /** 连接侧关闭（对端断开/错误）：标记关闭，令等待方在队列耗尽后返回 null。 */
  void onTransportClosed() {
    closed = true;
  }
}
