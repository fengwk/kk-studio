package fun.fengwk.kkstudio.ide.gateway;

import fun.fengwk.kkstudio.harness.environment.tunnel.TunnelStream;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 将 TCP Socket 适配为双向异步 {@link TunnelStream}，供集群节点间透传 WebSocket 连接。
 *
 * <p>读取初始响应头时溢出的预读字节优先消费；每个方向至多一个 pending 操作；写方向支持正常半关闭。
 */
public final class TcpTunnelStream implements TunnelStream {

  private static final int BUFFER_SIZE = 16 * 1024;

  private final Socket socket;
  private final InputStream input;
  private final OutputStream output;
  private final Executor executor;

  private final AtomicBoolean closed = new AtomicBoolean();
  private final AtomicBoolean outputEnded = new AtomicBoolean();
  private final AtomicBoolean readPending = new AtomicBoolean();
  private final AtomicBoolean writePending = new AtomicBoolean();

  private byte[] initialBytes;
  private CompletableFuture<Void> endOutputFuture;

  public TcpTunnelStream(Socket socket, Executor executor, byte[] initialBytes) throws IOException {
    this.socket = Objects.requireNonNull(socket, "socket");
    this.executor = Objects.requireNonNull(executor, "executor");
    this.input = socket.getInputStream();
    this.output = socket.getOutputStream();
    this.initialBytes =
        initialBytes != null && initialBytes.length > 0 ? initialBytes.clone() : null;
  }

  @Override
  public CompletionStage<ByteBuffer> read() {
    if (closed.get()) {
      return CompletableFuture.failedFuture(new CancellationException("stream closed"));
    }
    if (!readPending.compareAndSet(false, true)) {
      throw new IllegalStateException("concurrent read not permitted");
    }
    if (initialBytes != null) {
      byte[] bytes = initialBytes;
      initialBytes = null;
      readPending.set(false);
      return CompletableFuture.completedFuture(ByteBuffer.wrap(bytes));
    }
    return CompletableFuture.supplyAsync(
            () -> {
              try {
                byte[] buffer = new byte[BUFFER_SIZE];
                int n = input.read(buffer);
                if (n < 0) {
                  return null;
                }
                return ByteBuffer.wrap(buffer, 0, n);
              } catch (IOException error) {
                if (closed.get()) {
                  return null;
                }
                throw new IllegalStateException("socket read failed", error);
              }
            },
            executor)
        .whenComplete((res, err) -> readPending.set(false));
  }

  @Override
  public CompletionStage<Void> write(ByteBuffer source) {
    Objects.requireNonNull(source, "source");
    if (!source.hasRemaining()) {
      throw new IllegalArgumentException("source must have remaining bytes");
    }
    if (closed.get()) {
      return CompletableFuture.failedFuture(new CancellationException("stream closed"));
    }
    if (outputEnded.get()) {
      throw new IllegalStateException("output has already ended");
    }
    if (!writePending.compareAndSet(false, true)) {
      throw new IllegalStateException("concurrent write not permitted");
    }
    byte[] bytes = new byte[source.remaining()];
    source.get(bytes);
    return CompletableFuture.runAsync(
            () -> {
              try {
                output.write(bytes);
                output.flush();
              } catch (IOException error) {
                if (!closed.get()) {
                  throw new IllegalStateException("socket write failed", error);
                }
              }
            },
            executor)
        .whenComplete((res, err) -> writePending.set(false));
  }

  @Override
  public CompletionStage<Void> endOutput() {
    if (closed.get()) {
      return CompletableFuture.completedFuture(null);
    }
    synchronized (this) {
      if (endOutputFuture != null) {
        return endOutputFuture;
      }
      outputEnded.set(true);
      endOutputFuture =
          CompletableFuture.runAsync(
              () -> {
                try {
                  if (!socket.isOutputShutdown() && !socket.isClosed()) {
                    socket.shutdownOutput();
                  }
                } catch (IOException ignored) {
                }
              },
              executor);
      return endOutputFuture;
    }
  }

  @Override
  public void close() {
    if (closed.compareAndSet(false, true)) {
      try {
        socket.close();
      } catch (IOException ignored) {
      }
    }
  }
}
