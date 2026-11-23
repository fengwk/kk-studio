package fun.fengwk.kkstudio.ide.gateway;

import lombok.extern.slf4j.Slf4j;

import fun.fengwk.kkstudio.platform.daemon.tunnel.TunnelStream;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 将已建立的 TCP/SSL 连接适配为双向异步 {@link TunnelStream}，供集群节点间透传 WebSocket 连接。
 *
 * <p>传输统一为 {@link Socket}：TLS 连接传入分层的 SSL socket 及其底层 TCP 连接。阻塞读写运行在虚拟线程上，每次读取最多返回 16
 * KiB，只在调用方请求读取时才发起读取；每个方向至多一个未完成操作。
 *
 * <p>写方向按已有写入的完成顺序半关闭，重复调用返回同一次半关闭结果。关闭时先取消未完成操作，再关闭底层 TCP 连接以立即中断 JSSE 阻塞 I/O，最后关闭分层 SSL socket。
 */
@Slf4j
public final class TcpTunnelStream implements TunnelStream {

  private static final int BUFFER_SIZE = 16 * 1024;

  private final Socket socket;
  private final Socket transport;
  private final InputStream input;
  private final OutputStream output;
  private final AtomicBoolean closed = new AtomicBoolean();

  private final Object lock = new Object();
  private CompletableFuture<ByteBuffer> pendingRead;
  private CompletableFuture<Void> pendingWrite;
  private CompletableFuture<Void> endOutputFuture;
  private Throwable lastWriteError;
  private boolean inputEnded;
  private byte[] initialBytes;

  /**
   * 用一条普通 TCP 连接构造，连接自身即底层传输。
   *
   * @param socket 已建立的连接
   * @param initialBytes 读取响应头时溢出的预读字节，可空
   */
  public TcpTunnelStream(Socket socket, byte[] initialBytes) throws IOException {
    this(socket, socket, initialBytes);
  }

  /**
   * @param socket 分层 socket，TLS 连接时为 SSL socket
   * @param transport 底层 TCP 连接；普通连接时与 {@code socket} 相同。关闭时先关它，以便立即中断分层的阻塞 I/O
   * @param initialBytes 读取响应头时溢出的预读字节，可空
   */
  public TcpTunnelStream(Socket socket, Socket transport, byte[] initialBytes) throws IOException {
    this.socket = Objects.requireNonNull(socket, "socket");
    this.transport = Objects.requireNonNull(transport, "transport");
    this.input = socket.getInputStream();
    this.output = socket.getOutputStream();
    this.initialBytes =
        initialBytes != null && initialBytes.length > 0 ? initialBytes.clone() : null;
  }

  @Override
  public CompletionStage<ByteBuffer> read() {
    CompletableFuture<ByteBuffer> future = new CompletableFuture<>();
    synchronized (lock) {
      if (closed.get()) {
        return CompletableFuture.failedFuture(closedException());
      }
      if (pendingRead != null) {
        throw new IllegalStateException("concurrent read not permitted");
      }
      if (initialBytes != null) {
        byte[] bytes = initialBytes;
        initialBytes = null;
        return CompletableFuture.completedFuture(ByteBuffer.wrap(bytes));
      }
      if (inputEnded) {
        return CompletableFuture.completedFuture(null);
      }
      pendingRead = future;
    }
    Thread.ofVirtual().name("tcp-tunnel-read").start(() -> readBlocking(future));
    return future;
  }

  private void readBlocking(CompletableFuture<ByteBuffer> future) {
    ByteBuffer chunk = null;
    Throwable error = null;
    try {
      byte[] buffer = new byte[BUFFER_SIZE];
      int read = input.read(buffer, 0, buffer.length);
      if (read < 0) {
        synchronized (lock) {
          inputEnded = true;
        }
      } else {
        chunk = ByteBuffer.wrap(buffer, 0, read);
      }
    } catch (Throwable t) {
      error = t;
    }
    synchronized (lock) {
      if (pendingRead == future) {
        pendingRead = null;
      }
    }
    if (error != null) {
      future.completeExceptionally(error);
    } else {
      future.complete(chunk);
    }
  }

