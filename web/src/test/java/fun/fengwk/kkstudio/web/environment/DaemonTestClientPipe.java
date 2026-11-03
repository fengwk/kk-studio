package fun.fengwk.kkstudio.web.environment;

import org.eclipse.jetty.util.thread.Scheduler;
import org.eclipse.jetty.websocket.api.Callback;

import fun.fengwk.kkstudio.platform.daemon.transport.WebSocketEndPoint;
import fun.fengwk.kkstudio.platform.daemon.transport.WebSocketPipe;

import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.function.Consumer;

/** 测试专用的 Daemon 侧 WebSocketPipe 实现。 */
class DaemonTestClientPipe implements WebSocketPipe {

  private final Consumer<ByteBuffer> binarySender;
  private final Runnable demander;
  private final Consumer<String> closer;
  private final SocketAddress localAddress;
  private final SocketAddress remoteAddress;
  private WebSocketEndPoint endPoint;

  DaemonTestClientPipe(
      Consumer<ByteBuffer> binarySender,
      Runnable demander,
      Consumer<String> closer,
      SocketAddress localAddress,
      SocketAddress remoteAddress) {
    this.binarySender = Objects.requireNonNull(binarySender, "binarySender must not be null");
    this.demander = Objects.requireNonNull(demander, "demander must not be null");
    this.closer = Objects.requireNonNull(closer, "closer must not be null");
    this.localAddress = localAddress;
    this.remoteAddress = remoteAddress;
  }

  public WebSocketEndPoint createEndPoint(Scheduler scheduler) {
    this.endPoint = new WebSocketEndPoint(scheduler, this);
    return this.endPoint;
  }

  public void deliverInbound(ByteBuffer payload, Runnable onComplete, Consumer<Throwable> onError) {
    if (endPoint != null) {
      endPoint.onInboundData(payload, Callback.from(onComplete, onError));
    } else if (onComplete != null) {
      onComplete.run();
    }
  }

  public void deliverEof() {
    if (endPoint != null) {
      endPoint.onInboundEof();
    }
  }

  public void deliverError(Throwable cause) {
    if (endPoint != null) {
      endPoint.onInboundError(cause);
    }
  }

  @Override
  public void sendBinary(ByteBuffer payload, Callback callback) {
    try {
      binarySender.accept(payload);
      callback.succeed();
    } catch (Throwable t) {
      callback.fail(t);
    }
  }

  @Override
  public void demand() {
    demander.run();
  }

  @Override
  public void close(int statusCode, String reason) {
    closer.accept(reason);
  }

  @Override
  public SocketAddress getLocalSocketAddress() {
    return localAddress;
  }

  @Override
  public SocketAddress getRemoteSocketAddress() {
    return remoteAddress;
  }
}
