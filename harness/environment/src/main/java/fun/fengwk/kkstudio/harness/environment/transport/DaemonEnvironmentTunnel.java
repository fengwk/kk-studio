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
 * <p>遵循隧道窄契约：单流每个方向最多一个未完成操作；正常 EOF 持续完成 {@code null}；写操作成功时推进 position 到 limit； {@link
 * #openWebSocket} 仅允许 GET 并映射为 extended CONNECT。
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
    private final Queue<ByteBuffer> inboundQueue = new ArrayDeque<>();

    private Stream stream;
    private Throwable streamFailure;
    private boolean streamClosed;
    private boolean eof;
    private boolean outputEnded;
    private CompletableFuture<ByteBuffer> pendingRead;
    private CompletableFuture<Void> pendingWrite;
    private CompletableFuture<Void> endOutputFuture;

    void attachStream(Stream stream) {
      synchronized (streamLock) {
        this.stream = stream;
        if (streamClosed) {
          try {
            stream.reset(
                new ResetFrame(stream.getId(), ErrorCode.CANCEL_STREAM_ERROR.code), Callback.NOOP);
          } catch (Throwable ignored) {
          }
        }
      }
    }

    void failStream(Throwable failure) {
      CompletableFuture<ByteBuffer> readToFail = null;
      CompletableFuture<Void> writeToFail = null;
      synchronized (streamLock) {
        if (streamFailure != null) {
          return;
        }
        streamFailure = failure;
        streamClosed = true;
        readToFail = pendingRead;
        pendingRead = null;
        writeToFail = pendingWrite;
        pendingWrite = null;
      }
      responseHeadersFuture.completeExceptionally(failure);
      if (readToFail != null) {
        readToFail.completeExceptionally(failure);
      }
      if (writeToFail != null) {
        writeToFail.completeExceptionally(failure);
      }
      if (endOutputFuture != null) {
        endOutputFuture.completeExceptionally(failure);
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
            Map<String, List<String>> headers = new LinkedHashMap<>();
            response
                .getHttpFields()
                .forEach(
                    field ->
                        headers
                            .computeIfAbsent(
                                field.getName().toLowerCase(Locale.ROOT), k -> new ArrayList<>())
                            .add(field.getValue()));
            responseHeadersFuture.complete(new Response(response.getStatus(), headers));
          }
          if (frame.isEndStream()) {
            onInboundEof();
          } else {
            source.demand();
          }
        }

        @Override
        public void onDataAvailable(Stream source) {
          while (true) {
            Stream.Data data = source.readData();
            if (data == null) {
              source.demand();
              return;
            }
            ByteBuffer buffer = data.frame().getByteBuffer();
            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
            boolean endStream = data.frame().isEndStream();
            data.release();

            if (bytes.length > 0) {
              onInboundBytes(ByteBuffer.wrap(bytes));
            }
            if (endStream) {
              onInboundEof();
              return;
            }
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

    private void onInboundBytes(ByteBuffer data) {
      CompletableFuture<ByteBuffer> readToComplete = null;
      synchronized (streamLock) {
        if (pendingRead != null) {
          readToComplete = pendingRead;
          pendingRead = null;
        } else {
          inboundQueue.add(data);
        }
      }
      if (readToComplete != null) {
        readToComplete.complete(data);
      }
    }

    private void onInboundEof() {
      CompletableFuture<ByteBuffer> readToComplete = null;
      synchronized (streamLock) {
        eof = true;
        if (pendingRead != null && inboundQueue.isEmpty()) {
          readToComplete = pendingRead;
          pendingRead = null;
        }
      }
      if (readToComplete != null) {
        readToComplete.complete(null);
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
        if (!inboundQueue.isEmpty()) {
          return CompletableFuture.completedFuture(inboundQueue.poll());
        }
        if (eof) {
          return CompletableFuture.completedFuture(null);
        }
        pendingRead = new CompletableFuture<>();
        if (stream != null) {
          stream.demand();
        }
        return pendingRead;
      }
    }

    @Override
    public CompletionStage<Void> write(ByteBuffer source) {
      if (source == null || !source.hasRemaining()) {
        throw new IllegalArgumentException("source must not be null and must have remaining bytes");
      }
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
        if (pendingWrite != null) {
          throw new IllegalStateException("a write operation is already pending");
        }
        if (stream == null) {
          pendingWrite = new CompletableFuture<>();
          // stream 异步建立中，等待附加
          return pendingWrite;
        }

        pendingWrite = new CompletableFuture<>();
        int remaining = source.remaining();
        byte[] copy = new byte[remaining];
        source.get(copy);
        stream.data(
            new DataFrame(stream.getId(), ByteBuffer.wrap(copy), false),
            Callback.from(
                () -> {
                  CompletableFuture<Void> future;
                  synchronized (streamLock) {
                    future = pendingWrite;
                    pendingWrite = null;
                  }
                  if (future != null) {
                    future.complete(null);
                  }
                },
                failure -> {
                  CompletableFuture<Void> future;
                  synchronized (streamLock) {
                    future = pendingWrite;
                    pendingWrite = null;
                  }
                  if (future != null) {
                    future.completeExceptionally(failure);
                  }
                  failStream(failure);
                }));
        return pendingWrite;
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
        CompletableFuture<Void> current = pendingWrite;
        if (current != null) {
          current.whenComplete(
              (res, err) -> {
                if (err != null) {
                  endOutputFuture.completeExceptionally(err);
                } else {
                  sendEndStreamData();
                }
              });
        } else {
          sendEndStreamData();
        }
        return endOutputFuture;
      }
    }

    private void sendEndStreamData() {
      synchronized (streamLock) {
        if (stream == null) {
          outputEnded = true;
          endOutputFuture.complete(null);
          return;
        }
        stream.data(
            new DataFrame(stream.getId(), BufferUtil.EMPTY_BUFFER, true),
            Callback.from(
                () -> {
                  synchronized (streamLock) {
                    outputEnded = true;
                  }
                  endOutputFuture.complete(null);
                },
                err -> {
                  endOutputFuture.completeExceptionally(err);
                  failStream(err);
                }));
      }
    }

    @Override
    public void close() {
      CompletableFuture<ByteBuffer> readToCancel = null;
      CompletableFuture<Void> writeToCancel = null;
      Stream s = null;
      synchronized (streamLock) {
        if (streamClosed) {
          return;
        }
        streamClosed = true;
        readToCancel = pendingRead;
        pendingRead = null;
        writeToCancel = pendingWrite;
        pendingWrite = null;
        s = stream;
      }

      CancellationException cancel = new CancellationException("tunnel closed");
      responseHeadersFuture.completeExceptionally(cancel);
      if (readToCancel != null) {
        readToCancel.completeExceptionally(cancel);
      }
      if (writeToCancel != null) {
        writeToCancel.completeExceptionally(cancel);
      }
      if (endOutputFuture != null) {
        endOutputFuture.completeExceptionally(cancel);
      }

      if (s != null) {
        try {
          s.reset(new ResetFrame(s.getId(), ErrorCode.CANCEL_STREAM_ERROR.code), Callback.NOOP);
        } catch (Throwable ignored) {
        }
      }
      removeActive();
    }
  }
}
