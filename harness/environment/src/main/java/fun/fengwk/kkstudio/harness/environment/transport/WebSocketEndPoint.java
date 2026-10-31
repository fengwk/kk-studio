package fun.fengwk.kkstudio.harness.environment.transport;

import org.eclipse.jetty.io.AbstractEndPoint;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.thread.Scheduler;

import java.io.IOException;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.util.ArrayDeque;
import java.util.Objects;
import java.util.Queue;

/**
 * 由底层 WebSocket 二进制管道支撑的 Jetty {@link org.eclipse.jetty.io.EndPoint}。
 *
 * <p>入站数据由管道接收并由 {@link #fill(ByteBuffer)} 驱动读取；出站数据由 {@link #flush(ByteBuffer...)} 切片为至多 1 MiB
 * 的二进制消息异步发出，由底层发送回调驱动 {@link org.eclipse.jetty.io.WriteFlusher} 推进后续写入。
 */
public class WebSocketEndPoint extends AbstractEndPoint {

  /** 单条 WebSocket 消息的最大发送载荷：1 MiB。 */
  public static final int MAX_MESSAGE_BYTES = 1024 * 1024;

  private final WebSocketPipe pipe;
  private final Object lock = new Object();
  private final Queue<InboundEntry> inbound = new ArrayDeque<>();

  private boolean eof;
  private Throwable closedException;
  private boolean writePending;

  public WebSocketEndPoint(Scheduler scheduler, WebSocketPipe pipe) {
    super(scheduler);
    this.pipe = Objects.requireNonNull(pipe, "pipe must not be null");
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
        callback.succeeded();
      }
      return;
    }
    synchronized (lock) {
      if (eof || isInputShutdown()) {
        if (callback != null) {
          callback.succeeded();
        }
        return;
      }
      inbound.add(new InboundEntry(data.slice(), callback));
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
    getFillInterest().onClose();
    getWriteFlusher().onFail(cause);
    close(cause);
  }

  @Override
  public int fill(ByteBuffer buffer) throws IOException {
    synchronized (lock) {
      if (closedException != null) {
        throw new IOException("endpoint closed exceptionally", closedException);
      }
      if (isInputShutdown()) {
        return -1;
      }
      int filled = 0;
      while (buffer.hasRemaining() && !inbound.isEmpty()) {
        InboundEntry entry = inbound.peek();
        ByteBuffer source = entry.buffer();
        int remainingBefore = buffer.remaining();
        BufferUtil.put(source, buffer);
        filled += (remainingBefore - buffer.remaining());
        if (!source.hasRemaining()) {
          inbound.poll();
          if (entry.callback() != null) {
            entry.callback().succeeded();
          }
        }
      }
      if (filled > 0) {
        return filled;
      }
      if (eof) {
        shutdownInput();
        return -1;
      }
      return 0;
    }
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
  protected void onIncompleteFlush() {}

  @Override
  protected void needsFillInterest() {
    boolean ready;
    synchronized (lock) {
      ready = eof || !inbound.isEmpty() || closedException != null;
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
