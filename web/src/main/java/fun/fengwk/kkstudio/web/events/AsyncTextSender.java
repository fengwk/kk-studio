package fun.fengwk.kkstudio.web.events;

import jakarta.websocket.CloseReason;
import jakarta.websocket.RemoteEndpoint.Async;
import jakarta.websocket.SendHandler;
import jakarta.websocket.SendResult;
import jakarta.websocket.Session;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Objects;

/**
 * 每 Session 的有界串行异步文本发送队列。
 *
 * <p>发送走 {@link jakarta.websocket.Session#getAsyncRemote()} 的 {@link Async#sendText(String,
 * SendHandler)}， 同一时刻只有一个 in-flight 发送（在 SendHandler 回调里发起下一帧），因此帧顺序严格串行且不占用任何常驻/阻塞
 * worker。AsyncRemote 设置由装配方（SystemSettings.Advanced.applicationEventSendTimeoutMillis）传入的有限 send
 * timeout，卡死的对端不会无限占用发送链。
 *
 * <p>待发缓冲按「帧数（capacity）+ 总 UTF-8 字节（maxBytes，含 in-flight 帧）」双限，两者都由装配方从数据库 SystemSettings.Advanced
 * 传入；任一越界或已失败时 {@link #enqueue} 返回 {@code false}（调用方负责按协议关闭连接）。
 *
 * <p>{@code sendText} 一律在 sender lock 之外调用：锁内只决定是否启动 drain 并转移帧/计数，锁外完成发送；SendHandler
 * 完成回调驱动下一帧。队列满（过载）或发送失败时 {@link #fail} 清空队列、尽力发出 error 帧后在队尾关闭连接，让客户端重连 恢复；关闭后拒绝新入队。{@code
 * sendText} 同步抛异常（如连接已断）也立即关闭连接。
 */
final class AsyncTextSender {

  private final Session session;
  private final int capacity;
  private final long maxBytes;
  private final Object lock = new Object();
  private final ArrayDeque<Outbound> queue;
  private long queuedBytes;
  private long inFlightBytes;
  private boolean draining;
  private boolean failed;
  private CloseReason reason =
      new CloseReason(CloseReason.CloseCodes.UNEXPECTED_CONDITION, "event channel send failed");

  /** 同一物理写入链上的文本帧或原生 ping；ping 不占 UTF-8 字节但占队列槽位。 */
  private record Outbound(String text) {
    private static Outbound ping() {
      return new Outbound(null);
    }

    private boolean isPing() {
      return text == null;
    }
  }

  AsyncTextSender(Session session, int capacity, long maxBytes, long sendTimeoutMillis) {
    this.session = Objects.requireNonNull(session, "session");
    if (capacity <= 0) {
      throw new IllegalArgumentException("capacity must be positive");
    }
    if (maxBytes <= 0) {
      throw new IllegalArgumentException("maxBytes must be positive");
    }
    if (sendTimeoutMillis <= 0) {
      throw new IllegalArgumentException("sendTimeoutMillis must be positive");
    }
    this.capacity = capacity;
    this.maxBytes = maxBytes;
    this.queue = new ArrayDeque<>(capacity);
    session.getAsyncRemote().setSendTimeout(sendTimeoutMillis);
  }

  /** 入队一帧；待发帧数（含在途）达到容量、待发 UTF-8 字节（含在途）达到上限或已失败时返回 {@code false}。 单帧本身超过字节上限也拒绝。 */
  boolean enqueue(String text) {
    Objects.requireNonNull(text, "text");
    return enqueue(new Outbound(text), utf8Bytes(text));
  }

  /** 入队一个原生 ping；与文本帧共用同一串行物理写入链与有界队列。 */
  boolean enqueuePing() {
    return enqueue(Outbound.ping(), 0);
  }

  private boolean enqueue(Outbound frame, int bytes) {
    boolean startDrain;
    synchronized (lock) {
      if (failed
          || queue.size() + (draining ? 1 : 0) >= capacity
          || queuedBytes + (draining ? inFlightBytes : 0) + bytes > maxBytes) {
        return false;
      }
      queue.add(frame);
      queuedBytes += bytes;
      startDrain = !draining;
      if (startDrain) {
        draining = true;
      }
    }
    if (startDrain) {
      drain();
    }
    return true;
  }

