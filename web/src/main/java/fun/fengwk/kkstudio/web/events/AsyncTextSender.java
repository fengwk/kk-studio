package fun.fengwk.kkstudio.web.events;

import jakarta.websocket.CloseReason;
import jakarta.websocket.RemoteEndpoint.Async;
import jakarta.websocket.SendHandler;
import jakarta.websocket.SendResult;
import jakarta.websocket.Session;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Objects;

/**
 * 每 Session 的有界串行异步文本发送队列。
 *
 * <p>发送走 {@link jakarta.websocket.Session#getAsyncRemote()} 的 {@link Async#sendText(String,
 * SendHandler)}， 同一时刻只有一个 in-flight 发送（在 SendHandler 回调里发起下一帧），因此帧顺序严格串行且不占用任何常驻/阻塞
 * worker。AsyncRemote 设置有限 send timeout（10s），卡死的对端不会无限占用发送链。
 *
 * <p>待发缓冲按「帧数（{@link #DEFAULT_CAPACITY}）+ 总 UTF-8 字节（{@link #DEFAULT_MAX_BYTES}，含 in-flight
 * 帧）」双限；任一越界或已失败时 {@link #enqueue} 返回 {@code false}（调用方负责按协议关闭连接）。
 *
 * <p>{@code sendText} 一律在 sender lock 之外调用：锁内只决定是否启动 drain 并转移帧/计数，锁外完成发送；SendHandler
 * 完成回调驱动下一帧。队列满（过载）或发送失败时 {@link #fail} 清空队列、尽力发出 error 帧后在队尾关闭连接，让客户端重连 恢复；关闭后拒绝新入队。{@code
 * sendText} 同步抛异常（如连接已断）也立即关闭连接。
 */
final class AsyncTextSender {

  static final int DEFAULT_CAPACITY = 512;
  static final long DEFAULT_MAX_BYTES = 2L * 1024 * 1024;
  static final long SEND_TIMEOUT_MILLIS = 10_000L;

  private final Session session;
  private final int capacity;
  private final long maxBytes;
  private final Object lock = new Object();
  private final ArrayDeque<String> queue;
  private long queuedBytes;
  private long inFlightBytes;
  private boolean draining;
  private boolean failed;
  private CloseReason reason =
      new CloseReason(CloseReason.CloseCodes.UNEXPECTED_CONDITION, "event channel send failed");

  AsyncTextSender(Session session) {
    this(session, DEFAULT_CAPACITY, DEFAULT_MAX_BYTES);
  }

  AsyncTextSender(Session session, int capacity) {
    this(session, capacity, DEFAULT_MAX_BYTES);
  }

  /** 测试可注入字节上限。 */
  AsyncTextSender(Session session, int capacity, long maxBytes) {
    this.session = Objects.requireNonNull(session, "session");
    if (capacity <= 0) {
      throw new IllegalArgumentException("capacity must be positive");
    }
    if (maxBytes <= 0) {
      throw new IllegalArgumentException("maxBytes must be positive");
    }
    this.capacity = capacity;
    this.maxBytes = maxBytes;
    this.queue = new ArrayDeque<>(capacity);
    session.getAsyncRemote().setSendTimeout(SEND_TIMEOUT_MILLIS);
  }

  /** 入队一帧；待发帧数（含在途）达到容量、待发 UTF-8 字节（含在途）达到上限或已失败时返回 {@code false}。 单帧本身超过字节上限也拒绝。 */
  boolean enqueue(String text) {
    Objects.requireNonNull(text, "text");
    int bytes = utf8Bytes(text);
    boolean startDrain;
    synchronized (lock) {
      if (failed
          || queue.size() + (draining ? 1 : 0) >= capacity
          || queuedBytes + (draining ? inFlightBytes : 0) + bytes > maxBytes) {
        return false;
      }
      queue.add(text);
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
      queue.add(errorFrame);
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

  boolean isFailed() {
    synchronized (lock) {
      return failed;
    }
  }

  /** 取下一帧并发送；SendHandler 完成回调驱动下一帧，全程不持有 sender lock 调用外部 {@code sendText}。 */
  private void drain() {
    String text;
    synchronized (lock) {
      text = queue.poll();
      if (text == null) {
        draining = false;
        inFlightBytes = 0;
      } else {
        int bytes = utf8Bytes(text);
        queuedBytes -= bytes;
        inFlightBytes = bytes;
      }
    }
    if (text == null) {
      closeIfFailed();
      return;
    }
    try {
      session
          .getAsyncRemote()
          .sendText(
              text,
              new SendHandler() {
                @Override
                public void onResult(SendResult result) {
                  if (!result.isOK()) {
                    sendFailed(result.getException());
                    return;
                  }
                  drain();
                }
              });
    } catch (RuntimeException error) {
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
