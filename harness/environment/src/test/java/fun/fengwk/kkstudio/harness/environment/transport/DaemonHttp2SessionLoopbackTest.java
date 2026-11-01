package fun.fengwk.kkstudio.harness.environment.transport;

import static org.eclipse.jetty.util.Callback.NOOP;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http2.ErrorCode;
import org.eclipse.jetty.http2.api.Session;
import org.eclipse.jetty.http2.api.Stream;
import org.eclipse.jetty.http2.api.server.ServerSessionListener;
import org.eclipse.jetty.http2.frames.DataFrame;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.http2.server.RawHTTP2ServerConnectionFactory;
import org.eclipse.jetty.io.ArrayByteBufferPool;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.thread.ScheduledExecutorScheduler;
import org.eclipse.jetty.util.thread.Scheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.environment.protocol.Cancel;
import fun.fengwk.kkstudio.harness.environment.protocol.ControlProtocolCodec;
import fun.fengwk.kkstudio.harness.environment.protocol.Envelope;
import fun.fengwk.kkstudio.harness.environment.protocol.MessageType;
import fun.fengwk.kkstudio.harness.environment.server.DaemonAdmission;
import fun.fengwk.kkstudio.harness.environment.server.DaemonControlChannel;
import fun.fengwk.kkstudio.harness.environment.server.DaemonHandshake;
import fun.fengwk.kkstudio.harness.environment.server.DaemonOfferResult;
import fun.fengwk.kkstudio.harness.environment.server.DaemonSession;
import fun.fengwk.kkstudio.harness.environment.server.DaemonSessions;
import fun.fengwk.kkstudio.harness.environment.tunnel.EnvironmentTunnel;
import fun.fengwk.kkstudio.harness.environment.tunnel.TunnelExchange;
import fun.fengwk.kkstudio.harness.environment.tunnel.TunnelHttpRequest;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

class DaemonHttp2SessionLoopbackTest {

  private static final DaemonAdmission.Admitted ADMITTED =
      new DaemonAdmission.Admitted(
          EnvironmentId.parse("3f2a9c1e-7b4d-4e2a-9c6f-1a2b3c4d5e6f"), "inst-loopback");

  private ScheduledExecutorScheduler scheduler;
  private ExecutorService executor;
  private ByteBufferPool bufferPool;
  private Server server;
  private ServerConnector connector;

  @BeforeEach
  void setUp() throws Exception {
    scheduler = new ScheduledExecutorScheduler("loopback-scheduler-", false);
    scheduler.start();
    executor = Executors.newCachedThreadPool();
    bufferPool = new ArrayByteBufferPool();
    server = new Server();
    connector = new ServerConnector(server);
    server.addConnector(connector);
    server.start();
  }

  @AfterEach
  void tearDown() throws Exception {
    if (server != null) {
      server.stop();
    }
    executor.shutdownNow();
    if (scheduler != null) {
      scheduler.stop();
    }
  }

  @Test
  void fullSessionLifecycleHandshakeSendEnvelopeAndGoAwayDrainsTunnel() throws Exception {
    CompletableFuture<Session> serverSessionFuture = new CompletableFuture<>();

    ServerSessionListener listener =
        new ServerSessionListener() {
          @Override
          public void onAccept(Session session) {
            serverSessionFuture.complete(session);
          }

          @Override
          public Stream.Listener onNewStream(Stream stream, HeadersFrame frame) {
            MetaData meta = frame.getMetaData();
            if (meta instanceof MetaData.Request req
                && "/control".equals(req.getHttpURI().getPath())) {
              MetaData.Response response =
                  new MetaData.Response(
                      HttpStatus.OK_200, "OK", HttpVersion.HTTP_2, HttpFields.EMPTY);
              stream.headers(new HeadersFrame(stream.getId(), response, null, false), NOOP);
              byte[] ready = loadReadyFrame(ADMITTED.environmentId().value().toString());
              stream.data(new DataFrame(stream.getId(), ByteBuffer.wrap(ready), false), NOOP);
              return new Stream.Listener() {};
            }
            return new Stream.Listener() {};
          }
        };

    LoopbackFixture fixture = startFixture(listener, scheduler);

    TestSessions.OpenResult openResult = fixture.sessions.openFuture.get(5, TimeUnit.SECONDS);
    assertNotNull(openResult);
    assertEquals(ADMITTED, openResult.admitted);

    DaemonSession daemonSession =
        fixture.clientSession.whenSessionEstablished().get(5, TimeUnit.SECONDS);
    assertNotNull(daemonSession);

    // 发送 control envelope
    Envelope cancelEnv =
        Envelope.newBuilder()
            .setMessageType(MessageType.CANCEL)
            .setEnvironmentId(ADMITTED.environmentId().value().toString())
            .setInvocationId("inv-loopback")
            .setCancel(Cancel.getDefaultInstance())
            .build();
    assertEquals(DaemonOfferResult.ACCEPTED, openResult.control.offer(cancelEnv));

    // 服务端发送 GOAWAY，验证客户端隧道进入 drain 状态并拒绝新建流
    Session sSession = serverSessionFuture.get(5, TimeUnit.SECONDS);
    sSession.close(ErrorCode.NO_ERROR.code, "loopback-drain", NOOP);

    // 稍候片刻等待帧在内存管道中处理
    Thread.sleep(100);
    assertThrows(
        IllegalStateException.class,
        () ->
            fixture
                .clientSession
                .tunnel()
                .openHttp(new TunnelHttpRequest("GET", "/drain-test", Map.of())));

    fixture.clientSession.close();
    assertTrue(fixture.clientSession.isClosed());
  }

