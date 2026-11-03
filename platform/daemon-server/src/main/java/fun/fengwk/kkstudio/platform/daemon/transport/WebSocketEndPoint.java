package fun.fengwk.kkstudio.platform.daemon.transport;

import org.eclipse.jetty.io.AbstractEndPoint;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.thread.Scheduler;
import org.eclipse.jetty.websocket.api.Callback;

import java.io.IOException;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.util.Objects;

/**
 * 由底层 WebSocket 二进制管道支撑的 Jetty {@link org.eclipse.jetty.io.EndPoint}。
 *
 * <p>入站背压由本端点主动控制：打开连接时调用一次 {@link WebSocketPipe#demand()}，入站队列中最多保留一条消息；每条消息被 {@link
 * #fill(ByteBuffer)} 完全消费后再 demand 下一条消息。 消息消费回调和 {@code fillable()} 通知均在锁外执行。 出站数据由 {@link
 * #flush(ByteBuffer...)} 切片为至多 1 MiB 的二进制消息异步发出，由底层发送回调驱动 {@link org.eclipse.jetty.io.WriteFlusher}
 * 推进后续写入。
 */
public class WebSocketEndPoint extends AbstractEndPoint {

  /** 单条 WebSocket 消息的最大发送载荷：1 MiB。 */
  public static final int MAX_MESSAGE_BYTES = 1024 * 1024;

  private final WebSocketPipe pipe;
  private final Object lock = new Object();

  private InboundEntry currentEntry;
  private boolean eof;
  private Throwable closedException;
  private boolean writePending;

  public WebSocketEndPoint(Scheduler scheduler, WebSocketPipe pipe) {
    super(scheduler);
    this.pipe = Objects.requireNonNull(pipe, "pipe must not be null");
  }

  @Override
  public void onOpen() {
    super.onOpen();
    pipe.demand();
  }

  /**
   * 底层收到一条二进制消息。
   *
   * @param data 消息载荷
   * @param callback 消息消费完成回调
   */
  public void onInboundData(ByteBuffer data, Callback callback) {
    if (data == null || !data.hasRemaining()) {
      if (callback != null) {
        callback.succeed();
      }
      pipe.demand();
      return;
    }

    boolean accepted = false;
    synchronized (lock) {
      if (!eof && !isInputShutdown() && closedException == null && currentEntry == null) {
        currentEntry = new InboundEntry(data.slice(), callback);
        accepted = true;
      }
    }

    if (!accepted) {
      if (callback != null) {
        callback.succeed();
      }
      return;
    }

    getFillInterest().fillable();
  }

  /** 底层 WebSocket 连接正常关闭或收到 EOF。 */
  public void onInboundEof() {
    synchronized (lock) {
      eof = true;
    }
    getFillInterest().fillable();
  }

  /** 底层 WebSocket 传输异常终止。 */
  public void onInboundError(Throwable cause) {
    synchronized (lock) {
      closedException = cause;
    }
    close(cause);
  }

  @Override
  public int fill(ByteBuffer buffer) throws IOException {
    Callback callbackToComplete = null;
    boolean needDemand = false;
    int filled = 0;

    synchronized (lock) {
      if (closedException != null) {
        throw new IOException("endpoint closed exceptionally", closedException);
      }
      if (isInputShutdown()) {
        return -1;
      }
      if (currentEntry != null) {
        ByteBuffer source = currentEntry.buffer();
        if (buffer.position() == 0 && buffer.limit() == buffer.capacity()) {
          buffer.limit(0);
        }
        filled = BufferUtil.append(buffer, source);
        if (!source.hasRemaining()) {
          callbackToComplete = currentEntry.callback();
          currentEntry = null;
          needDemand = !eof && !isInputShutdown();
        }
      }
      if (filled > 0) {
        // 延后在锁外完成 callback 和 demand
      } else if (eof) {
        shutdownInput();
        return -1;
      } else {
        return 0;
      }
    }

    if (callbackToComplete != null) {
      callbackToComplete.succeed();
    }
    if (needDemand) {
      pipe.demand();
    }
    return filled;
  }

  @Override
  public boolean flush(ByteBuffer... buffers) throws IOException {
    if (isOutputShutdown()) {
      throw new ClosedChannelException();
    }
    synchronized (lock) {
      if (closedException != null) {
        throw new IOException("endpoint closed exceptionally", closedException);
      }
      if (writePending) {
        return false;
      }
      int totalRemaining = 0;
      for (ByteBuffer b : buffers) {
        totalRemaining += b.remaining();
      }
      if (totalRemaining == 0) {
        return true;
      }
      int toSend = Math.min(totalRemaining, MAX_MESSAGE_BYTES);
      byte[] packet = new byte[toSend];
      int offset = 0;
      for (ByteBuffer b : buffers) {
        int take = Math.min(b.remaining(), toSend - offset);
        if (take > 0) {
          b.get(packet, offset, take);
          offset += take;
          if (offset == toSend) {
            break;
          }
        }
      }
      writePending = true;
      pipe.sendBinary(
          ByteBuffer.wrap(packet), Callback.from(this::onWriteSucceeded, this::onWriteFailed));
      return false;
    }
  }

  private void onWriteSucceeded() {
    synchronized (lock) {
      writePending = false;
    }
    getWriteFlusher().completeWrite();
  }

  private void onWriteFailed(Throwable cause) {
    synchronized (lock) {
      writePending = false;
      closedException = cause;
    }
    getWriteFlusher().onFail(cause);
    close(cause);
  }

  @Override
  protected void onIncompleteFlush() {
    boolean pending;
    Throwable failure;
    synchronized (lock) {
      pending = writePending;
      failure = closedException;
    }
    if (failure != null) {
      getWriteFlusher().onFail(failure);
    } else if (!pending) {
      getWriteFlusher().completeWrite();
    }
  }

  @Override
  protected void needsFillInterest() {
    boolean ready;
    synchronized (lock) {
      ready = eof || currentEntry != null || closedException != null;
    }
    if (ready) {
      getFillInterest().fillable();
    }
  }

  @Override
  protected void doShutdownOutput() {
    pipe.close(1000, "shutdown output");
  }

  @Override
  protected void doClose() {
    synchronized (lock) {
      eof = true;
    }
    pipe.close(1000, "close");
  }

  @Override
  public Object getTransport() {
    return pipe;
  }

  @Override
  public SocketAddress getLocalSocketAddress() {
    return pipe.getLocalSocketAddress();
  }

  @Override
  public SocketAddress getRemoteSocketAddress() {
    return pipe.getRemoteSocketAddress();
  }

  private record InboundEntry(ByteBuffer buffer, Callback callback) {}
}
