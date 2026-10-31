package fun.fengwk.kkstudio.web.environment;

import org.eclipse.jetty.util.thread.Scheduler;
import org.eclipse.jetty.websocket.api.Callback;
import org.eclipse.jetty.websocket.api.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import fun.fengwk.kkstudio.harness.environment.server.DaemonAdmission;
import fun.fengwk.kkstudio.harness.environment.server.DaemonSessions;
import fun.fengwk.kkstudio.harness.environment.transport.DaemonHttp2ClientSession;
import fun.fengwk.kkstudio.harness.environment.transport.WebSocketEndPoint;

import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Daemon 物理 WebSocket 会话监听器。
 *
 * <p>在 WebSocket 升级成功后启动，适配为底层 Pipe 并挂载 {@link WebSocketEndPoint} 与
 * {@link DaemonHttp2ClientSession}。使用显式 demand 维持背压。
 */
public final class EnvironmentDaemonWebSocketListener implements Session.Listener {

  private static final Logger log =
      LoggerFactory.getLogger(EnvironmentDaemonWebSocketListener.class);
  private static final int MAX_BINARY_MESSAGE_SIZE = 1024 * 1024;

  private final DaemonAdmission.Admitted admitted;
  private final DaemonSessions sessions;
  private final Scheduler scheduler;
  private final AtomicBoolean closed = new AtomicBoolean(false);

  private volatile WebSocketEndPoint endPoint;
  private volatile DaemonHttp2ClientSession clientSession;

  public EnvironmentDaemonWebSocketListener(
      DaemonAdmission.Admitted admitted, DaemonSessions sessions, Scheduler scheduler) {
    this.admitted = Objects.requireNonNull(admitted, "admitted");
    this.sessions = Objects.requireNonNull(sessions, "sessions");
    this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
  }

  @Override
  public void onWebSocketOpen(Session session) {
    session.setMaxBinaryMessageSize(MAX_BINARY_MESSAGE_SIZE);
    EnvironmentDaemonWebSocketPipe pipe =
        new EnvironmentDaemonWebSocketPipe(session, closed);

    WebSocketEndPoint ep = new WebSocketEndPoint(scheduler, pipe);
    DaemonHttp2ClientSession cs =
        new DaemonHttp2ClientSession(ep, scheduler, admitted, sessions);
    this.endPoint = ep;
    this.clientSession = cs;
    try {
      cs.start();
    } catch (Exception e) {
      log.warn("failed to start daemon http2 client session for {}", admitted.environmentId(), e);
      pipe.close(1011, "failed to start session");
    }
  }

  @Override
  public void onWebSocketBinary(ByteBuffer payload, Callback callback) {
    WebSocketEndPoint ep = this.endPoint;
    if (ep != null) {
      ep.onInboundMessage(
          payload, UtilCallbackBridge.from(callback::succeed, callback::fail));
    } else {
      callback.fail(new IllegalStateException("endpoint not initialized"));
    }
  }

  @Override
  public void onWebSocketError(Throwable cause) {
    WebSocketEndPoint ep = this.endPoint;
    if (ep != null) {
      ep.onInboundError(cause);
    }
  }

  @Override
  public void onWebSocketClose(int statusCode, String reason, Callback callback) {
    closed.set(true);
    WebSocketEndPoint ep = this.endPoint;
    if (ep != null) {
      ep.onInboundClose(statusCode, reason);
    }
    callback.succeed();
  }
}