  @Test
  void pingAckTimeoutFailsSession() throws Exception {
    ControlledScheduler controlledScheduler = new ControlledScheduler();

    ServerSessionListener listener =
        new ServerSessionListener() {
          @Override
          public void onAccept(Session session) {}

          @Override
          public Stream.Listener onNewStream(Stream stream, HeadersFrame frame) {
            MetaData meta = frame.getMetaData();
            if (meta instanceof MetaData.Request req
                && "/control".equals(req.getHttpURI().getPath())) {
              MetaData.Response response =
                  new MetaData.Response(
                      HttpStatus.OK_200, "OK", HttpVersion.HTTP_2, HttpFields.EMPTY);
              stream.headers(new HeadersFrame(stream.getId(), response, null, false), NOOP);
              byte[] ready = loadReadyFrame(ADMITTED.environmentId().value().toString());
              stream.data(new DataFrame(stream.getId(), ByteBuffer.wrap(ready), false), NOOP);
              return new Stream.Listener() {};
            }
            return new Stream.Listener() {};
          }
        };

    LoopbackFixture fixture = startFixture(listener, controlledScheduler);

    // 等待握手完成并建立会话
    fixture.sessions.openFuture.get(5, TimeUnit.SECONDS);
    assertNotNull(fixture.clientSession.whenSessionEstablished().get(5, TimeUnit.SECONDS));

    // 触发 15 秒 PING 定时任务，该任务发送 PING 帧并调度 30 秒 ACK 超时任务
    controlledScheduler.runScheduledTasks();

    // 触发 30 秒 ACK 超时任务（未收到 PING 响应）
    controlledScheduler.runScheduledTasks();

    // 验证连接因 PING 超时被关闭
    assertTrue(fixture.clientSession.isClosed());
  }

  @Test
  void webSocketCloseFailsAllStreamsWithIOException() throws Exception {
    ServerSessionListener listener =
        new ServerSessionListener() {
          @Override
          public void onAccept(Session session) {}

          @Override
          public Stream.Listener onNewStream(Stream stream, HeadersFrame frame) {
            MetaData meta = frame.getMetaData();
            if (meta instanceof MetaData.Request req) {
              if ("/control".equals(req.getHttpURI().getPath())) {
                MetaData.Response response =
                    new MetaData.Response(
                        HttpStatus.OK_200, "OK", HttpVersion.HTTP_2, HttpFields.EMPTY);
                stream.headers(new HeadersFrame(stream.getId(), response, null, false), NOOP);
                byte[] ready = loadReadyFrame(ADMITTED.environmentId().value().toString());
                stream.data(new DataFrame(stream.getId(), ByteBuffer.wrap(ready), false), NOOP);
                return new Stream.Listener() {};
              } else if ("/long-poll".equals(req.getHttpURI().getPath())) {
                MetaData.Response response =
                    new MetaData.Response(
                        HttpStatus.OK_200, "OK", HttpVersion.HTTP_2, HttpFields.EMPTY);
                stream.headers(new HeadersFrame(stream.getId(), response, null, false), NOOP);
                return new Stream.Listener() {};
              }
            }
            return new Stream.Listener() {};
          }
        };

    LoopbackFixture fixture = startFixture(listener, scheduler);

    fixture.sessions.openFuture.get(5, TimeUnit.SECONDS);
    fixture.clientSession.whenSessionEstablished().get(5, TimeUnit.SECONDS);

    // 打开一个流并读取
    TunnelExchange exchange =
        fixture
            .clientSession
            .tunnel()
            .openHttp(new TunnelHttpRequest("GET", "/long-poll", Map.of()));
    CompletableFuture<ByteBuffer> readFuture = exchange.stream().read().toCompletableFuture();

    // 模拟底层 WebSocket 发生异常中断
    fixture.pair.clientPipe.fail(new IOException("simulated network breakdown"));

    ExecutionException ex =
        assertThrows(ExecutionException.class, () -> readFuture.get(5, TimeUnit.SECONDS));
    assertTrue(ex.getCause() instanceof IOException);
    assertTrue(fixture.clientSession.isClosed());
  }

