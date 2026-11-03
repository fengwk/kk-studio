package fun.fengwk.kkstudio.platform.daemon.transport;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http2.ErrorCode;
import org.eclipse.jetty.http2.HTTP2Connection;
import org.eclipse.jetty.http2.HTTP2Session;
import org.eclipse.jetty.http2.SimpleFlowControlStrategy;
import org.eclipse.jetty.http2.api.Session;
import org.eclipse.jetty.http2.api.Stream;
import org.eclipse.jetty.http2.client.HTTP2Client;
import org.eclipse.jetty.http2.client.HTTP2ClientConnectionFactory;
import org.eclipse.jetty.http2.frames.GoAwayFrame;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.http2.frames.PingFrame;
import org.eclipse.jetty.http2.frames.ResetFrame;
import org.eclipse.jetty.http2.frames.SettingsFrame;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.thread.Scheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import fun.fengwk.kkstudio.platform.daemon.server.DaemonAdmission;
import fun.fengwk.kkstudio.platform.daemon.server.DaemonConnectionIdentity;
import fun.fengwk.kkstudio.platform.daemon.server.DaemonSession;
import fun.fengwk.kkstudio.platform.daemon.server.DaemonSessions;

import java.io.IOException;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 承载单条 Daemon WebSocket 连接上的 HTTP/2 客户端会话。
 *
 * <p>按 X-DESIGN §2 配置：4 MiB 会话窗口、1 MiB 流窗口、32 KiB 响应头大小限制、15s PING / 30s ACK 超时、10s 控制流开启倒计时与
 * abandon 清理；提供 {@link #drain()} 发送 NO_ERROR GOAWAY 并通知隧道排空；连接关闭时使全部子流以 {@link IOException} 结束。
 */
public class DaemonHttp2ClientSession implements AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(DaemonHttp2ClientSession.class);

  public static final int INITIAL_SESSION_WINDOW = 4 * 1024 * 1024;
  public static final int INITIAL_STREAM_WINDOW = 1024 * 1024;
  public static final int MAX_RESPONSE_HEADERS_SIZE = 32 * 1024;
  public static final Duration CONTROL_STREAM_DEADLINE = Duration.ofSeconds(10);
  public static final Duration PING_INTERVAL = Duration.ofSeconds(15);
  public static final Duration PING_ACK_TIMEOUT = Duration.ofSeconds(30);

  private final DaemonAdmission.Admitted admitted;
  private final DaemonConnectionIdentity identity;
  private final WebSocketPipe pipe;
  private final Scheduler scheduler;
  private final Executor executor;
  private final ByteBufferPool bufferPool;
  private final DaemonSessions sessions;

  private final WebSocketEndPoint endPoint;
  private final HTTP2Connection connection;
  private final HTTP2Session session;
  private final DaemonEnvironmentTunnel tunnel;

  private final CompletableFuture<DaemonSession> sessionEstablished = new CompletableFuture<>();
  private final AtomicBoolean sessionEstablishedMarker = new AtomicBoolean(false);
  private final AtomicBoolean abandonMarker = new AtomicBoolean(false);
  private final AtomicBoolean closed = new AtomicBoolean(false);

  private volatile Scheduler.Task abandonTimer;
  private volatile Scheduler.Task pingTask;
  private volatile Scheduler.Task ackTimeoutTask;
  private volatile DaemonControlStream controlStream;
  private volatile DaemonSession daemonSession;

  public DaemonHttp2ClientSession(
      DaemonAdmission.Admitted admitted,
      WebSocketPipe pipe,
      Scheduler scheduler,
      Executor executor,
      ByteBufferPool bufferPool,
      DaemonSessions sessions) {
    this.admitted = Objects.requireNonNull(admitted, "admitted must not be null");
    this.pipe = Objects.requireNonNull(pipe, "pipe must not be null");
    this.scheduler = Objects.requireNonNull(scheduler, "scheduler must not be null");
    this.executor = Objects.requireNonNull(executor, "executor must not be null");
    this.bufferPool = Objects.requireNonNull(bufferPool, "bufferPool must not be null");
    this.sessions = Objects.requireNonNull(sessions, "sessions must not be null");

    this.identity =
        new DaemonConnectionIdentity(admitted.environmentId(), admitted.daemonInstanceId());
    this.endPoint = new WebSocketEndPoint(scheduler, pipe);

    HTTP2Client client = new HTTP2Client();
    client.setExecutor(executor);
    client.setScheduler(scheduler);
    client.setByteBufferPool(bufferPool);
    client.setInitialSessionRecvWindow(INITIAL_SESSION_WINDOW);
    client.setInitialStreamRecvWindow(INITIAL_STREAM_WINDOW);
    client.setMaxResponseHeadersSize(MAX_RESPONSE_HEADERS_SIZE);
    client.setFlowControlStrategyFactory(
        () -> new SimpleFlowControlStrategy(INITIAL_STREAM_WINDOW));

    Session.Listener sessionListener = new ClientSessionListener();
    Map<String, Object> context = new HashMap<>();
    context.put(HTTP2Client.CONTEXT_KEY, client);
    context.put(HTTP2Client.SESSION_LISTENER_CONTEXT_KEY, sessionListener);
    context.put(
        HTTP2Client.SESSION_PROMISE_CONTEXT_KEY,
        new Promise<Session>() {
          @Override
          public void succeeded(Session result) {
            onSessionReady(result);
          }

          @Override
          public void failed(Throwable failure) {
            onSessionFailed(failure);
          }
        });

    this.connection =
        (HTTP2Connection) new HTTP2ClientConnectionFactory().newConnection(endPoint, context);
    this.session = connection.getSession();
    this.tunnel = new DaemonEnvironmentTunnel(identity, session, this::isClosed);
  }

  public DaemonConnectionIdentity identity() {
    return identity;
  }

  public WebSocketEndPoint endPoint() {
    return endPoint;
  }

  public DaemonEnvironmentTunnel tunnel() {
    return tunnel;
  }

  public CompletableFuture<DaemonSession> whenSessionEstablished() {
    return sessionEstablished;
  }

  public boolean isClosed() {
    return closed.get() || session.isClosed();
  }

  /** 启动连接、端点与 10 秒建连倒计时。 */
  public void start() {
    startAbandonTimer();
    executor.execute(
        () -> {
          try {
            endPoint.setConnection(connection);
            endPoint.onOpen();
            session.start();
            connection.onOpen();
          } catch (Exception error) {
            log.error("Failed to start HTTP/2 client session for {}", identity, error);
            onSessionFailed(error);
          }
        });
  }

  private void startAbandonTimer() {
    abandonTimer =
        scheduler.schedule(
            () -> {
              if (!sessionEstablishedMarker.get()) {
                log.warn(
                    "Daemon control stream handshake timed out after 10s for {}, abandoning",
                    identity);
                doAbandon();
                close();
              }
            },
            CONTROL_STREAM_DEADLINE.toMillis(),
            TimeUnit.MILLISECONDS);
  }

  private void cancelAbandonTimer() {
    Scheduler.Task timer = abandonTimer;
    if (timer != null) {
      timer.cancel();
      abandonTimer = null;
    }
  }

  private void doAbandon() {
    if (abandonMarker.compareAndSet(false, true)) {
      sessions.abandon(admitted);
    }
  }

  private void onSessionReady(Session h2Session) {
    schedulePing();
    openControlStream(h2Session);
  }

  private void openControlStream(Session h2Session) {
    HttpFields.Mutable fields = HttpFields.build();
    fields.add("content-type", "application/octet-stream");
    MetaData.Request meta =
        new MetaData.Request(
            "POST", HttpURI.from("https://daemon/control"), HttpVersion.HTTP_2, fields);

    h2Session.newStream(
        new HeadersFrame(meta, null, false),
        new Promise<>() {
          @Override
          public void succeeded(Stream stream) {
            DaemonControlStream ctrl =
                new DaemonControlStream(
                    identity,
                    stream,
                    DaemonHttp2ClientSession.this::close,
                    DaemonHttp2ClientSession.this::drain,
                    scheduler);
            controlStream = ctrl;
          }

          @Override
          public void failed(Throwable failure) {
            log.error("Failed to open POST /control stream for {}", identity, failure);
            onSessionFailed(failure);
          }
        },
        new Stream.Listener() {
          @Override
          public void onHeaders(Stream source, HeadersFrame frame) {
            if (frame.getMetaData() instanceof MetaData.Response response) {
              if (response.getStatus() == 200) {
                onControlStreamHeaders200();
              } else {
                log.warn(
                    "POST /control returned non-200 status {} for {}",
                    response.getStatus(),
                    identity);
                onSessionFailed(new IOException("POST /control status " + response.getStatus()));
              }
            }
          }

          @Override
          public void onDataAvailable(Stream source) {
            DaemonControlStream ctrl = controlStream;
            if (ctrl != null) {
              ctrl.listener().onDataAvailable(source);
            }
          }

          @Override
          public void onReset(Stream source, ResetFrame frame, Callback callback) {
            DaemonControlStream ctrl = controlStream;
            if (ctrl != null) {
              ctrl.listener().onReset(source, frame, callback);
            } else {
              callback.succeeded();
            }
          }

          @Override
          public void onFailure(
              Stream source, int error, String reason, Throwable failure, Callback callback) {
            DaemonControlStream ctrl = controlStream;
            if (ctrl != null) {
              ctrl.listener().onFailure(source, error, reason, failure, callback);
            } else {
              callback.succeeded();
            }
          }
        });
  }

  private void onControlStreamHeaders200() {
    if (!sessionEstablishedMarker.compareAndSet(false, true)) {
      return;
    }
    cancelAbandonTimer();
    DaemonSession opened;
    try {
      opened = sessions.open(admitted, controlStream, tunnel);
    } catch (RuntimeException error) {
      // 核心未建立会话：先释放准入并关闭连接，建立结果失败时调用方看到的已是关闭状态。
      log.error("Failed to open daemon session in core for {}", identity, error);
      doAbandon();
      onSessionFailed(error);
      sessionEstablished.completeExceptionally(error);
      return;
    }
    daemonSession = opened;
    controlStream.bindSession(opened);
    sessionEstablished.complete(opened);
  }

  /** 发送 NO_ERROR 的 GOAWAY，让隧道拒绝新建流并优雅排空控制流。 */
  public void drain() {
    tunnel.drain();
    try {
      session.goAway(
          new GoAwayFrame(session.getStreams().size() * 2 + 1, ErrorCode.NO_ERROR.code, null),
          Callback.NOOP);
    } catch (RuntimeException ex) {
      log.warn("Failed to send GOAWAY to daemon {}", identity, ex);
    }
    DaemonControlStream ctrl = controlStream;
    if (ctrl != null) {
      ctrl.closeAfterFlush();
    }
  }

  private void schedulePing() {
    if (isClosed()) {
      return;
    }
    pingTask =
        scheduler.schedule(
            () -> {
              if (isClosed()) {
                return;
              }
              sendPing();
            },
            PING_INTERVAL.toMillis(),
            TimeUnit.MILLISECONDS);
  }

  private void sendPing() {
    long pingVal = System.currentTimeMillis();
    ackTimeoutTask =
        scheduler.schedule(
            () -> {
              log.warn("Daemon HTTP/2 session {} ping ACK timed out after 30s", identity);
              onSessionFailed(new TimeoutException("PING ACK timeout after 30s"));
            },
            PING_ACK_TIMEOUT.toMillis(),
            TimeUnit.MILLISECONDS);

    session.ping(
        new PingFrame(pingVal, false),
        Callback.from(
            () -> {},
            error -> {
              log.warn("Failed to send PING frame to daemon {}", identity, error);
            }));
  }

  private void onPingReply() {
    Scheduler.Task task = ackTimeoutTask;
    if (task != null) {
      task.cancel();
      ackTimeoutTask = null;
    }
    schedulePing();
  }

  private void onSessionFailed(Throwable failure) {
    if (!closed.compareAndSet(false, true)) {
      return;
    }
    cancelTimers();
    if (!sessionEstablishedMarker.get()) {
      doAbandon();
      sessionEstablished.completeExceptionally(failure);
    }
    DaemonSession ds = daemonSession;
    if (ds != null) {
      ds.closed();
    }
    tunnel.onSessionClosed(failure);
    DaemonControlStream ctrl = controlStream;
    if (ctrl != null) {
      ctrl.close();
    }
    endPoint.close();
  }

  private void cancelTimers() {
    cancelAbandonTimer();
    Scheduler.Task ping = pingTask;
    if (ping != null) {
      ping.cancel();
      pingTask = null;
    }
    Scheduler.Task ack = ackTimeoutTask;
    if (ack != null) {
      ack.cancel();
      ackTimeoutTask = null;
    }
  }

  @Override
  public void close() {
    if (!closed.compareAndSet(false, true)) {
      return;
    }
    cancelTimers();
    if (!sessionEstablishedMarker.get()) {
      doAbandon();
      sessionEstablished.completeExceptionally(new IOException("session closed before handshake"));
    }
    try {
      session.close(ErrorCode.NO_ERROR.code, "session closed", Callback.NOOP);
    } catch (RuntimeException ex) {
      log.warn("Exception closing HTTP/2 session for {}", identity, ex);
    }
    DaemonSession ds = daemonSession;
    if (ds != null) {
      ds.closed();
    }
    tunnel.onSessionClosed(new IOException("session closed"));
    DaemonControlStream ctrl = controlStream;
    if (ctrl != null) {
      ctrl.close();
    }
    endPoint.close();
  }

  private class ClientSessionListener implements Session.Listener {
    @Override
    public Map<Integer, Integer> onPreface(Session source) {
      return Map.of(
          SettingsFrame.INITIAL_WINDOW_SIZE, INITIAL_STREAM_WINDOW, SettingsFrame.ENABLE_PUSH, 0);
    }

    @Override
    public void onPing(Session source, PingFrame frame) {
      if (frame.isReply()) {
        onPingReply();
      }
    }

    @Override
    public void onGoAway(Session source, GoAwayFrame frame) {
      log.info(
          "Received GOAWAY from daemon {}, lastStreamId={}, error={}",
          identity,
          frame.getLastStreamId(),
          frame.getError());
      tunnel.drain();
      DaemonControlStream ctrl = controlStream;
      if (ctrl != null) {
        ctrl.closeAfterFlush();
      }
    }

    @Override
    public void onClose(Session source, GoAwayFrame frame, Callback callback) {
      onSessionFailed(new IOException("session closed by peer"));
      callback.succeeded();
    }

    @Override
    public void onFailure(Session source, Throwable failure, Callback callback) {
      onSessionFailed(failure);
      callback.succeeded();
    }
  }
}
