package fun.fengwk.kkstudio.web.events;

import org.eclipse.jetty.websocket.api.Callback;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.StatusCode;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 每 Session 的有界串行异步文本与 ping 发送队列。
 *
 * <p>发送基于 Jetty 原生 {@link org.eclipse.jetty.websocket.api.Session} 的 {@link
 * Session#sendText(String, Callback)} 与 {@link Session#sendPing(ByteBuffer, Callback)}， 文本与原生 ping
 * 全部走异步 {@link Callback}，同一时刻只有一个 in-flight 发送（在 Callback 回调里发起下一帧）， 严格保证帧串行按序且绝不并发写入底层的网络通道。
 *
 * <p>单帧发送超时通过显式截止时间（基于调度器）实现：当一帧发起在途发送时调度超时任务， 若在超时时间内未收到完成回调，则触发发送超时并关闭连接；完成回调（succeed /
 * fail）到达时立即取消超时任务。
 *
 * <p>待发缓冲按「帧数（capacity）+ 总 UTF-8 字节（maxBytes，含 in-flight 帧）」双限；任一越界或已失败时 {@link #enqueue} 返回 {@code
 * false}。
 *
 * <p>发送调用一律在 sender lock 之外发起：锁内只决定是否启动 drain 并转移帧/计数，锁外完成异步发送； Callback 完成回调驱动下一帧。队列满（过载）或发送失败时
 * {@link #fail} 清空队列、尽力发出 error 帧后在队尾关闭连接； 关闭后拒绝新入队。发送同步抛异常（如连接已断）也立即关闭连接。
 */
final class AsyncTextSender {

  private final Session session;
  private final int capacity;
  private final long maxBytes;
  private final long sendTimeoutMillis;
  private final ScheduledExecutorService scheduler;
  private final Object lock = new Object();
  private final ArrayDeque<Outbound> queue;
  private long queuedBytes;
  private long inFlightBytes;
  private boolean draining;
  private boolean failed;
  private int closeCode = StatusCode.SERVER_ERROR;
  private String closeReason = "event channel send failed";

  /** 同一物理写入链上的文本帧或原生 ping；ping 不占 UTF-8 字节但占队列槽位。 */
  private record Outbound(String text) {
    private static Outbound ping() {
      return new Outbound(null);
    }

    private boolean isPing() {
      return text == null;
    }
  }

  AsyncTextSender(
      Session session,
      int capacity,
      long maxBytes,
      long sendTimeoutMillis,
      ScheduledExecutorService scheduler) {
    this.session = Objects.requireNonNull(session, "session");
    this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
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
    this.sendTimeoutMillis = sendTimeoutMillis;
    this.queue = new ArrayDeque<>(capacity);
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
  void fail(String errorFrame, int code) {
    Objects.requireNonNull(errorFrame, "errorFrame");
    boolean startDrain;
    synchronized (lock) {
      if (failed) {
        return;
      }
      failed = true;
      this.closeCode = code;
      this.closeReason = "event channel failed";
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
  void close(int code, String reasonText) {
    Objects.requireNonNull(reasonText, "reasonText");
    synchronized (lock) {
      failed = true;
      closeCode = code;
      closeReason = reasonText;
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

  /** 取下一帧并发送；Callback 完成回调驱动下一帧，全程不持有 sender lock 调用外部发送。 */
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

    AtomicBoolean completed = new AtomicBoolean(false);
    ScheduledFuture<?> timeoutTask;
    try {
      timeoutTask = scheduleTimeout(completed);
    } catch (Throwable error) {
      completed.set(true);
      sendFailed(error);
      return;
    }

    Callback callback =
        new Callback() {
          @Override
          public void succeed() {
            if (completed.compareAndSet(false, true)) {
              cancelTimeout(timeoutTask);
              drain();
            }
          }

          @Override
          public void fail(Throwable x) {
            if (completed.compareAndSet(false, true)) {
              cancelTimeout(timeoutTask);
              sendFailed(x);
            }
          }
        };

    try {
      if (frame.isPing()) {
        session.sendPing(ByteBuffer.allocate(0), callback);
      } else {
        session.sendText(frame.text(), callback);
      }
    } catch (Throwable error) {
      if (completed.compareAndSet(false, true)) {
        cancelTimeout(timeoutTask);
        sendFailed(error);
      }
    }
  }

  private ScheduledFuture<?> scheduleTimeout(AtomicBoolean completed) {
    return scheduler.schedule(
        () -> {
          if (completed.compareAndSet(false, true)) {
            sendFailed(
                new TimeoutException(
                    "event channel send timed out after " + sendTimeoutMillis + "ms"));
          }
        },
        sendTimeoutMillis,
        TimeUnit.MILLISECONDS);
  }

  private static void cancelTimeout(ScheduledFuture<?> timeoutTask) {
    if (timeoutTask != null) {
      timeoutTask.cancel(false);
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
    session.close(closeCode, closeReason, Callback.NOOP);
  }

  private static int utf8Bytes(String text) {
    return text.getBytes(StandardCharsets.UTF_8).length;
  }
}