  @Test
  void controlStreamNon200StatusFailsSession() {
    ServerSessionListener listener =
        new ServerSessionListener() {
          @Override
          public void onAccept(Session session) {}

          @Override
          public Stream.Listener onNewStream(Stream stream, HeadersFrame frame) {
            MetaData meta = frame.getMetaData();
            if (meta instanceof MetaData.Request req
                && "/control".equals(req.getHttpURI().getPath())) {
              MetaData.Response response =
                  new MetaData.Response(
                      HttpStatus.INTERNAL_SERVER_ERROR_500,
                      "Internal Error",
                      HttpVersion.HTTP_2,
                      HttpFields.EMPTY);
              stream.headers(new HeadersFrame(stream.getId(), response, null, true), NOOP);
              return new Stream.Listener() {};
            }
            return new Stream.Listener() {};
          }
        };

    LoopbackFixture fixture = startFixture(listener, scheduler);

    assertThrows(
        ExecutionException.class,
        () -> fixture.clientSession.whenSessionEstablished().get(5, TimeUnit.SECONDS));
    assertTrue(fixture.clientSession.isClosed());
  }

  @Test
  void sessionsOpenThrowsExceptionFailsSession() {
    ServerSessionListener listener =
        new ServerSessionListener() {
          @Override
          public void onAccept(Session session) {}

          @Override
          public Stream.Listener onNewStream(Stream stream, HeadersFrame frame) {
            MetaData meta = frame.getMetaData();
            if (meta instanceof MetaData.Request req
                && "/control".equals(req.getHttpURI().getPath())) {
              MetaData.Response response =
                  new MetaData.Response(
                      HttpStatus.OK_200, "OK", HttpVersion.HTTP_2, HttpFields.EMPTY);
              stream.headers(new HeadersFrame(stream.getId(), response, null, false), NOOP);
              byte[] ready = loadReadyFrame(ADMITTED.environmentId().value().toString());
              stream.data(new DataFrame(stream.getId(), ByteBuffer.wrap(ready), false), NOOP);
              return new Stream.Listener() {};
            }
            return new Stream.Listener() {};
          }
        };

    TestSessions faultySessions =
        new TestSessions() {
          @Override
          public DaemonSession open(
              DaemonAdmission.Admitted admitted,
              DaemonControlChannel control,
              EnvironmentTunnel tunnel) {
            throw new IllegalStateException("simulated failure opening core session");
          }
        };

    LoopbackPair pair = new LoopbackPair(scheduler, executor);
    HttpConfiguration httpConfig = new HttpConfiguration();
    RawHTTP2ServerConnectionFactory h2Factory =
        new RawHTTP2ServerConnectionFactory(httpConfig, listener);

    Connection serverConn = h2Factory.newConnection(connector, pair.serverEndPoint);
    pair.serverEndPoint.setConnection(serverConn);
    pair.serverEndPoint.onOpen();
    serverConn.onOpen();

    DaemonHttp2ClientSession clientSession =
        new DaemonHttp2ClientSession(
            ADMITTED, pair.clientPipe, scheduler, executor, bufferPool, faultySessions);

    pair.clientPipe.attachEndPoint(clientSession.endPoint());
    pair.serverPipe.link(clientSession.endPoint(), pair.serverEndPoint);
    clientSession.start();

    assertThrows(
        ExecutionException.class,
        () -> clientSession.whenSessionEstablished().get(5, TimeUnit.SECONDS));
    // 建立结果失败时连接已关闭，准入分配也已释放。
    assertTrue(clientSession.isClosed());
    assertEquals(List.of(ADMITTED), faultySessions.abandoned);
  }