  @Override
  public CompletionStage<Void> write(ByteBuffer source) {
    if (source == null) {
      throw new IllegalArgumentException("source must not be null");
    }
    if (!source.hasRemaining()) {
      throw new IllegalArgumentException("source must have remaining bytes");
    }
    CompletableFuture<Void> future = new CompletableFuture<>();
    synchronized (lock) {
      if (closed.get()) {
        return CompletableFuture.failedFuture(closedException());
      }
      if (endOutputFuture != null) {
        throw new IllegalStateException("output has already ended");
      }
      if (pendingWrite != null) {
        throw new IllegalStateException("concurrent write not permitted");
      }
      pendingWrite = future;
    }
    Thread.ofVirtual().name("tcp-tunnel-write").start(() -> writeBlocking(source, future));
    return future;
  }

  private void writeBlocking(ByteBuffer source, CompletableFuture<Void> future) {
    Throwable error = null;
    try {
      byte[] buffer = new byte[BUFFER_SIZE];
      while (true) {
        int length;
        synchronized (lock) {
          // Once close cancels the future the caller may reuse its buffer.
          if (closed.get()) {
            return;
          }
          if (!source.hasRemaining()) {
            break;
          }
          length = Math.min(source.remaining(), buffer.length);
          source.get(buffer, 0, length);
        }
        output.write(buffer, 0, length);
      }
    } catch (Throwable t) {
      error = t;
    }
    synchronized (lock) {
      if (pendingWrite == future) {
        pendingWrite = null;
      }
      if (error != null) {
        lastWriteError = error;
      }
    }
    if (error != null) {
      future.completeExceptionally(error);
    } else {
      future.complete(null);
    }
  }

  @Override
  public CompletionStage<Void> endOutput() {
    CompletableFuture<Void> endFuture = new CompletableFuture<>();
    CompletableFuture<Void> currentWrite;
    synchronized (lock) {
      if (endOutputFuture != null) {
        return endOutputFuture;
      }
      if (closed.get()) {
        return CompletableFuture.completedFuture(null);
      }
      endOutputFuture = endFuture;
      if (lastWriteError != null) {
        endFuture.completeExceptionally(lastWriteError);
        return endFuture;
      }
      currentWrite = pendingWrite;
      if (currentWrite == null) {
        shutdownOutputAsync(endFuture);
        return endFuture;
      }
    }
    currentWrite.whenComplete(
        (ignored, error) -> {
          if (error != null) {
            endFuture.completeExceptionally(error);
          } else {
            shutdownOutputAsync(endFuture);
          }
        });
    return endFuture;
  }

  private void shutdownOutputAsync(CompletableFuture<Void> endFuture) {
    Thread.ofVirtual()
        .name("tcp-tunnel-half-close")
        .start(
            () -> {
              Throwable error = null;
              try {
                socket.shutdownOutput();
              } catch (Throwable t) {
                error = t;
              }
              if (error != null) {
                endFuture.completeExceptionally(error);
              } else {
                endFuture.complete(null);
              }
            });
  }

  @Override
  public void close() {
    if (!closed.compareAndSet(false, true)) {
      return;
    }
    CompletableFuture<ByteBuffer> readToCancel;
    CompletableFuture<Void> writeToCancel;
    CompletableFuture<Void> endOutputToCancel;
    synchronized (lock) {
      readToCancel = pendingRead;
      pendingRead = null;
      writeToCancel = pendingWrite;
      pendingWrite = null;
      endOutputToCancel = endOutputFuture;
      initialBytes = null;
    }
    CancellationException cancel = closedException();
    if (readToCancel != null) {
      readToCancel.completeExceptionally(cancel);
    }
    if (writeToCancel != null) {
      writeToCancel.completeExceptionally(cancel);
    }
    if (endOutputToCancel != null) {
      endOutputToCancel.completeExceptionally(cancel);
    }
    closeQuietly(transport);
    if (transport != socket) {
      closeQuietly(socket);
    }
  }

  private static CancellationException closedException() {
    return new CancellationException("tunnel stream closed");
  }

  private static void closeQuietly(Socket socket) {
    try {
      socket.close();
    } catch (IOException error) {
      log.debug("failed to close tcp tunnel socket", error);
    }
  }
}
