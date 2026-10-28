package fun.fengwk.kkstudio.web.environment.ide;

import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.PingMessage;
import org.springframework.web.socket.WebSocketSession;

import fun.fengwk.kkstudio.ide.mux.MuxLimits;
import fun.fengwk.kkstudio.ide.mux.MuxTransport;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 一条真实数据 WebSocket 的物理读写：一个异步写入者、一个由上层单线程消费的有界入站队列，以及原生 Ping/Pong 心跳。
 *
 * <p>第一条 binary message 对应一个完整 wire 帧，上限为 12 字节头 + 65536 字节
 * payload。任何一次写失败立即封闭发送端，后续写入不再尝试，也不会通过装饰器重试 Tomcat 已损坏的写状态。入站队列最多 {@value #MAX_QUEUED_FRAMES}
 * 帧；满时容器线程等待形成背压，超时即 fail-closed。
 */
final class IdeDataWebSocketTransport implements MuxTransport {

  static final int MAX_MESSAGE_BYTES = MuxLimits.HEADER_BYTES + MuxLimits.MAX_PAYLOAD_BYTES;

  static final int MAX_QUEUED_FRAMES = 64;

  private static final long OFFER_TIMEOUT_MILLIS = 60_000L;

  private static final CloseStatus CLOSED_BY_SERVER =
      CloseStatus.GOING_AWAY.withReason("data connection closed");

  private final WebSocketSession session;
  private final ArrayBlockingQueue<byte[]> inbound = new ArrayBlockingQueue<>(MAX_QUEUED_FRAMES);
  private final Object sendLock = new Object();
  private final ExecutorService writer;
  private final AtomicBoolean closed = new AtomicBoolean();
  private volatile CloseStatus remoteCloseStatus;
  private volatile long lastPongNanos = System.nanoTime();
  private volatile ScheduledFuture<?> heartbeat;

  IdeDataWebSocketTransport(WebSocketSession session) {
    this.session = Objects.requireNonNull(session, "session");
    this.writer =
        Executors.newSingleThreadExecutor(
            Thread.ofVirtual().name("ide-data-writer-" + session.getId()).factory());
  }

  @Override
  public CompletionStage<Void> write(byte[] frame) {
    Objects.requireNonNull(frame, "frame");
    if (frame.length > MAX_MESSAGE_BYTES) {
      return CompletableFuture.failedFuture(
          new IllegalArgumentException("data frame exceeds " + MAX_MESSAGE_BYTES + " bytes"));
    }
    CompletableFuture<Void> completion = new CompletableFuture<>();
    try {
      writer.execute(
          () -> {
            if (closed.get()) {
              completion.completeExceptionally(new IllegalStateException("data transport closed"));
              return;
            }
            try {
              synchronized (sendLock) {
                if (closed.get()) {
                  throw new IllegalStateException("data transport closed");
                }
                session.sendMessage(new BinaryMessage(frame, true));
              }
              completion.complete(null);
            } catch (Throwable error) {
              closed.set(true);
              completion.completeExceptionally(error);
              closeSession(CloseStatus.SERVER_ERROR.withReason("data write failed"));
            }
          });
    } catch (RejectedExecutionException error) {
      completion.completeExceptionally(error);
    }
    return completion;
  }

  /** 容器线程入队一个完整 binary message；队列满时阻塞形成背压。 */
  boolean offer(byte[] frame) {
    Objects.requireNonNull(frame, "frame");
    if (frame.length > MAX_MESSAGE_BYTES || closed.get()) {
      return false;
    }
    try {
      return inbound.offer(frame, OFFER_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  /** 单线程消费一条入站帧；无帧或连接关闭时返回 null。 */
  byte[] poll(long timeoutMillis) throws InterruptedException {
    if (timeoutMillis <= 0L) {
      return inbound.poll();
    }
    return inbound.poll(timeoutMillis, TimeUnit.MILLISECONDS);
  }

  boolean isClosed() {
    return closed.get();
  }

  CloseStatus remoteCloseStatus() {
    return remoteCloseStatus;
  }

  void onPong() {
    lastPongNanos = System.nanoTime();
  }

  /**
   * 启动原生 Ping/Pong：每 {@code pingInterval} 探测一次，超过 {@code pongTimeout} 没有 Pong 即关闭。
   *
   * <p>正常空闲的 IDE WebSocket 不因没有业务消息被回收；只有底层探测失败才关闭整条数据连接。
   */
  void startHeartbeat(
      ScheduledExecutorService timer,
      long pingIntervalMillis,
      long pongTimeoutMillis,
      Runnable onFailure) {
    Objects.requireNonNull(timer, "timer");
    Objects.requireNonNull(onFailure, "onFailure");
    heartbeat =
        timer.scheduleWithFixedDelay(
            () -> {
              if (closed.get()) {
                ScheduledFuture<?> scheduled = heartbeat;
                if (scheduled != null) {
                  scheduled.cancel(false);
                }
                return;
              }
              if (System.nanoTime() - lastPongNanos
                  >= TimeUnit.MILLISECONDS.toNanos(pongTimeoutMillis)) {
                closeWith(CloseStatus.POLICY_VIOLATION.withReason("data pong timeout"));
                onFailure.run();
                return;
              }
              sendPing(onFailure);
            },
            pingIntervalMillis,
            pingIntervalMillis,
            TimeUnit.MILLISECONDS);
  }

  void stopHeartbeat() {
    ScheduledFuture<?> scheduled = heartbeat;
    if (scheduled != null) {
      scheduled.cancel(false);
      heartbeat = null;
    }
  }

  private void sendPing(Runnable onFailure) {
    try {
      writer.execute(
          () -> {
            if (closed.get()) {
              return;
            }
            try {
              synchronized (sendLock) {
                if (closed.get()) {
                  return;
                }
                session.sendMessage(new PingMessage());
              }
            } catch (Throwable error) {
              closed.set(true);
              onFailure.run();
              closeSession(CloseStatus.SERVER_ERROR.withReason("data ping failed"));
            }
          });
    } catch (RejectedExecutionException error) {
      onFailure.run();
    }
  }

  /** 对端已断开：不再发送关闭帧，只收敛本地队列与发送端。 */
  void onRemoteClosed(CloseStatus status) {
    remoteCloseStatus = status;
    if (closed.compareAndSet(false, true)) {
      writer.shutdownNow();
    }
  }

  /** 本端以指定状态关闭；只发送一次，不做关闭重试。 */
  void closeWith(CloseStatus status) {
    Objects.requireNonNull(status, "status");
    if (closed.compareAndSet(false, true)) {
      writer.shutdownNow();
      closeSession(status);
    }
  }

  @Override
  public void close() {
    closeWith(CLOSED_BY_SERVER);
  }

  private void closeSession(CloseStatus status) {
    synchronized (sendLock) {
      if (!session.isOpen()) {
        return;
      }
      try {
        session.close(status);
      } catch (IOException ignored) {
        // 底层会话已经断开；不重试写状态。
      }
    }
  }
}