  /**
   * 终止发送：清空队列，把 {@code errorFrame} 作为最后一帧发出，随后以 {@code code} 关闭连接。 已在途的发送完成后才关闭，保证 error 帧先于 close
   * 帧到达。
   */
  void fail(String errorFrame, CloseReason.CloseCodes code) {
    Objects.requireNonNull(errorFrame, "errorFrame");
    Objects.requireNonNull(code, "code");
    boolean startDrain;
    synchronized (lock) {
      if (failed) {
        return;
      }
      failed = true;
      this.reason = new CloseReason(code, "event channel failed");
      queue.clear();
      queuedBytes = 0;
      queue.add(new Outbound(errorFrame));
      queuedBytes += utf8Bytes(errorFrame);
      startDrain = !draining;
      if (startDrain) {
        draining = true;
      }
    }
    if (startDrain) {
      drain();
    }
  }

  /**
   * 丢弃尚未发送的帧并停止发送链，但不主动关闭物理连接。
   *
   * <p>用于连接已经结束（或正由其它路径关闭）时释放排队帧；失败状态或待发送的 error 帧不会被清掉。
   */
  void discard() {
    synchronized (lock) {
      if (failed) {
        return;
      }
      failed = true;
      queue.clear();
      queuedBytes = 0;
    }
  }

  /** 立即停止发送并以给定原因关闭物理连接（用于原生 pong 超时等无需再投递 error 帧的收敛路径）。 */
  void close(CloseReason.CloseCodes code, String reasonText) {
    Objects.requireNonNull(code, "code");
    Objects.requireNonNull(reasonText, "reasonText");
    synchronized (lock) {
      failed = true;
      reason = new CloseReason(code, reasonText);
      queue.clear();
      queuedBytes = 0;
    }
    closeSession();
  }

  boolean isFailed() {
    synchronized (lock) {
      return failed;
    }
  }

  /** 取下一帧并发送；SendHandler 完成回调驱动下一帧，全程不持有 sender lock 调用外部发送。 */
  private void drain() {
    Outbound frame;
    synchronized (lock) {
      frame = queue.poll();
      if (frame == null) {
        draining = false;
        inFlightBytes = 0;
      } else {
        inFlightBytes = frame.isPing() ? 0 : utf8Bytes(frame.text());
        queuedBytes -= inFlightBytes;
      }
    }
    if (frame == null) {
      closeIfFailed();
      return;
    }
    SendHandler handler =
        new SendHandler() {
          @Override
          public void onResult(SendResult result) {
            if (!result.isOK()) {
              sendFailed(result.getException());
              return;
            }
            drain();
          }
        };
    try {
      if (frame.isPing()) {
        // Tomcat 11 的 AsyncRemote.sendPing 实际走 WsRemoteEndpointImplBase.sendMessageBlock：
        // 同步取得 messagePartInProgress 信号量，由 BlockingSendHandler 等到该 ping 写完才返回。
        // 因此返回后继续 drain 仍是同一物理 writer 的串行写；不另建 ping 线程或发送队列。
        session.getAsyncRemote().sendPing(ByteBuffer.allocate(0));
        drain();
      } else {
        session.getAsyncRemote().sendText(frame.text(), handler);
      }
    } catch (RuntimeException | IOException error) {
      sendFailed(error);
    }
  }

  private void sendFailed(Throwable error) {
    synchronized (lock) {
      failed = true;
      queue.clear();
      queuedBytes = 0;
      inFlightBytes = 0;
    }
    closeSession();
  }

  private void closeIfFailed() {
    if (isFailed()) {
      closeSession();
    }
  }

  private void closeSession() {
    try {
      session.close(reason);
    } catch (Exception error) {
      // 连接已不可用
    }
  }

  private static int utf8Bytes(String text) {
    return text.getBytes(StandardCharsets.UTF_8).length;
  }
}
