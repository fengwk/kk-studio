package fun.fengwk.kkstudio.web.environment;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.util.thread.Scheduler;
import org.eclipse.jetty.websocket.api.Callback;
import org.eclipse.jetty.websocket.api.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import fun.fengwk.kkstudio.harness.environment.server.DaemonAdmission;
import fun.fengwk.kkstudio.harness.environment.server.DaemonSessions;
import fun.fengwk.kkstudio.harness.environment.transport.DaemonHttp2ClientSession;
import fun.fengwk.kkstudio.harness.environment.transport.WebSocketPipe;

import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * Daemon 物理 WebSocket 会话监听器与通道实现。
 *
 * <p>在 WebSocket 升级成功后启动，实现 {@link WebSocketPipe} 挂载 {@link DaemonHttp2ClientSession}。 使用显式 demand
 * 维持背压。收到文本帧按规范以 1003 状态码关闭。
 */
public final class EnvironmentDaemonWebSocketListener implements Session.Listener, WebSocketPipe {

  private static final Logger log =
      LoggerFactory.getLogger(EnvironmentDaemonWebSocketListener.class);
  private static final int MAX_BINARY_MESSAGE_SIZE = 1024 * 1024;

  private final DaemonAdmission.Admitted admitted;
  private final DaemonSessions sessions;
  private final Scheduler scheduler;
  private final Executor executor;
  private final ByteBufferPool bufferPool;

  private Session session;
  private DaemonHttp2ClientSession clientSession;
  private volatile boolean closed;

  public EnvironmentDaemonWebSocketListener(
      DaemonAdmission.Admitted admitted,
      DaemonSessions sessions,
      Scheduler scheduler,
      Executor executor,
      ByteBufferPool bufferPool) {
    this.admitted = Objects.requireNonNull(admitted, "admitted");
    this.sessions = Objects.requireNonNull(sessions, "sessions");
    this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    this.executor = Objects.requireNonNull(executor, "executor");
    this.bufferPool = Objects.requireNonNull(bufferPool, "bufferPool");
  }

  @Override
  public void onWebSocketOpen(Session session) {
    this.session = session;
    session.setMaxBinaryMessageSize(MAX_BINARY_MESSAGE_SIZE);

    DaemonHttp2ClientSession cs =
        new DaemonHttp2ClientSession(admitted, this, scheduler, executor, bufferPool, sessions);
    this.clientSession = cs;
    try {
      cs.start();
    } catch (Exception e) {
      log.warn("failed to start daemon http2 client session for {}", admitted.environmentId(), e);
      close(1011, "failed to start session");
    }
  }

  @Override
  public void onWebSocketText(String message) {
    log.warn("received unsupported text frame from daemon {}, closing", admitted.environmentId());
    close(1003, "Text frames not supported");
  }

  @Override
  public void onWebSocketBinary(ByteBuffer payload, Callback callback) {
    clientSession.endPoint().onInboundData(payload, callback);
  }

  @Override
  public void onWebSocketError(Throwable cause) {
    clientSession.endPoint().onInboundError(cause);
  }

  @Override
  public void onWebSocketClose(int statusCode, String reason, Callback callback) {
    closed = true;
    clientSession.endPoint().onInboundEof();
    callback.succeed();
  }

  @Override
  public void sendBinary(ByteBuffer payload, Callback callback) {
    session.sendBinary(payload, callback);
  }

  @Override
  public void demand() {
    session.demand();
  }

  @Override
  public void close(int statusCode, String reason) {
    if (!closed) {
      closed = true;
      try {
        session.close(statusCode, reason, Callback.NOOP);
      } catch (Exception e) {
        log.debug("error closing websocket session: {}", e.getMessage());
      }
    }
  }

  @Override
  public SocketAddress getLocalSocketAddress() {
    return session.getLocalSocketAddress();
  }

  @Override
  public SocketAddress getRemoteSocketAddress() {
    return session.getRemoteSocketAddress();
  }
}
