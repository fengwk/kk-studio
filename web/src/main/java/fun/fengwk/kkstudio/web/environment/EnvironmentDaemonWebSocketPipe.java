package fun.fengwk.kkstudio.web.environment;

import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.websocket.api.Session;

import fun.fengwk.kkstudio.harness.environment.transport.WebSocketPipe;

import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

final class EnvironmentDaemonWebSocketPipe implements WebSocketPipe {

  private final Session session;
  private final AtomicBoolean closed;

  EnvironmentDaemonWebSocketPipe(Session session, AtomicBoolean closed) {
    this.session = Objects.requireNonNull(session, "session");
    this.closed = Objects.requireNonNull(closed, "closed");
  }

  @Override
  public void sendBinary(ByteBuffer payload, Callback callback) {
    session.sendBinary(
        payload,
        WebSocketCallbackBridge.adapt(
            callback != null ? callback::succeeded : null,
            callback != null ? callback::failed : null));
  }

  @Override
  public void demand() {
    session.demand();
  }

  @Override
  public void close(int statusCode, String reason) {
    if (closed.compareAndSet(false, true)) {
      session.close(statusCode, reason, WebSocketCallbackBridge.noop());
    }
  }

  @Override
  public SocketAddress getRemoteAddress() {
    return session.getRemoteSocketAddress();
  }

  @Override
  public SocketAddress getLocalAddress() {
    return session.getLocalSocketAddress();
  }
}
