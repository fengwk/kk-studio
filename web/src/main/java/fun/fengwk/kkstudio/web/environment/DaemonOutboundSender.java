package fun.fengwk.kkstudio.web.environment;

import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import fun.fengwk.kkstudio.harness.environment.server.DaemonOfferResult;
import fun.fengwk.kkstudio.share.notification.NotificationOutbox;
import fun.fengwk.kkstudio.share.notification.NotificationPeerLink;

import java.io.IOException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 每个 Environment Daemon WebSocket 连接的有界串行出站发送器。
 *
 * <p>{@link #offerText(String)} 只做确定性入队：本地容量/字节预算拒绝为 {@link DaemonOfferResult#BUSY}，围栏已关闭为 {@link
 * DaemonOfferResult#CLOSED}，二者都保证帧未被发送且不改变连接可用性。逻辑消息与公平游标由共享 {@link NotificationPeerLink}/{@link
 * NotificationOutbox} 持有：唯一 sender 虚拟线程按 batch 轮转，每批最多 {@code sendBatchFrames} 片，排定 deadline 后才实际调用
 * Spring {@link WebSocketSession#sendMessage}；完整包保持预算直到末批完成。发送异常或超时会先关闭入队围栏，再在锁外通知 Gateway 并尽力关闭
 * WebSocket；超时回调仅作状态裁决，实际关闭和通知均从共享 timer 线程派发出去。
 *
 * <p>{@link #close()} 幂等地终止 link（清空未发送包并释放全部字节）并禁止后续入队；外部 {@code sendMessage}/{@code close} 调用都不持有
 * sender 状态锁。已获发送许可的在途批可以与 close 并发进入底层调用，由 session close/线程中断终止；围栏后的排队包及新入队包绝不会开始发送。
 */
final class DaemonOutboundSender implements AutoCloseable {

  private final WebSocketSession session;
  private final NotificationPeerLink link;
  private final long sendTimeoutMillis;
  private final Consumer<Throwable> failureHandler;
  private final ScheduledThreadPoolExecutor deadlineTimer;
  private final Object lock = new Object();
  private final Thread worker;
  private NotificationOutbox.Batch inFlight;
  private ScheduledFuture<?> deadline;
  private boolean closed;
  private boolean closeAfterFlush;
  private boolean failed;
  private boolean transportClosed;

  DaemonOutboundSender(
      WebSocketSession session,
      NotificationPeerLink link,
      int sendTimeoutMillis,
      ScheduledThreadPoolExecutor deadlineTimer,
      Consumer<Throwable> failureHandler) {
    Objects.requireNonNull(session, "session");
    Objects.requireNonNull(link, "link");
    if (sendTimeoutMillis <= 0) {
      throw new IllegalArgumentException("sendTimeoutMillis must be positive");
    }
    // 帧的串行化、逻辑/字节预算与发送超时都由本类唯一 sender 线程与共享 link 持有；不再叠加 Spring 的并发装饰器，
    // 避免出现第二份无界缓冲与双重超时语义。
    this.session = session;
    this.link = link;
    this.sendTimeoutMillis = sendTimeoutMillis;
    this.deadlineTimer = Objects.requireNonNull(deadlineTimer, "deadlineTimer");
    this.failureHandler = Objects.requireNonNull(failureHandler, "failureHandler");
    this.worker =
        Thread.ofVirtual().name("environment-daemon-outbound-" + session.getId()).start(this::run);
  }

  /**
   * 非阻塞入队并返回确定性结果。
   *
   * <p>共享 outbox 的队列/字节预算拒绝为 {@link DaemonOfferResult#BUSY}，sender 已关闭或正在 drain-close 时为 {@link
   * DaemonOfferResult#CLOSED}；两者都表示该帧肯定未发送，连接语义不变。
   */
  DaemonOfferResult offerText(String text) {
    Objects.requireNonNull(text, "text");
    boolean accepted = false;
    // 检查围栏、入队与唤醒在同一 sender monitor 内原子完成：link.offer 是纯内存操作且不回调，
    // 因此 close()/closeAfterFlush() 不可能与准入交错出一个已围栏的包。
    synchronized (lock) {
      if (closed || closeAfterFlush) {
        return DaemonOfferResult.CLOSED;
      }
      if (link.isClosed()) {
        return DaemonOfferResult.CLOSED;
      }
      accepted = link.offer(UUID.randomUUID(), text);
      if (accepted) {
        lock.notifyAll();
      }
    }
    if (accepted) {
      return DaemonOfferResult.ACCEPTED;
    }
    return link.isClosed() ? DaemonOfferResult.CLOSED : DaemonOfferResult.BUSY;
  }

  boolean isOpen() {
    synchronized (lock) {
      return !closed && !closeAfterFlush && session.isOpen() && !link.isClosed();
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
      inFlight = null;
      cancelDeadline();
      unreliable = failed;
      lock.notifyAll();
    }
    link.close();
    if (shouldClose && Thread.currentThread() != worker) {
      worker.interrupt();
    }
    requestTransportClose(unreliable ? CloseStatus.SESSION_NOT_RELIABLE : CloseStatus.NORMAL);
  }

  /** 禁止新入队，保持已接受包顺序，最后一包发送完成后关闭 transport。 */
  void closeAfterFlush() {
    boolean closeNow;
    synchronized (lock) {
      if (closed || closeAfterFlush) {
        return;
      }
      closeAfterFlush = true;
      closeNow = link.pendingBytes() == 0;
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
      NotificationOutbox.Batch batch = takeNext();
      if (batch == null) {
        closeAfterDrain();
        return;
      }
      try {
        synchronized (lock) {
          if (closed) {
            return;
          }
          inFlight = batch;
          deadline =
              deadlineTimer.schedule(
                  () -> onDeadline(batch), sendTimeoutMillis, TimeUnit.MILLISECONDS);
        }
        if (!session.isOpen()) {
          throw new IllegalStateException("daemon WebSocket session is closed");
        }
        // isOpen 是外部调用，期间 close 可以建立围栏；再次检查后才允许在途包开始 send。
        // 最后检查与实际 send 之间仍允许 close 并发，无法原子化外部阻塞调用。
        if (isClosed()) {
          return;
        }
        for (String frame : batch.frames()) {
          // 关闭/失败后绝不继续整批 native write，逐片复核围栏。
          if (isClosed()) {
            link.complete(batch, false);
            return;
          }
          session.sendMessage(new TextMessage(frame));
        }
        link.complete(batch, true);
      } catch (Exception error) {
        link.complete(batch, false);
        fail(error);
      } finally {
        complete(batch);
      }
    }
  }

  private NotificationOutbox.Batch takeNext() {
    synchronized (lock) {
      while (!closed && !closeAfterFlush) {
        Optional<NotificationOutbox.Batch> next = link.pollBatch();
        if (next.isPresent()) {
          return next.get();
        }
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
      if (closed) {
        return null;
      }
      return link.pollBatch().orElse(null);
    }
  }

  private void complete(NotificationOutbox.Batch batch) {
    synchronized (lock) {
      if (inFlight != batch) {
        return;
      }
      cancelDeadline();
      inFlight = null;
    }
  }

  private boolean isClosed() {
    synchronized (lock) {
      return closed;
    }
  }

  private void cancelDeadline() {
    if (deadline != null) {
      deadline.cancel(false);
      deadline = null;
    }
  }

  private void onDeadline(NotificationOutbox.Batch batch) {
    IllegalStateException error =
        new IllegalStateException(
            "daemon WebSocket send timed out after " + sendTimeoutMillis + "ms");
    synchronized (lock) {
      if (closed || inFlight != batch) {
        return;
      }
      fenceFailure();
    }
    // 定时器仅作状态裁决；用户回调、transport close 和 worker 中断均在独立线程执行。
    Thread.startVirtualThread(
        () -> {
          link.complete(batch, false);
          requestTransportClose(CloseStatus.SESSION_NOT_RELIABLE);
          worker.interrupt();
          finishFailure(error);
        });
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
      // drain 完成也必须独立释放全部预算，不能依赖外部 handler 再调用 close。
      link.close();
      requestTransportClose(CloseStatus.NORMAL);
    }
  }

  private void fail(Throwable error) {
    synchronized (lock) {
      if (closed) {
        return;
      }
      fenceFailure();
    }
    finishFailure(error);
  }

  private void fenceFailure() {
    failed = true;
    closed = true;
    inFlight = null;
    cancelDeadline();
    // 失败必须独立释放 link 预算，不依赖外部 close 调用。
    link.close();
    lock.notifyAll();
  }

  private void finishFailure(Throwable error) {
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
    synchronized (lock) {
      if (transportClosed) {
        return;
      }
      transportClosed = true;
    }
    Thread.startVirtualThread(() -> closeTransport(status));
  }

  private void closeTransport(CloseStatus status) {
    try {
      session.close(status);
    } catch (IOException ignored) {
      // transport 已不可用。
    }
  }
}