  private LoopbackFixture startFixture(
      ServerSessionListener serverListener, Scheduler fixtureScheduler) {
    TestSessions sessions = new TestSessions();
    LoopbackPair pair = new LoopbackPair(fixtureScheduler, executor);

    HttpConfiguration httpConfig = new HttpConfiguration();
    RawHTTP2ServerConnectionFactory h2Factory =
        new RawHTTP2ServerConnectionFactory(httpConfig, serverListener);

    Connection serverConn = h2Factory.newConnection(connector, pair.serverEndPoint);
    pair.serverEndPoint.setConnection(serverConn);
    pair.serverEndPoint.onOpen();
    serverConn.onOpen();

    DaemonHttp2ClientSession clientSession =
        new DaemonHttp2ClientSession(
            ADMITTED, pair.clientPipe, fixtureScheduler, executor, bufferPool, sessions);

    pair.clientPipe.attachEndPoint(clientSession.endPoint());
    pair.serverPipe.link(clientSession.endPoint(), pair.serverEndPoint);
    clientSession.start();

    return new LoopbackFixture(pair, clientSession, sessions, serverConn);
  }

  private static byte[] loadReadyFrame(String environmentId) {
    Path p = Path.of("src/main/protocol/golden/ready.bin");
    try {
      Envelope golden = ControlProtocolCodec.decodeBinary(Files.readAllBytes(p));
      Envelope ready = golden.toBuilder().setEnvironmentId(environmentId).build();
      return ControlProtocolCodec.encodeFrame(ready);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  private record LoopbackFixture(
      LoopbackPair pair,
      DaemonHttp2ClientSession clientSession,
      TestSessions sessions,
      Connection serverConn) {}

  private static final class LoopbackPair {
    final DaemonLoopbackPipe clientPipe;
    final DaemonLoopbackPipe serverPipe;
    final WebSocketEndPoint serverEndPoint;

    LoopbackPair(Scheduler scheduler, ExecutorService executor) {
      this.clientPipe = new DaemonLoopbackPipe(executor);
      this.serverPipe = new DaemonLoopbackPipe(executor);
      this.serverEndPoint = new WebSocketEndPoint(scheduler, serverPipe);
      this.clientPipe.link(serverEndPoint, null);
      this.serverPipe.link(null, serverEndPoint);
    }
  }

  private static final class ControlledScheduler extends ScheduledExecutorScheduler {
    private final List<Runnable> tasks = new ArrayList<>();

    @Override
    public Task schedule(Runnable task, long delay, TimeUnit unit) {
      synchronized (tasks) {
        tasks.add(task);
      }
      return () -> {
        synchronized (tasks) {
          return tasks.remove(task);
        }
      };
    }

    void runScheduledTasks() {
      List<Runnable> copy;
      synchronized (tasks) {
        copy = new ArrayList<>(tasks);
        tasks.clear();
      }
      for (Runnable r : copy) {
        r.run();
      }
    }
  }

  private static class TestSessions implements DaemonSessions {
    final CompletableFuture<OpenResult> openFuture = new CompletableFuture<>();
    final List<DaemonAdmission.Admitted> abandoned = new ArrayList<>();

    record OpenResult(
        DaemonAdmission.Admitted admitted,
        DaemonControlChannel control,
        EnvironmentTunnel tunnel) {}

    @Override
    public DaemonAdmission admit(DaemonHandshake handshake) {
      return ADMITTED;
    }

    @Override
    public void abandon(DaemonAdmission.Admitted admitted) {
      abandoned.add(admitted);
    }

    @Override
    public DaemonSession open(
        DaemonAdmission.Admitted admitted, DaemonControlChannel control, EnvironmentTunnel tunnel) {
      openFuture.complete(new OpenResult(admitted, control, tunnel));
      return new DaemonSession() {
        @Override
        public void receive(Envelope envelope) {}

        @Override
        public void close() {}

        @Override
        public void closed() {}
      };
    }
  }
}
