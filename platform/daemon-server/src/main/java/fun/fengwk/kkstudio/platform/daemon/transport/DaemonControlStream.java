package fun.fengwk.kkstudio.platform.daemon.transport;

import org.eclipse.jetty.http2.ErrorCode;
import org.eclipse.jetty.http2.api.Stream;
import org.eclipse.jetty.http2.frames.DataFrame;
import org.eclipse.jetty.http2.frames.ResetFrame;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.thread.Scheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import fun.fengwk.kkstudio.platform.daemon.protocol.ControlFraming;
import fun.fengwk.kkstudio.platform.daemon.protocol.ControlProtocolCodec;
import fun.fengwk.kkstudio.platform.daemon.protocol.ControlProtocolException;
import fun.fengwk.kkstudio.platform.daemon.protocol.Envelope;
import fun.fengwk.kkstudio.platform.daemon.protocol.Error;
import fun.fengwk.kkstudio.platform.daemon.protocol.MessageType;
import fun.fengwk.kkstudio.platform.daemon.server.DaemonConnectionIdentity;
import fun.fengwk.kkstudio.platform.daemon.server.DaemonControlChannel;
import fun.fengwk.kkstudio.platform.daemon.server.DaemonOfferResult;
import fun.fengwk.kkstudio.platform.daemon.server.DaemonSession;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 承载单个 Daemon {@code POST /control} HTTP/2 流的双向控制信道。
 *
 * <p>遵循控制信道契约：256 帧 / 16 MiB 出站容量预算、10s 写入截止时间、增量长度前缀成帧与锁外按序交付、绑定前不预读、 并在 {@code closeAfterFlush()}
 * 时等待 {@code END_STREAM} 完成回调后再关闭会话。
 */
public class DaemonControlStream implements DaemonControlChannel {

  private static final Logger log = LoggerFactory.getLogger(DaemonControlStream.class);

  public static final int MAX_PENDING_FRAMES = 256;
  public static final int MAX_PENDING_BYTES = 16 * 1024 * 1024;
  public static final Duration WRITE_DEADLINE = Duration.ofSeconds(10);

  private final DaemonConnectionIdentity identity;
  private final Stream stream;
  private final Runnable sessionCloser;
  private final Runnable sessionDrainer;
  private final Scheduler scheduler;
  private final Object lock = new Object();

  private final Queue<QueuedFrame> outboundQueue = new ArrayDeque<>();
  private final ByteBuffer lengthBuffer = ByteBuffer.allocate(Integer.BYTES);
  private byte[] bodyBuffer;
  private int bodyFilled;

  private DaemonSession daemonSession;
  private int pendingFrames;
  private long pendingBytes;
  private boolean flushing;
  private boolean closed;
  private boolean draining;
  private final AtomicBoolean sessionClosedNotified = new AtomicBoolean(false);

  public DaemonControlStream(
      DaemonConnectionIdentity identity,
      Stream stream,
      Runnable sessionCloser,
      Runnable sessionDrainer,
      Scheduler scheduler) {
    this.identity = Objects.requireNonNull(identity, "identity must not be null");
    this.stream = Objects.requireNonNull(stream, "stream must not be null");
    this.sessionCloser = Objects.requireNonNull(sessionCloser, "sessionCloser must not be null");
    this.sessionDrainer = Objects.requireNonNull(sessionDrainer, "sessionDrainer must not be null");
    this.scheduler = Objects.requireNonNull(scheduler, "scheduler must not be null");
  }

