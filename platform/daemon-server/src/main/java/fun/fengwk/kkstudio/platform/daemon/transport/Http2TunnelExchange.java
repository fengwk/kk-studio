package fun.fengwk.kkstudio.platform.daemon.transport;

import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http2.ErrorCode;
import org.eclipse.jetty.http2.api.Stream;
import org.eclipse.jetty.http2.frames.DataFrame;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.http2.frames.ResetFrame;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;

import fun.fengwk.kkstudio.platform.daemon.tunnel.TunnelExchange;
import fun.fengwk.kkstudio.platform.daemon.tunnel.TunnelStream;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * 单条数据面 HTTP/2 流上的隧道交换。
 *
 * <p>每个方向最多一个未完成操作：读在挂起时才 demand，写在流 attach 前先排队；半关闭只关闭输出方向，入站 EOF 之后的读一律返回 {@code null}。
 *
 * <p>所有失败（对端 reset、传输失败、主动关闭）都收敛到同一处：完成未决 future、注销交换，并保证只收敛一次。
 */
final class Http2TunnelExchange implements TunnelExchange, TunnelStream {

  private Runnable onFinished;
  private final CompletableFuture<Response> responseHeadersFuture = new CompletableFuture<>();
  private final Object streamLock = new Object();
  private final Queue<PendingWrite> pendingWrites = new ArrayDeque<>();

  private Stream stream;
  private Throwable streamFailure;
  private boolean streamClosed;
  private boolean eof;
  private boolean outputEnded;
  private boolean writing;

  private CompletableFuture<ByteBuffer> pendingRead;
  private ByteBuffer unreadBuffer;
  private CompletableFuture<Void> activeWriteFuture;
  private CompletableFuture<Void> endOutputFuture;

  /** 登记终止回调；由所属隧道用于把本交换从活跃集合中移除。 */
  void onFinished(Runnable onFinished) {
    this.onFinished = onFinished;
  }

  void attachStream(Stream stream) {
    boolean shouldDemand = false;
    synchronized (streamLock) {
      this.stream = stream;
      if (streamClosed) {
        stream.reset(
            new ResetFrame(stream.getId(), ErrorCode.CANCEL_STREAM_ERROR.code), Callback.NOOP);
        return;
      }
      if (pendingRead != null) {
        shouldDemand = true;
      }
      if (!pendingWrites.isEmpty()) {
        triggerFlushWritesLocked();
      }
    }
    if (shouldDemand) {
      stream.demand();
    }
  }

  void failStream(Throwable failure) {
    CompletableFuture<ByteBuffer> readToFail = null;
    List<CompletableFuture<Void>> writesToFail = new ArrayList<>();
    CompletableFuture<Void> endOutputToFail = null;
    synchronized (streamLock) {
      if (streamFailure != null) {
        return;
      }
      streamFailure = failure;
      streamClosed = true;
      readToFail = pendingRead;
      pendingRead = null;
      if (activeWriteFuture != null) {
        writesToFail.add(activeWriteFuture);
        activeWriteFuture = null;
      }
      while (!pendingWrites.isEmpty()) {
        writesToFail.add(pendingWrites.poll().future);
      }
      endOutputToFail = endOutputFuture;
      endOutputFuture = null;
    }

    responseHeadersFuture.completeExceptionally(failure);
    if (readToFail != null) {
      readToFail.completeExceptionally(failure);
    }
    for (CompletableFuture<Void> wf : writesToFail) {
      wf.completeExceptionally(failure);
    }
    if (endOutputToFail != null) {
      endOutputToFail.completeExceptionally(failure);
    }
    removeActive();
  }

  private void removeActive() {
    onFinished.run();
  }

