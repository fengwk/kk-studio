package fun.fengwk.kkstudio.harness.environment.tunnel.testing;

import fun.fengwk.kkstudio.harness.environment.tunnel.TunnelStream;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * 零缓冲的进程内双向字节流。
 *
 * <p>每个方向同时最多一个未完成读取和一个未完成写入。没有读取时写入保持未完成，不预读、不排队；每次读取最多复制 {@link
 * #MAX_SEGMENT_BYTES}。全部剩余字节被读取后写入才完成。测试对端是 {@link Peer}，不使用网络或协议 credit。
 */
public final class FakeTunnelStream implements TunnelStream {

  public static final int MAX_SEGMENT_BYTES = 16 * 1024;

  private final Object lock = new Object();
  private final Direction outbound = new Direction();
  private final Direction inbound = new Direction();
  private Throwable terminalFailure;

  FakeTunnelStream() {}

  /** 与调用方读写方向相反的显式测试对端。 */
  public Peer peer() {
    return new Peer();
  }

  @Override
  public CompletionStage<ByteBuffer> read() {
    return inbound.read(lock);
  }

  @Override
  public CompletionStage<Void> write(ByteBuffer source) {
    return outbound.write(lock, source);
  }

  @Override
  public CompletionStage<Void> endOutput() {
    return outbound.end(lock);
  }

  @Override
  public void close() {
    terminate(new CancellationException("tunnel stream closed"));
  }

  /** 会话断开或响应提交后的流失败；已完成操作保持原结果。 */
  public void fail(IOException failure) {
    if (failure == null) {
      throw new IllegalArgumentException("failure must not be null");
    }
    terminate(failure);
  }

  private void terminate(Throwable failure) {
    List<CompletableFuture<?>> pending = new ArrayList<>();
    synchronized (lock) {
      if (terminalFailure != null) {
        return;
      }
      terminalFailure = failure;
      outbound.takePending(pending);
      inbound.takePending(pending);
    }
    for (CompletableFuture<?> future : pending) {
      future.completeExceptionally(failure);
    }
  }

  /** 测试中模拟 Daemon 或浏览器对端的反向端口。 */
  public final class Peer {

    private Peer() {}

    /** 读取调用方已经写入、尚未被本对端消费的下一段。 */
    public CompletionStage<ByteBuffer> read() {
      return outbound.read(lock);
    }

    /** 向调用方的读端写入；没有挂起读取时保持未完成。 */
    public CompletionStage<Void> write(ByteBuffer source) {
      return inbound.write(lock, source);
    }

    /** 半关闭调用方看到的读方向。 */
    public CompletionStage<Void> endOutput() {
      return inbound.end(lock);
    }
  }

  private final class Direction {

    private CompletableFuture<ByteBuffer> pendingRead;
    private ByteBuffer source;
    private CompletableFuture<Void> pendingWrite;
    private CompletableFuture<Void> ending;
    private boolean ended;

    private CompletionStage<ByteBuffer> read(Object monitor) {
      ByteBuffer segment = null;
      CompletableFuture<ByteBuffer> readResult = null;
      CompletableFuture<Void> finishedWrite = null;
      CompletableFuture<Void> finishedEnd = null;
      synchronized (monitor) {
        if (terminalFailure != null) {
          return CompletableFuture.failedFuture(terminalFailure);
        }
        if (pendingRead != null) {
          throw new IllegalStateException("a read is already pending in this direction");
        }
        if (source != null) {
          segment = takeSegment();
          if (!source.hasRemaining()) {
            finishedWrite = pendingWrite;
            source = null;
            pendingWrite = null;
          }
          if (finishedWrite != null && ending != null) {
            ended = true;
            finishedEnd = ending;
          }
        } else if (ended) {
          readResult = CompletableFuture.completedFuture(null);
        } else {
          readResult = new CompletableFuture<>();
          pendingRead = readResult;
        }
      }
      if (segment != null) {
        readResult = CompletableFuture.completedFuture(segment);
      }
      if (finishedWrite != null) {
        finishedWrite.complete(null);
      }
      if (finishedEnd != null) {
        finishedEnd.complete(null);
      }
      return readResult;
    }

    private CompletionStage<Void> write(Object monitor, ByteBuffer input) {
      if (input == null || !input.hasRemaining()) {
        throw new IllegalArgumentException("source must contain at least one byte");
      }
      CompletableFuture<ByteBuffer> waitingRead = null;
      ByteBuffer segment = null;
      CompletableFuture<Void> completed = null;
      synchronized (monitor) {
        ensureWritable();
        if (pendingWrite != null) {
          throw new IllegalStateException("a write is already pending in this direction");
        }
        if (pendingRead != null) {
          waitingRead = pendingRead;
          pendingRead = null;
          segment = takeSegment(input);
          if (!input.hasRemaining()) {
            completed = CompletableFuture.completedFuture(null);
          }
        }
        if (completed == null) {
          source = input;
          pendingWrite = new CompletableFuture<>();
          completed = pendingWrite;
        }
      }
      if (waitingRead != null) {
        waitingRead.complete(segment);
      }
      return completed;
    }

    private CompletionStage<Void> end(Object monitor) {
      CompletableFuture<ByteBuffer> waitingRead = null;
      CompletableFuture<Void> completion;
      boolean finishNow = false;
      synchronized (monitor) {
        if (ending != null) {
          return ending;
        }
        if (terminalFailure != null) {
          throw new IllegalStateException("tunnel stream is closed");
        }
        completion = new CompletableFuture<>();
        ending = completion;
        if (pendingWrite == null) {
          ended = true;
          if (pendingRead != null) {
            waitingRead = pendingRead;
            pendingRead = null;
          } else {
            finishNow = true;
          }
        }
      }
      if (waitingRead != null) {
        waitingRead.complete(null);
        finishNow = true;
      }
      if (finishNow) {
        completion.complete(null);
      }
      return completion;
    }

    private void takePending(List<CompletableFuture<?>> pending) {
      ended = true;
      source = null;
      if (pendingRead != null) {
        pending.add(pendingRead);
        pendingRead = null;
      }
      if (pendingWrite != null) {
        pending.add(pendingWrite);
        pendingWrite = null;
      }
      if (ending != null) {
        pending.add(ending);
      }
    }

    private ByteBuffer takeSegment() {
      return takeSegment(source);
    }

    private static ByteBuffer takeSegment(ByteBuffer input) {
      int size = Math.min(input.remaining(), MAX_SEGMENT_BYTES);
      ByteBuffer segment = ByteBuffer.allocate(size);
      int oldLimit = input.limit();
      input.limit(input.position() + size);
      segment.put(input);
      input.limit(oldLimit);
      segment.flip();
      return segment.asReadOnlyBuffer();
    }

    private void ensureWritable() {
      if (terminalFailure != null) {
        throw new IllegalStateException("tunnel stream is closed");
      }
      if (ended || ending != null) {
        throw new IllegalStateException("output is already ending or closed");
      }
    }
  }
}
