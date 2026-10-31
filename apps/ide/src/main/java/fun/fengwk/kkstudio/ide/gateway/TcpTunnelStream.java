package fun.fengwk.kkstudio.ide.gateway;

import lombok.extern.slf4j.Slf4j;

import fun.fengwk.kkstudio.harness.environment.tunnel.TunnelStream;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousSocketChannel;
import java.nio.channels.CompletionHandler;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 将 {@link AsynchronousSocketChannel} 适配为双向异步 {@link TunnelStream}，供集群节点间透传 WebSocket 连接。
 *
 * <p>读取初始响应头时溢出的预读字节优先消费；每个方向至多一个 pending 操作；写方向支持正常半关闭；使用 NIO CompletionHandler，不占用阻塞工作线程。
 */
@Slf4j
public final class TcpTunnelStream implements TunnelStream {

  private static final int BUFFER_SIZE = 16 * 1024;

  private final AsynchronousSocketChannel channel;
  private final AtomicBoolean closed = new AtomicBoolean();
  private final AtomicBoolean outputEnded = new AtomicBoolean();

  private final Object lock = new Object();
  private CompletableFuture<ByteBuffer> pendingRead;
  private CompletableFuture<Void> pendingWrite;
  private CompletableFuture<Void> pendingEndOutput;
  private Throwable lastWriteError;
  private byte[] initialBytes;

  public TcpTunnelStream(AsynchronousSocketChannel channel, byte[] initialBytes) {
    this.channel = Objects.requireNonNull(channel, "channel");
    this.initialBytes =
        initialBytes != null && initialBytes.length > 0 ? initialBytes.clone() : null;
  }

  @Override
  public CompletionStage<ByteBuffer> read() {
    synchronized (lock) {
      if (closed.get()) {
        return CompletableFuture.failedFuture(new CancellationException("tunnel stream closed"));
      }
      if (pendingRead != null) {
        throw new IllegalStateException("concurrent read not permitted");
      }
      if (initialBytes != null) {
        byte[] bytes = initialBytes;
        initialBytes = null;
        return CompletableFuture.completedFuture(ByteBuffer.wrap(bytes));
      }

      ByteBuffer buffer = ByteBuffer.allocate(BUFFER_SIZE);
      CompletableFuture<ByteBuffer> future = new CompletableFuture<>();
      pendingRead = future;

      try {
        channel.read(
            buffer,
            null,
            new CompletionHandler<Integer, Void>() {
              @Override
              public void completed(Integer result, Void attachment) {
                synchronized (lock) {
                  pendingRead = null;
                }
                if (result == null || result < 0) {
                  future.complete(null);
                } else {
                  buffer.flip();
                  future.complete(buffer);
                }
              }

              @Override
              public void failed(Throwable exc, Void attachment) {
                synchronized (lock) {
                  pendingRead = null;
                }
                future.completeExceptionally(exc);
              }
            });
      } catch (Throwable t) {
        pendingRead = null;
        future.completeExceptionally(t);
      }
      return future;
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
    synchronized (lock) {
      if (closed.get()) {
        return CompletableFuture.failedFuture(new CancellationException("tunnel stream closed"));
      }
      if (outputEnded.get()) {
        throw new IllegalStateException("output has already ended");
      }
      if (pendingWrite != null) {
        throw new IllegalStateException("concurrent write not permitted");
      }

      CompletableFuture<Void> future = new CompletableFuture<>();
      pendingWrite = future;
      writeFully(source, future);
      return future;
    }
  }

  private void writeFully(ByteBuffer source, CompletableFuture<Void> future) {
    try {
      channel.write(
          source,
          null,
          new CompletionHandler<Integer, Void>() {
            @Override
            public void completed(Integer result, Void attachment) {
              if (source.hasRemaining()) {
                writeFully(source, future);
              } else {
                synchronized (lock) {
                  pendingWrite = null;
                }
                future.complete(null);
              }
            }

            @Override
            public void failed(Throwable exc, Void attachment) {
              synchronized (lock) {
                lastWriteError = exc;
                pendingWrite = null;
              }
              future.completeExceptionally(exc);
            }
          });
    } catch (Throwable t) {
      synchronized (lock) {
        lastWriteError = t;
        pendingWrite = null;
      }
      future.completeExceptionally(t);
    }
  }

  @Override
  public CompletionStage<Void> endOutput() {
    synchronized (lock) {
      if (closed.get()) {
        return CompletableFuture.completedFuture(null);
      }
      if (outputEnded.compareAndSet(false, true)) {
        CompletableFuture<Void> endFuture = new CompletableFuture<>();
        pendingEndOutput = endFuture;
        if (lastWriteError != null) {
          endFuture.completeExceptionally(lastWriteError);
          return endFuture;
        }
        CompletableFuture<Void> currentWrite = pendingWrite;
        if (currentWrite != null) {
          currentWrite.whenComplete(
              (v, err) -> {
                if (err != null) {
                  endFuture.completeExceptionally(err);
                } else {
                  performShutdownOutput(endFuture);
                }
              });
        } else {
          performShutdownOutput(endFuture);
        }
        return endFuture;
      }
      return pendingEndOutput != null ? pendingEndOutput : CompletableFuture.completedFuture(null);
    }
  }

  private void performShutdownOutput(CompletableFuture<Void> endFuture) {
    try {
      channel.shutdownOutput();
      endFuture.complete(null);
    } catch (Throwable t) {
      endFuture.completeExceptionally(t);
    }
  }

  @Override
  public void close() {
    if (closed.compareAndSet(false, true)) {
      CompletableFuture<ByteBuffer> r;
      CompletableFuture<Void> w;
      CompletableFuture<Void> e;
      synchronized (lock) {
        r = pendingRead;
        pendingRead = null;
        w = pendingWrite;
        pendingWrite = null;
        e = pendingEndOutput;
        pendingEndOutput = null;
      }
      CancellationException cancel = new CancellationException("tunnel stream closed");
      if (r != null) {
        r.completeExceptionally(cancel);
      }
      if (w != null) {
        w.completeExceptionally(cancel);
      }
      if (e != null) {
        e.completeExceptionally(cancel);
      }
      try {
        channel.close();
      } catch (IOException error) {
        log.debug("failed to close tcp channel", error);
      }
    }
  }
}