  Stream.Listener listener() {
    return new Stream.Listener() {
      @Override
      public void onHeaders(Stream source, HeadersFrame frame) {
        MetaData metaData = frame.getMetaData();
        if (metaData instanceof MetaData.Response response) {
          int status = response.getStatus();
          if (status >= 100 && status < 200) {
            // 跳过 1xx 信息响应（如 100 Continue）
            return;
          }
          Map<String, List<String>> headers = new LinkedHashMap<>();
          response
              .getHttpFields()
              .forEach(
                  field ->
                      headers
                          .computeIfAbsent(
                              field.getName().toLowerCase(Locale.ROOT), k -> new ArrayList<>())
                          .add(field.getValue()));
          responseHeadersFuture.complete(new Response(status, headers));
        }
        if (frame.isEndStream()) {
          onInboundEof();
        }
      }

      @Override
      public void onDataAvailable(Stream source) {
        Stream.Data data = source.readData();
        if (data == null) {
          source.demand();
          return;
        }
        ByteBuffer buffer = data.frame().getByteBuffer();
        int remaining = buffer.remaining();
        boolean endStream = data.frame().isEndStream();
        byte[] bytes = new byte[remaining];
        buffer.get(bytes);
        data.release();

        if (remaining == 0) {
          if (endStream) {
            onInboundEof();
          } else {
            source.demand();
          }
          return;
        }

        CompletableFuture<ByteBuffer> toComplete = null;
        ByteBuffer payload = ByteBuffer.wrap(bytes);

        synchronized (streamLock) {
          if (pendingRead != null) {
            toComplete = pendingRead;
            pendingRead = null;
          } else {
            unreadBuffer = payload;
          }
          if (endStream) {
            eof = true;
          }
        }

        if (toComplete != null) {
          toComplete.complete(payload);
        }
      }

      @Override
      public void onReset(Stream source, ResetFrame frame, Callback callback) {
        failStream(new IOException("stream reset by peer: " + frame.getError()));
        callback.succeeded();
      }

      @Override
      public void onFailure(
          Stream source, int error, String reason, Throwable failure, Callback callback) {
        failStream(failure != null ? failure : new IOException(reason));
        callback.succeeded();
      }
    };
  }

  private void onInboundEof() {
    CompletableFuture<ByteBuffer> toComplete = null;
    synchronized (streamLock) {
      eof = true;
      if (pendingRead != null && unreadBuffer == null) {
        toComplete = pendingRead;
        pendingRead = null;
      }
    }
    if (toComplete != null) {
      toComplete.complete(null);
    }
  }

  @Override
  public CompletionStage<Response> responseHeaders() {
    return responseHeadersFuture;
  }

  @Override
  public TunnelStream stream() {
    return this;
  }

  @Override
  public CompletionStage<ByteBuffer> read() {
    CompletableFuture<ByteBuffer> future;
    Stream s = null;
    synchronized (streamLock) {
      if (streamFailure != null) {
        return CompletableFuture.failedFuture(streamFailure);
      }
      if (streamClosed) {
        return CompletableFuture.failedFuture(new CancellationException("stream closed"));
      }
      if (pendingRead != null) {
        throw new IllegalStateException("a read operation is already pending");
      }
      if (unreadBuffer != null) {
        ByteBuffer data = unreadBuffer;
        unreadBuffer = null;
        return CompletableFuture.completedFuture(data);
      }
      if (eof) {
        return CompletableFuture.completedFuture(null);
      }

      pendingRead = new CompletableFuture<>();
      future = pendingRead;
      s = stream;
    }

    if (s != null) {
      s.demand();
    }
    return future;
  }

  @Override
  public CompletionStage<Void> write(ByteBuffer source) {
    if (source == null || !source.hasRemaining()) {
      throw new IllegalArgumentException("source must not be null and must have remaining bytes");
    }
    CompletableFuture<Void> writeFuture = new CompletableFuture<>();
    synchronized (streamLock) {
      if (streamFailure != null) {
        return CompletableFuture.failedFuture(streamFailure);
      }
      if (streamClosed) {
        return CompletableFuture.failedFuture(new CancellationException("stream closed"));
      }
      if (outputEnded || endOutputFuture != null) {
        throw new IllegalStateException("output direction has already been closed");
      }
      if (activeWriteFuture != null || !pendingWrites.isEmpty()) {
        throw new IllegalStateException("a write operation is already pending");
      }

      int remaining = source.remaining();
      byte[] copy = new byte[remaining];
      source.get(copy); // 成功推进 position 到 limit

      PendingWrite pw = new PendingWrite(ByteBuffer.wrap(copy), writeFuture, false);
      pendingWrites.add(pw);

      if (stream != null && !writing) {
        triggerFlushWritesLocked();
      }
      return writeFuture;
    }
  }

