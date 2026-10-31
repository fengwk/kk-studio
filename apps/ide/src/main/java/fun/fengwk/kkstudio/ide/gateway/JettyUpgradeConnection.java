package fun.fengwk.kkstudio.ide.gateway;

import org.eclipse.jetty.io.AbstractConnection;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.util.Callback;

import fun.fengwk.kkstudio.harness.environment.tunnel.TunnelStream;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Jetty 公开连接交接：在浏览器 EndPoint 与 TunnelStream 间双向泵字节。
 *
 * <p>预读字节先转发；每方向至多一个 pending；浏览器 EOF 转为 endOutput，隧道 EOF 转为 shutdownOutput； 两个方向都结束才关闭；任一侧失败即关闭另一侧。
 */
public final class JettyUpgradeConnection extends AbstractConnection
    implements Connection.UpgradeTo {

  private final TunnelStream tunnel;
  private final AtomicBoolean closed = new AtomicBoolean();
  private final AtomicBoolean upstreamDone = new AtomicBoolean();
  private final AtomicBoolean downstreamDone = new AtomicBoolean();
  private final AtomicReference<ByteBuffer> prefetched = new AtomicReference<>();

  public JettyUpgradeConnection(EndPoint endPoint, Executor executor, TunnelStream tunnel) {
    super(endPoint, executor);
    this.tunnel = tunnel;
  }

  @Override
  public void onUpgradeTo(ByteBuffer buffer) {
    if (buffer != null && buffer.hasRemaining()) {
      ByteBuffer copy = ByteBuffer.allocate(buffer.remaining());
      copy.put(buffer);
      copy.flip();
      prefetched.set(copy);
    }
  }

  @Override
  public void onOpen() {
    super.onOpen();
    if (tunnel == null) {
      abort();
      return;
    }
    ByteBuffer pre = prefetched.getAndSet(null);
    if (pre != null && pre.hasRemaining()) {
      tunnel
          .write(pre)
          .whenComplete(
              (v, error) -> {
                if (error != null) {
                  abort();
                } else {
                  fillInterested();
                }
              });
    } else {
      fillInterested();
    }
    pumpDownstream();
  }

  @Override
  public void onFillable() {
    if (closed.get()) {
      return;
    }
    ByteBuffer buffer = ByteBuffer.allocate(16 * 1024);
    int read;
    try {
      read = getEndPoint().fill(buffer);
    } catch (IOException error) {
      upstreamFinished(false);
      return;
    }
    if (read < 0) {
      upstreamFinished(true);
      return;
    }
    if (read == 0) {
      fillInterested();
      return;
    }
    buffer.flip();
    tunnel
        .write(buffer)
        .whenComplete(
            (v, error) -> {
              if (error != null) {
                upstreamFinished(false);
              } else {
                if (!closed.get()) {
                  fillInterested();
                }
              }
            });
  }

  @Override
  public void onFillInterestedFailed(Throwable cause) {
    upstreamFinished(false);
  }

  private void upstreamFinished(boolean graceful) {
    if (upstreamDone.compareAndSet(false, true)) {
      if (tunnel != null) {
        if (graceful) {
          tunnel
              .endOutput()
              .whenComplete(
                  (v, error) -> {
                    if (error != null) {
                      abort();
                    } else {
                      closeWhenBothDone();
                    }
                  });
          return;
        } else {
          tunnel.close();
        }
      }
      closeWhenBothDone();
    }
  }

  private void pumpDownstream() {
    if (closed.get()) {
      return;
    }
    tunnel
        .read()
        .whenComplete(
            (chunk, error) -> {
              if (error != null) {
                downstreamFinished(false);
                return;
              }
              if (chunk == null) {
                downstreamFinished(true);
                return;
              }
              getEndPoint()
                  .write(
                      Callback.from(this::pumpDownstream, err -> downstreamFinished(false)), chunk);
            });
  }

  private void downstreamFinished(boolean graceful) {
    if (downstreamDone.compareAndSet(false, true)) {
      if (graceful) {
        try {
          getEndPoint().shutdownOutput();
        } catch (Exception ignored) {
        }
      } else {
        abort();
        return;
      }
      closeWhenBothDone();
    }
  }

  private void closeWhenBothDone() {
    if (upstreamDone.get() && downstreamDone.get()) {
      closeSocket();
    }
  }

  private void closeSocket() {
    if (closed.compareAndSet(false, true)) {
      if (tunnel != null) {
        tunnel.close();
      }
      try {
        getEndPoint().close();
      } catch (Exception ignored) {
      }
    }
  }

  private void abort() {
    if (closed.compareAndSet(false, true)) {
      if (tunnel != null) {
        tunnel.close();
      }
      try {
        getEndPoint().close();
      } catch (Exception ignored) {
      }
    }
  }

  @Override
  public void close() {
    abort();
  }
}
