package fun.fengwk.kkstudio.harness.environment.transport;

import org.eclipse.jetty.http.HostPortHttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpScheme;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http2.ErrorCode;
import org.eclipse.jetty.http2.api.Session;
import org.eclipse.jetty.http2.api.Stream;
import org.eclipse.jetty.http2.frames.DataFrame;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.http2.frames.ResetFrame;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Promise;

import fun.fengwk.kkstudio.harness.environment.server.DaemonConnectionIdentity;
import fun.fengwk.kkstudio.harness.environment.tunnel.EnvironmentTunnel;
import fun.fengwk.kkstudio.harness.environment.tunnel.TunnelExchange;
import fun.fengwk.kkstudio.harness.environment.tunnel.TunnelHttpRequest;
import fun.fengwk.kkstudio.harness.environment.tunnel.TunnelStream;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.BooleanSupplier;

/**
 * 承载数据面 HTTP 交换与 extended CONNECT WebSocket 隧道的 {@link EnvironmentTunnel} 实现。
 *
 * <p>遵循隧道窄契约：单流每个方向最多一个未完成操作；严格按需需求（未挂起 read 时不主动 demand），杜绝无界预读； 针对流尚未 attach 时的写与半关闭支持按序排队；跳过 1xx
 * 信息响应；EOF 重复返回 {@code null}；写成功推进 position 到 limit。
 */
public class DaemonEnvironmentTunnel implements EnvironmentTunnel {

  private final DaemonConnectionIdentity identity;
  private final Session session;
  private final BooleanSupplier closedCheck;
  private final List<Http2TunnelExchange> activeExchanges = new ArrayList<>();
  private final Object lock = new Object();
  private boolean draining;
  private boolean closed;

  public DaemonEnvironmentTunnel(
      DaemonConnectionIdentity identity, Session session, BooleanSupplier closedCheck) {
    this.identity = Objects.requireNonNull(identity, "identity must not be null");
    this.session = Objects.requireNonNull(session, "session must not be null");
    this.closedCheck = Objects.requireNonNull(closedCheck, "closedCheck must not be null");
  }

  @Override
  public DaemonConnectionIdentity identity() {
    return identity;
  }

  /** 开始排空会话，拒绝新建交换。 */
  public void drain() {
    synchronized (lock) {
      draining = true;
    }
  }

  /** 关闭整个隧道及所有活跃流。 */
  public void onSessionClosed(Throwable failure) {
    List<Http2TunnelExchange> copy;
    synchronized (lock) {
      if (closed) {
        return;
      }
      closed = true;
      copy = new ArrayList<>(activeExchanges);
      activeExchanges.clear();
    }
    Throwable cause = failure != null ? failure : new IOException("tunnel session closed");
    for (Http2TunnelExchange exchange : copy) {
      exchange.failStream(cause);
    }
  }

  @Override
  public TunnelExchange openHttp(TunnelHttpRequest request) {
    if (request == null) {
      throw new IllegalArgumentException("request must not be null");
    }
    checkAvailable();

    HttpFields.Mutable fields = HttpFields.build();
    request
        .headers()
        .forEach(
            (name, values) -> {
              for (String v : values) {
                fields.add(name, v);
              }
            });

    MetaData.Request meta =
        new MetaData.Request(
            request.method(),
            HttpURI.from("https://daemon" + request.path()),
            HttpVersion.HTTP_2,
            fields);

    return createExchange(meta);
  }

  @Override
  public TunnelExchange openWebSocket(TunnelHttpRequest request) {
    if (request == null) {
      throw new IllegalArgumentException("request must not be null");
    }
    if (!"GET".equals(request.method())) {
      throw new IllegalArgumentException("openWebSocket request method must be GET");
    }
    checkAvailable();

    HttpFields.Mutable fields = HttpFields.build();
    request
        .headers()
        .forEach(
            (name, values) -> {
              for (String v : values) {
                fields.add(name, v);
              }
            });

    MetaData.ConnectRequest meta =
        new MetaData.ConnectRequest(
            HttpScheme.HTTPS, new HostPortHttpField("daemon"), request.path(), fields, "websocket");

    return createExchange(meta);
  }

  private void checkAvailable() {
    synchronized (lock) {
      if (closed || draining || closedCheck.getAsBoolean()) {
        throw new IllegalStateException("tunnel session is closed or draining");
      }
    }
  }

  private TunnelExchange createExchange(MetaData.Request meta) {
    Http2TunnelExchange exchange = new Http2TunnelExchange();
    synchronized (lock) {
      activeExchanges.add(exchange);
    }

    session.newStream(
        new HeadersFrame(meta, null, false),
        new Promise<>() {
          @Override
          public void succeeded(Stream stream) {
            exchange.attachStream(stream);
          }

          @Override
          public void failed(Throwable failure) {
            exchange.failStream(failure);
          }
        },
        exchange.listener());

    return exchange;
  }

  private class Http2TunnelExchange implements TunnelExchange, TunnelStream {

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
      synchronized (lock) {
        activeExchanges.remove(this);
      }
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
  }

  private record PendingWrite(
      ByteBuffer buffer, CompletableFuture<Void> future, boolean isEndStream) {}
}