  @Override
  public CompletionStage<Void> endOutput() {
    synchronized (streamLock) {
      if (endOutputFuture != null) {
        return endOutputFuture;
      }
      if (outputEnded) {
        return CompletableFuture.completedFuture(null);
      }
      if (streamClosed) {
        return CompletableFuture.failedFuture(new CancellationException("stream closed"));
      }
      if (streamFailure != null) {
        return CompletableFuture.failedFuture(streamFailure);
      }

      endOutputFuture = new CompletableFuture<>();
      PendingWrite pw = new PendingWrite(BufferUtil.EMPTY_BUFFER, endOutputFuture, true);
      pendingWrites.add(pw);

      if (stream != null && !writing) {
        triggerFlushWritesLocked();
      }
      return endOutputFuture;
    }
  }

  private void triggerFlushWritesLocked() {
    if (writing || pendingWrites.isEmpty() || stream == null) {
      return;
    }
    writing = true;
    PendingWrite pw = pendingWrites.poll();
    activeWriteFuture = pw.future;

    stream.data(
        new DataFrame(stream.getId(), pw.buffer, pw.isEndStream),
        Callback.from(
            () -> {
              CompletableFuture<Void> done;
              synchronized (streamLock) {
                writing = false;
                done = activeWriteFuture;
                activeWriteFuture = null;
                if (pw.isEndStream) {
                  outputEnded = true;
                }
                if (!pendingWrites.isEmpty()) {
                  triggerFlushWritesLocked();
                }
              }
              if (done != null) {
                done.complete(null);
              }
            },
            error -> {
              CompletableFuture<Void> fail;
              synchronized (streamLock) {
                writing = false;
                fail = activeWriteFuture;
                activeWriteFuture = null;
              }
              if (fail != null) {
                fail.completeExceptionally(error);
              }
              failStream(error);
            }));
  }

  @Override
  public void close() {
    CompletableFuture<ByteBuffer> readToCancel = null;
    List<CompletableFuture<Void>> writesToCancel = new ArrayList<>();
    CompletableFuture<Void> endOutputToCancel = null;
    Stream s = null;
    synchronized (streamLock) {
      if (streamClosed) {
        return;
      }
      streamClosed = true;
      readToCancel = pendingRead;
      pendingRead = null;
      if (activeWriteFuture != null) {
        writesToCancel.add(activeWriteFuture);
        activeWriteFuture = null;
      }
      while (!pendingWrites.isEmpty()) {
        writesToCancel.add(pendingWrites.poll().future);
      }
      endOutputToCancel = endOutputFuture;
      endOutputFuture = null;
      s = stream;
    }

    CancellationException cancel = new CancellationException("tunnel stream closed");
    responseHeadersFuture.completeExceptionally(cancel);
    if (readToCancel != null) {
      readToCancel.completeExceptionally(cancel);
    }
    for (CompletableFuture<Void> wf : writesToCancel) {
      wf.completeExceptionally(cancel);
    }
    if (endOutputToCancel != null) {
      endOutputToCancel.completeExceptionally(cancel);
    }

    if (s != null) {
      s.reset(new ResetFrame(s.getId(), ErrorCode.CANCEL_STREAM_ERROR.code), Callback.NOOP);
    }
    removeActive();
  }

  private record PendingWrite(
      ByteBuffer buffer, CompletableFuture<Void> future, boolean isEndStream) {}
}
