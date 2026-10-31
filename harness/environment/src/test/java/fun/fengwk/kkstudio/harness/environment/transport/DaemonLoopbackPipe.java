package fun.fengwk.kkstudio.harness.environment.transport;

import org.eclipse.jetty.websocket.api.Callback;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.util.concurrent.Executor;

final class DaemonLoopbackPipe implements WebSocketPipe {

  private final Executor executor;
  private WebSocketEndPoint targetEndPoint;
  private WebSocketEndPoint selfEndPoint;
  private boolean closed;

  DaemonLoopbackPipe(Executor executor) {
    this.executor = executor;
  }

  void link(WebSocketEndPoint target, WebSocketEndPoint self) {
    this.targetEndPoint = target;
    this.selfEndPoint = self;
  }

  void attachEndPoint(WebSocketEndPoint self) {
    this.selfEndPoint = self;
  }

  @Override
  public void sendBinary(ByteBuffer payload, Callback callback) {
    if (targetEndPoint != null && !closed) {
      byte[] copy = new byte[payload.remaining()];
      payload.get(copy);
      executor.execute(
          () ->
              targetEndPoint.onInboundData(
                  ByteBuffer.wrap(copy), Callback.from(callback::succeed, callback::fail)));
    } else {
      callback.succeed();
    }
  }

  @Override
  public void demand() {}

  @Override
  public void close(int statusCode, String reason) {
    closed = true;
    if (targetEndPoint != null) {
      executor.execute(() -> targetEndPoint.onInboundEof());
    }
  }

  void fail(Throwable error) {
    closed = true;
    if (targetEndPoint != null) {
      executor.execute(() -> targetEndPoint.onInboundError(error));
    }
  }

  @Override
  public SocketAddress getLocalSocketAddress() {
    return new InetSocketAddress("127.0.0.1", 10001);
  }

  @Override
  public SocketAddress getRemoteSocketAddress() {
    return new InetSocketAddress("127.0.0.1", 10002);
  }
}