  /** 关联核心环境会话并触发首次需求。在绑定前不得 demand，以防丢弃入站帧。 */
  public void bindSession(DaemonSession daemonSession) {
    Objects.requireNonNull(daemonSession, "daemonSession must not be null");
    synchronized (lock) {
      this.daemonSession = daemonSession;
    }
    stream.demand();
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
      enqueueFrameLocked(frameBytes);
      return DaemonOfferResult.ACCEPTED;
    }
  }

  private void enqueueFrameLocked(byte[] frameBytes) {
    QueuedFrame frame = new QueuedFrame(frameBytes);
    Scheduler.Task timeoutTask =
        scheduler.schedule(
            () -> onFrameTimeout(frame), WRITE_DEADLINE.toMillis(), TimeUnit.MILLISECONDS);
    frame.setTimeoutTask(timeoutTask);

    outboundQueue.add(frame);
    pendingFrames++;
    pendingBytes += frameBytes.length;
    triggerFlushLocked();
  }

  private void onFrameTimeout(QueuedFrame frame) {
    log.error("Control frame write timed out after 10s on stream {}", stream.getId());
    failChannel(new TimeoutException("control frame write deadline exceeded 10s"));
  }

  @Override
  public void drain() {
    sessionDrainer.run();
  }

  @Override
  public void closeAfterFlush() {
    synchronized (lock) {
      if (closed) {
        return;
      }
      draining = true;
      if (pendingFrames == 0 && !flushing) {
        sendEndStreamLocked();
      }
    }
  }

  @Override
  public void close() {
    synchronized (lock) {
      if (closed) {
        return;
      }
      closed = true;
      clearPendingLocked();
    }
    stream.reset(
        new ResetFrame(stream.getId(), ErrorCode.CANCEL_STREAM_ERROR.code),
        Callback.from(this::notifyClosed, error -> notifyClosed()));
  }

  private void clearPendingLocked() {
    while (!outboundQueue.isEmpty()) {
      QueuedFrame frame = outboundQueue.poll();
      frame.cancelTimeout();
    }
    pendingFrames = 0;
    pendingBytes = 0;
  }

  private void triggerFlushLocked() {
    if (flushing || closed || outboundQueue.isEmpty()) {
      return;
    }
    flushing = true;
    QueuedFrame next = outboundQueue.poll();
    pendingFrames--;
    pendingBytes -= next.bytes.length;

    stream.data(
        new DataFrame(stream.getId(), ByteBuffer.wrap(next.bytes), false),
        Callback.from(
            () -> {
              next.cancelTimeout();
              onFlushSuccess();
            },
            failure -> {
              next.cancelTimeout();
              onFlushFailure(failure);
            }));
  }

  private void onFlushSuccess() {
    boolean shouldSendEnd = false;
    synchronized (lock) {
      flushing = false;
      if (closed) {
        return;
      }
      if (!outboundQueue.isEmpty()) {
        triggerFlushLocked();
        return;
      }
      if (draining) {
        shouldSendEnd = true;
      }
    }
    if (shouldSendEnd) {
      synchronized (lock) {
        sendEndStreamLocked();
      }
    }
  }

  private void sendEndStreamLocked() {
    flushing = true;
    stream.data(
        new DataFrame(stream.getId(), BufferUtil.EMPTY_BUFFER, true),
        Callback.from(
            () -> {
              synchronized (lock) {
                flushing = false;
                closed = true;
              }
              notifyClosed();
              sessionCloser.run();
            },
            failure -> {
              synchronized (lock) {
                flushing = false;
                closed = true;
              }
              log.warn("Failed to send END_STREAM on control stream", failure);
              notifyClosed();
              sessionCloser.run();
            }));
  }

  private void onFlushFailure(Throwable failure) {
    failChannel(failure);
  }

  private void failChannel(Throwable failure) {
    synchronized (lock) {
      if (closed) {
        return;
      }
      closed = true;
      clearPendingLocked();
    }
    notifyClosed();
    sessionCloser.run();
  }

  private void notifyClosed() {
    if (sessionClosedNotified.compareAndSet(false, true)) {
      DaemonSession target;
      synchronized (lock) {
        target = daemonSession;
      }
      if (target != null) {
        target.closed();
      }
    }
  }

  /** 处理对端关闭流。 */
  private void onPeerClose() {
    notifyClosed();
  }

  public Stream.Listener listener() {
    return new Stream.Listener() {
      @Override
      public void onDataAvailable(Stream source) {
        List<Envelope> readyEnvelopes = new ArrayList<>();
        String errorMsg = null;
        boolean peerEnded = false;

        while (true) {
          Stream.Data data = source.readData();
          if (data == null) {
            break;
          }
          ByteBuffer buffer = data.frame().getByteBuffer();
          boolean endStream = data.frame().isEndStream();

          synchronized (lock) {
            while (buffer.hasRemaining() && errorMsg == null) {
              if (bodyBuffer == null) {
                BufferUtil.put(buffer, lengthBuffer);
                if (!lengthBuffer.hasRemaining()) {
                  lengthBuffer.flip();
                  int length = lengthBuffer.getInt();
                  lengthBuffer.clear();
                  if (length < 1 || length > ControlFraming.MAX_MESSAGE_BYTES) {
                    errorMsg = "invalid inbound frame length: " + length;
                    break;
                  }
                  bodyBuffer = new byte[length];
                  bodyFilled = 0;
                }
              } else {
                int needed = bodyBuffer.length - bodyFilled;
                int toTake = Math.min(needed, buffer.remaining());
                buffer.get(bodyBuffer, bodyFilled, toTake);
                bodyFilled += toTake;
                if (bodyFilled == bodyBuffer.length) {
                  try {
                    Envelope envelope = ControlProtocolCodec.decodeBinary(bodyBuffer);
                    readyEnvelopes.add(envelope);
                  } catch (ControlProtocolException ex) {
                    errorMsg = "failed to decode control envelope: " + ex.getMessage();
                    break;
                  } finally {
                    bodyBuffer = null;
                    bodyFilled = 0;
                    lengthBuffer.clear();
                  }
                }
              }
            }
          }

          data.release();
          if (endStream) {
            peerEnded = true;
            break;
          }
          if (errorMsg != null) {
            break;
          }
        }

        // 锁外按到达顺序分发给 DaemonSession.receive（绑定前不 demand，因此会话已就绪）
        DaemonSession currentSession;
        synchronized (lock) {
          currentSession = daemonSession;
        }
        for (Envelope envelope : readyEnvelopes) {
          currentSession.receive(envelope);
        }

        if (errorMsg != null) {
          log.warn("Control stream protocol violation: {}", errorMsg);
          Envelope errEnvelope =
              Envelope.newBuilder()
                  .setMessageType(MessageType.ERROR)
                  .setEnvironmentId(identity.environmentId().value().toString())
                  .setError(Error.newBuilder().setMessage(errorMsg).build())
                  .build();
          offer(errEnvelope);
          closeAfterFlush();
          return;
        }

        if (peerEnded) {
          onPeerClose();
          return;
        }

        synchronized (lock) {
          if (daemonSession != null && !closed && !draining) {
            source.demand();
          }
        }
      }

      @Override
      public void onReset(Stream source, ResetFrame frame, Callback callback) {
        failChannel(new IOException("control stream reset by peer: " + frame.getError()));
        callback.succeeded();
      }

      @Override
      public void onFailure(
          Stream source, int error, String reason, Throwable failure, Callback callback) {
        failChannel(failure != null ? failure : new IOException(reason));
        callback.succeeded();
      }
    };
  }

  private static final class QueuedFrame {
    final byte[] bytes;
    private Scheduler.Task timeoutTask;

    QueuedFrame(byte[] bytes) {
      this.bytes = bytes;
    }

    void setTimeoutTask(Scheduler.Task task) {
      this.timeoutTask = task;
    }

    void cancelTimeout() {
      if (timeoutTask != null) {
        timeoutTask.cancel();
        timeoutTask = null;
      }
    }
  }
}
