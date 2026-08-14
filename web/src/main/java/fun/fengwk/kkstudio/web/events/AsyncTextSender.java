package fun.fengwk.kkstudio.web.events;

import jakarta.websocket.CloseReason;
import jakarta.websocket.RemoteEndpoint.Async;
import jakarta.websocket.SendHandler;
import jakarta.websocket.SendResult;
import jakarta.websocket.Session;

import java.util.ArrayDeque;
import java.util.Objects;

/**
 * 每 Session 的有界串行异步文本发送队列。
 *
 * <p>发送走 {@link jakarta.websocket.Session#getAsyncRemote()} 的 {@link Async#sendText(String,
 * SendHandler)}， 同一时刻只有一个 in-flight 发送（在 SendHandler 回调里发起下一帧），因此帧顺序严格串行且不占用任何常驻/阻塞
 * worker。队列满（过载）或发送失败时 {@link #fail} 清空队列、尽力发出 error 帧后在队尾关闭连接，让客户端重连 恢复；关闭后拒绝新入队。
 */
final class AsyncTextSender {

  static final int DEFAULT_CAPACITY = 512;

  private final Session session;
  private final int capacity;
  private final Object lock = new Object();
  private final ArrayDeque<String> queue;
  private boolean draining;
  private boolean failed;

  AsyncTextSender(Session session) {
    this(session, DEFAULT_CAPACITY);
  }

  AsyncTextSender(Session session, int capacity) {
    this.session = Objects.requireNonNull(session, "session");
    if (capacity <= 0) {
      throw new IllegalArgumentException("capacity must be positive");
    }
    this.capacity = capacity;
    this.queue = new ArrayDeque<>(capacity);
  }

  /** 入队一帧；未完成帧数（含在途）达到容量或已失败时返回 {@code false}（调用方负责按协议关闭连接）。 */
  boolean enqueue(String text) {
    Objects.requireNonNull(text, "text");
    synchronized (lock) {
      if (failed || queue.size() + (draining ? 1 : 0) >= capacity) {
        return false;
      }
      queue.add(text);
      if (!draining) {
        draining = true;
        sendNext();
      }
      return true;
    }
  }

  /**
   * 终止发送：清空队列，把 {@code errorFrame} 作为最后一帧发出，随后以 {@code code} 关闭连接。 已在途的发送完成后才关闭，保证 error 帧先于 close
   * 帧到达。
   */
  void fail(String errorFrame, CloseReason.CloseCodes code) {
    Objects.requireNonNull(errorFrame, "errorFrame");
    Objects.requireNonNull(code, "code");
    synchronized (lock) {
      if (failed) {
        return;
      }
      failed = true;
      this.reason = new CloseReason(code, "event channel failed");
      queue.clear();
      queue.add(errorFrame);
      if (!draining) {
        draining = true;
        sendNext();
      }
    }
  }

  boolean isFailed() {
    synchronized (lock) {
      return failed;
    }
  }

  private void sendNext() {
    String text;
    synchronized (lock) {
      text = queue.poll();
      if (text == null) {
        draining = false;
      }
    }
    if (text == null) {
      if (failed) {
        closeSession();
      }
      return;
    }
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
                sendNext();
              }
            });
  }

  private void sendFailed(Throwable error) {
    synchronized (lock) {
      failed = true;
      queue.clear();
    }
    closeSession();
  }

  private void closeSession() {
    try {
      session.close(reason);
    } catch (Exception error) {
      // 连接已不可用
    }
  }

  private CloseReason reason =
      new CloseReason(CloseReason.CloseCodes.UNEXPECTED_CONDITION, "event channel send failed");
}
