package fun.fengwk.kkstudio.web.environment;

import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import fun.fengwk.kkstudio.harness.environment.server.DaemonOfferResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 每个 Environment Daemon WebSocket 连接的有界串行出站发送器。
 *
 * <p>{@link #offerText(String)} 只在内存中完成有界入队并返回确定性结果：容量/字节预算拒绝为 {@link
 * DaemonOfferResult#BUSY}，围栏已关闭为 {@link DaemonOfferResult#CLOSED}，二者都保证帧未被发送且不改变连接可用性。唯一 sender
 * 虚拟线程按 入队顺序调用 Spring {@link WebSocketSession#sendMessage}；待发预算同时限制帧数与 UTF-8
 * 字节数，并包含当前正在发送的帧。发送异常或超时会先关闭 入队围栏，再在锁外通知 Gateway 收敛连接，最后尽力关闭 WebSocket。
 *
 * <p>{@link #close()} 幂等地清空未发送帧并禁止后续入队；外部 {@code sendMessage}/{@code close} 调用都不持有 sender
 * 状态锁。已经进入底层阻塞调用的单帧由 session close/线程中断终止，后续帧绝不会继续发送。
 */
final class DaemonOutboundSender implements AutoCloseable {

  private final WebSocketSession session;
  private final int capacity;
  private final long maxBytes;
  private final long sendTimeoutMillis;
  private final Consumer<Throwable> failureHandler;
  private final Object lock = new Object();
  private final ArrayDeque<Frame> queue;
  private final Thread worker;
  private int outstandingFrames;
  private long outstandingBytes;
  private Frame inFlight;
  private boolean closed;
  private boolean closeAfterFlush;
  private boolean failed;
  private boolean transportClosed;

  DaemonOutboundSender(
      WebSocketSession session,
      int capacity,
      int maxBytes,
      int sendTimeoutMillis,
      Consumer<Throwable> failureHandler) {
    Objects.requireNonNull(session, "session");
    if (capacity <= 0) {
      throw new IllegalArgumentException("capacity must be positive");
    }
    if (maxBytes <= 0) {
      throw new IllegalArgumentException("maxBytes must be positive");
    }
    if (sendTimeoutMillis <= 0) {
      throw new IllegalArgumentException("sendTimeoutMillis must be positive");
    }
    // 帧的串行化、帧数/字节预算与发送超时都由本类唯一 sender 线程持有；不再叠加 Spring 的并发装饰器，
    // 避免出现第二份无界缓冲与双重超时语义。
    this.session = session;
    this.capacity = capacity;
    this.maxBytes = maxBytes;
    this.sendTimeoutMillis = sendTimeoutMillis;
    this.failureHandler = Objects.requireNonNull(failureHandler, "failureHandler");
    this.queue = new ArrayDeque<>(capacity);
    this.worker =
        Thread.ofVirtual().name("environment-daemon-outbound-" + session.getId()).start(this::run);
  }

  /**
   * 非阻塞入队并返回确定性结果。
   *
   * <p>待发帧数/UTF-8 字节达到上限或单帧超过字节上限时返回 {@link DaemonOfferResult#BUSY}，sender 已关闭或正在 drain-close 时返回
   * {@link DaemonOfferResult#CLOSED}；两者都表示该帧肯定未发送，连接语义不变。
   */
  DaemonOfferResult offerText(String text) {
    Objects.requireNonNull(text, "text");
    Frame frame = new Frame(text, text.getBytes(StandardCharsets.UTF_8).length);
    synchronized (lock) {
      if (closed || closeAfterFlush) {
        return DaemonOfferResult.CLOSED;
      }
      if (outstandingFrames >= capacity || outstandingBytes + frame.bytes() > maxBytes) {
        return DaemonOfferResult.BUSY;
      }
      queue.addLast(frame);
      outstandingFrames++;
      outstandingBytes += frame.bytes();
      lock.notifyAll();
      return DaemonOfferResult.ACCEPTED;
    }
  }

  boolean isOpen() {
    synchronized (lock) {
      return !closed && !closeAfterFlush && session.isOpen();
    }
  }

  @Override
  public void close() {
    boolean shouldClose;
    boolean unreliable;
    synchronized (lock) {
      if (closeAfterFlush) {
        return;
      }
      shouldClose = !closed;
      closed = true;
      queue.clear();
      outstandingFrames = 0;
      outstandingBytes = 0L;
      inFlight = null;
      unreliable = failed;
      lock.notifyAll();
    }
    if (shouldClose && Thread.currentThread() != worker) {
      worker.interrupt();
    }
    requestTransportClose(unreliable ? CloseStatus.SESSION_NOT_RELIABLE : CloseStatus.NORMAL);
  }

  /** 禁止新入队，保持已接受帧顺序，最后一帧发送完成后关闭 transport。 */
  void closeAfterFlush() {
    boolean closeNow;
    synchronized (lock) {
      if (closed || closeAfterFlush) {
        return;
      }
      closeAfterFlush = true;
      closeNow = outstandingFrames == 0;
      if (closeNow) {
        closed = true;
      }
      lock.notifyAll();
    }
    if (closeNow) {
      requestTransportClose(CloseStatus.NORMAL);
    }
  }

  private void run() {
    while (true) {
      Frame frame = takeNext();
      if (frame == null) {
        closeAfterDrain();
        return;
      }
      CountDownLatch completed = new CountDownLatch(1);
      Thread.startVirtualThread(() -> enforceTimeout(completed));
      try {
        if (!session.isOpen()) {
          throw new IllegalStateException("daemon WebSocket session is closed");
        }
        session.sendMessage(new TextMessage(frame.text()));
      } catch (Exception error) {
        fail(error);
      } finally {
        completed.countDown();
        complete(frame);
      }
    }
  }

  private Frame takeNext() {
    synchronized (lock) {
      while (queue.isEmpty() && !closed && !closeAfterFlush) {
        try {
          lock.wait();
        } catch (InterruptedException error) {
          if (closed) {
            return null;
          }
          Thread.currentThread().interrupt();
          return null;
        }
      }
      if (closed || (closeAfterFlush && queue.isEmpty())) {
        return null;
      }
      inFlight = queue.removeFirst();
      return inFlight;
    }
  }

  private void complete(Frame frame) {
    synchronized (lock) {
      if (inFlight != frame) {
        return;
      }
      inFlight = null;
      outstandingFrames--;
      outstandingBytes -= frame.bytes();
    }
  }

  private void enforceTimeout(CountDownLatch completed) {
    try {
      if (!completed.await(sendTimeoutMillis, TimeUnit.MILLISECONDS)) {
        fail(
            new IllegalStateException(
                "daemon WebSocket send timed out after " + sendTimeoutMillis + "ms"));
      }
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
    }
  }

  private void closeAfterDrain() {
    boolean shouldClose;
    synchronized (lock) {
      shouldClose = closeAfterFlush && !closed;
      if (shouldClose) {
        closed = true;
      }
    }
    if (shouldClose) {
      requestTransportClose(CloseStatus.NORMAL);
    }
  }

  private void fail(Throwable error) {
    boolean notify;
    synchronized (lock) {
      notify = !closed;
      if (notify) {
        failed = true;
        closed = true;
        queue.clear();
        outstandingFrames = 0;
        outstandingBytes = 0L;
        inFlight = null;
        lock.notifyAll();
      }
    }
    if (!notify) {
      return;
    }
    try {
      failureHandler.accept(error);
    } catch (RuntimeException ignored) {
      // Gateway 收敛失败不能阻止 transport 自身关闭。
    } finally {
      requestTransportClose(CloseStatus.SESSION_NOT_RELIABLE);
      if (Thread.currentThread() != worker) {
        worker.interrupt();
      }
    }
  }

  private void requestTransportClose(CloseStatus status) {
    Thread.startVirtualThread(() -> closeTransport(status));
  }

  private void closeTransport(CloseStatus status) {
    synchronized (lock) {
      if (transportClosed) {
        return;
      }
      transportClosed = true;
    }
    try {
      session.close(status);
    } catch (IOException ignored) {
      // transport 已不可用。
    }
  }

  private record Frame(String text, int bytes) {}
}
