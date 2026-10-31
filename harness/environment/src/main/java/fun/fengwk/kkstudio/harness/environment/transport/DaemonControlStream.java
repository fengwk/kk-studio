package fun.fengwk.kkstudio.harness.environment.transport;

import org.eclipse.jetty.http2.ErrorCode;
import org.eclipse.jetty.http2.api.Stream;
import org.eclipse.jetty.http2.frames.DataFrame;
import org.eclipse.jetty.http2.frames.ResetFrame;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;

import fun.fengwk.kkstudio.harness.environment.protocol.ControlFraming;
import fun.fengwk.kkstudio.harness.environment.protocol.ControlProtocolCodec;
import fun.fengwk.kkstudio.harness.environment.protocol.Envelope;
import fun.fengwk.kkstudio.harness.environment.server.DaemonControlChannel;
import fun.fengwk.kkstudio.harness.environment.server.DaemonOfferResult;
import fun.fengwk.kkstudio.harness.environment.server.DaemonSession;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.time.Clock;
import java.util.ArrayDeque;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 承载控制流（POST /control）的 {@link DaemonControlChannel} 实现。
 *
 * <p>双向 body 采用 4 字节大端前缀成帧传输 protobuf Envelope。出站队列有界（256 帧、16 MiB）， 超限返回 {@link
 * DaemonOfferResult#BUSY}；已排队帧超过 10 秒未写出判定连接失败； 任一方向结束或重置触发通道及底层会话关闭。
 */
public class DaemonControlStream implements DaemonControlChannel {

  public static final int MAX_PENDING_FRAMES = 256;
  public static final int MAX_PENDING_BYTES = 16 * 1024 * 1024;
  public static final long WRITE_TIMEOUT_MILLIS = 10_000;

  private final Stream stream;
  private final Runnable sessionCloser;
  private final Clock clock;
  private final Object lock = new Object();

  private final Queue<QueuedFrame> outboundQueue = new ArrayDeque<>();
  private final ByteArrayOutputStream inboundBuffer = new ByteArrayOutputStream();

  private DaemonSession daemonSession;
  private int pendingFrames;
  private int pendingBytes;
  private boolean writing;
  private boolean closed;
  private boolean draining;
  private final AtomicBoolean sessionClosedNotified = new AtomicBoolean(false);

  public DaemonControlStream(Stream stream, Runnable sessionCloser) {
    this(stream, sessionCloser, Clock.systemUTC());
  }

  DaemonControlStream(Stream stream, Runnable sessionCloser, Clock clock) {
    this.stream = Objects.requireNonNull(stream, "stream must not be null");
    this.sessionCloser = Objects.requireNonNull(sessionCloser, "sessionCloser must not be null");
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
  }

  /** 关联核心环境会话。 */
  public void bindSession(DaemonSession daemonSession) {
    synchronized (lock) {
      this.daemonSession = daemonSession;
    }
  }

  @Override
  public boolean isOpen() {
    synchronized (lock) {
      return !closed && !draining;
    }
  }

  @Override
  public DaemonOfferResult offer(Envelope envelope) {
    Objects.requireNonNull(envelope, "envelope must not be null");
    byte[] frameBytes = ControlProtocolCodec.encodeFrame(envelope);
    synchronized (lock) {
      if (closed || draining) {
        return DaemonOfferResult.CLOSED;
      }
      if (pendingFrames + 1 > MAX_PENDING_FRAMES
          || pendingBytes + frameBytes.length > MAX_PENDING_BYTES) {
        return DaemonOfferResult.BUSY;
      }
      outboundQueue.add(new QueuedFrame(frameBytes, clock.millis()));
      pendingFrames++;
      pendingBytes += frameBytes.length;
      triggerFlushLocked();
      return DaemonOfferResult.ACCEPTED;
    }
  }

  @Override
  public void closeAfterFlush() {
    synchronized (lock) {
      if (closed || draining) {
        return;
      }
      draining = true;
      triggerFlushLocked();
    }
  }

  @Override
  public void close() {
    synchronized (lock) {
      if (closed) {
        return;
      }
      closed = true;
      outboundQueue.clear();
      pendingFrames = 0;
      pendingBytes = 0;
    }
    try {
      stream.reset(
          new ResetFrame(stream.getId(), ErrorCode.CANCEL_STREAM_ERROR.code), Callback.NOOP);
    } catch (Throwable ignored) {
    }
    notifyClosed();
  }

  /** 处理入站 DATA 帧。 */
  public void onDataAvailable() {
    while (true) {
      Stream.Data data = stream.readData();
      if (data == null) {
        stream.demand();
        return;
      }
      ByteBuffer content = data.frame().getByteBuffer();
      byte[] chunk = new byte[content.remaining()];
      content.get(chunk);
      boolean endStream = data.frame().isEndStream();
      data.release();

      synchronized (lock) {
        inboundBuffer.writeBytes(chunk);
        drainInboundFramesLocked();
      }

      if (endStream) {
        onPeerClose();
        return;
      }
    }
  }

  private void drainInboundFramesLocked() {
    byte[] accumulated = inboundBuffer.toByteArray();
    int offset = 0;
    while (accumulated.length - offset >= Integer.BYTES) {
      int length =
          ((accumulated[offset] & 0xff) << 24)
              | ((accumulated[offset + 1] & 0xff) << 16)
              | ((accumulated[offset + 2] & 0xff) << 8)
              | (accumulated[offset + 3] & 0xff);
      if (length < 1 || length > ControlFraming.MAX_MESSAGE_BYTES) {
        failChannel(new IOException("invalid inbound frame length: " + length));
        return;
      }
      int totalFrameBytes = Integer.BYTES + length;
      if (accumulated.length - offset < totalFrameBytes) {
        break;
      }
      byte[] frame = new byte[totalFrameBytes];
      System.arraycopy(accumulated, offset, frame, 0, totalFrameBytes);
      offset += totalFrameBytes;

      Envelope envelope = ControlProtocolCodec.decodeFrame(frame);
      if (daemonSession != null) {
        daemonSession.receive(envelope);
      }
    }

    inboundBuffer.reset();
    if (offset < accumulated.length) {
      inboundBuffer.write(accumulated, offset, accumulated.length - offset);
    }
  }

  /** 对端重置控制流。 */
  public void onReset() {
    failChannel(new IOException("control stream reset by peer"));
  }

  /** 控制流发生底层失败。 */
  public void onFailure(Throwable failure) {
    failChannel(failure);
  }

  private void onPeerClose() {
    synchronized (lock) {
      closed = true;
    }
    notifyClosed();
  }

  private void failChannel(Throwable failure) {
    synchronized (lock) {
      if (closed) {
        return;
      }
      closed = true;
      outboundQueue.clear();
      pendingFrames = 0;
      pendingBytes = 0;
    }
    notifyClosed();
  }

  private void notifyClosed() {
    if (sessionClosedNotified.compareAndSet(false, true)) {
      if (daemonSession != null) {
        daemonSession.closed();
      }
      sessionCloser.run();
    }
  }

  private void triggerFlushLocked() {
    if (writing || closed) {
      return;
    }
    if (outboundQueue.isEmpty()) {
      if (draining) {
        closed = true;
        stream.data(new DataFrame(BufferUtil.EMPTY_BUFFER, true), Callback.NOOP);
        notifyClosed();
      }
      return;
    }

    QueuedFrame head = outboundQueue.peek();
    long elapsed = clock.millis() - head.enqueuedAt();
    if (elapsed > WRITE_TIMEOUT_MILLIS) {
      failChannel(new IOException("frame write timeout after " + elapsed + "ms"));
      return;
    }

    writing = true;
    outboundQueue.poll();
    stream.data(
        new DataFrame(ByteBuffer.wrap(head.bytes()), false),
        Callback.from(
            () -> {
              synchronized (lock) {
                writing = false;
                pendingFrames--;
                pendingBytes -= head.bytes().length;
                triggerFlushLocked();
              }
            },
            failure -> {
              synchronized (lock) {
                writing = false;
              }
              failChannel(failure);
            }));
  }

  private record QueuedFrame(byte[] bytes, long enqueuedAt) {}
}
