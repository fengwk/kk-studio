package fun.fengwk.kkstudio.web.environment.ide;

import org.springframework.web.socket.CloseStatus;

import fun.fengwk.kkstudio.ide.data.IdeDataConnection;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 已接受数据连接的应用侧驱动：把入站 binary message 交给唯一 mux 接收线程，并绑定原生 Ping/Pong 心跳。
 *
 * <p>WebSocket 容器线程只做有界入队；帧解析、credit 与回调都在单线程内按 wire 顺序推进。任何协议错误、物理断开或心跳超时都 fail-stop 整条连接，不回放逻辑请求。
 */
final class IdeDataPhysicalSession implements AutoCloseable {

  private static final long POLL_TICK_MILLIS = 100L;

  private final IdeDataWebSocketTransport transport;
  private final IdeDataConnection connection;
  private final Runnable onClosed;
  private final AtomicBoolean stopped = new AtomicBoolean();
  private final Thread worker;

  IdeDataPhysicalSession(
      IdeDataWebSocketTransport transport, IdeDataConnection connection, Runnable onClosed) {
    this.transport = Objects.requireNonNull(transport, "transport");
    this.connection = Objects.requireNonNull(connection, "connection");
    this.onClosed = Objects.requireNonNull(onClosed, "onClosed");
    this.worker =
        Thread.ofVirtual().name("ide-data-connection-" + transport.hashCode()).unstarted(this::run);
  }

  void start(ScheduledExecutorService timer, long pingIntervalMillis, long pongTimeoutMillis) {
    transport.startHeartbeat(
        timer, pingIntervalMillis, pongTimeoutMillis, this::onHeartbeatFailure);
    worker.start();
  }

  /** 容器线程入队；队列满或连接已关闭时返回 false。 */
  boolean offer(byte[] frame) {
    if (stopped.get()) {
      return false;
    }
    return transport.offer(frame);
  }

  void onPong() {
    transport.onPong();
  }

  /** 对端关闭：停止驱动并让 mux fail-stop；不发送新的关闭帧。 */
  void onTransportClosed(CloseStatus status) {
    transport.onRemoteClosed(status);
    stop(new IOException("IDE data websocket closed"));
  }

  void onTransportError(Throwable error) {
    stop(error);
  }

  private void onHeartbeatFailure() {
    stop(new IOException("IDE data websocket heartbeat failed"));
  }

  private void run() {
    try {
      while (!stopped.get()) {
        byte[] frame = transport.poll(POLL_TICK_MILLIS);
        if (frame != null) {
          connection.receive(frame);
          continue;
        }
        if (transport.isClosed()) {
          break;
        }
      }
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
    } catch (RuntimeException error) {
      transport.closeWith(
          CloseStatus.PROTOCOL_ERROR.withReason("invalid IDE data frame or downstream failure"));
    } finally {
      stop(new IOException("IDE data receive loop ended"));
    }
  }

  private void stop(Throwable cause) {
    if (!stopped.compareAndSet(false, true)) {
      return;
    }
    transport.stopHeartbeat();
    worker.interrupt();
    connection.fail(cause);
    onClosed.run();
  }

  @Override
  public void close() {
    stopped.set(true);
    transport.stopHeartbeat();
    worker.interrupt();
    transport.closeWith(CloseStatus.GOING_AWAY.withReason("IDE data connection replaced"));
    connection.fail(new IOException("IDE data connection replaced"));
    onClosed.run();
  }
}
