package fun.fengwk.kkstudio.platform.daemon.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http2.api.Session;
import org.eclipse.jetty.http2.api.Stream;
import org.eclipse.jetty.http2.frames.DataFrame;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.http2.frames.PingFrame;
import org.eclipse.jetty.http2.frames.PriorityFrame;
import org.eclipse.jetty.http2.frames.PushPromiseFrame;
import org.eclipse.jetty.http2.frames.ResetFrame;
import org.eclipse.jetty.http2.frames.SettingsFrame;
import org.eclipse.jetty.io.ArrayByteBufferPool;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.thread.ScheduledExecutorScheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.platform.daemon.protocol.Cancel;
import fun.fengwk.kkstudio.platform.daemon.protocol.ControlProtocolCodec;
import fun.fengwk.kkstudio.platform.daemon.protocol.Envelope;
import fun.fengwk.kkstudio.platform.daemon.protocol.MessageType;
import fun.fengwk.kkstudio.platform.daemon.protocol.ResourceBudgets;
import fun.fengwk.kkstudio.platform.daemon.protocol.Settings;
import fun.fengwk.kkstudio.platform.daemon.server.DaemonAdmission;
import fun.fengwk.kkstudio.platform.daemon.server.DaemonControlChannel;
import fun.fengwk.kkstudio.platform.daemon.server.DaemonHandshake;
import fun.fengwk.kkstudio.platform.daemon.server.DaemonOfferResult;
import fun.fengwk.kkstudio.platform.daemon.server.DaemonSession;
import fun.fengwk.kkstudio.platform.daemon.server.DaemonSessions;
import fun.fengwk.kkstudio.platform.daemon.tunnel.EnvironmentTunnel;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 控制流初始化的回调顺序回归。
 *
 * <p>Jetty 的响应头回调由解析线程触发，{@code newStream} 的 promise 在请求 HEADERS 写出后完成，二者没有硬性先后。控制流必须由先到者用自己持有的
 * {@link Stream} 同步初始化，且迟到的回调不得替换已绑定的控制流。
 */
class DaemonHttp2ControlStreamCallbackOrderTest {

  private static final DaemonAdmission.Admitted ADMITTED =
      new DaemonAdmission.Admitted(
          EnvironmentId.parse("3f2a9c1e-7b4d-4e2a-9c6f-1a2b3c4d5e6f"), "inst-callback-order");

  private TestScheduler scheduler;
  private ExecutorService executor;
  private DaemonLoopbackPipe pipe;
  private TestSessions sessions;

  @BeforeEach
  void setUp() {
    scheduler = new TestScheduler();
    executor = Executors.newSingleThreadExecutor();
    pipe = new DaemonLoopbackPipe(executor);
    sessions = new TestSessions();
  }

  @AfterEach
  void tearDown() {
    executor.shutdownNow();
  }

  @Test
  void headersBeforePromiseInitializeTheBoundControlStream() {
    DaemonHttp2ClientSession clientSession = newClientSession();
    TestSession h2Session = new TestSession();
    TestStream stream = new TestStream();

    clientSession.openControlStream(h2Session);
    h2Session.listener.onHeaders(stream, okResponse());

    // 响应头先到时核心必须收到非 null 控制通道，并能立即递交 SETTINGS
    assertEquals(1, sessions.opened.size());
    DaemonControlChannel control = sessions.opened.get(0).control();
    assertNotNull(control);
    assertEquals(DaemonOfferResult.ACCEPTED, control.offer(settingsEnvelope()));
    assertEquals(1, stream.sentFrames.size());
    assertEquals(1, stream.demandCount());

    // 迟到的 promise 不得替换已绑定控制流：入站仍到 headers 时机绑定的同一个核心会话
    h2Session.promise.succeeded(stream);
    stream.enqueueInbound(ControlProtocolCodec.encodeFrame(cancelEnvelope()));
    h2Session.listener.onDataAvailable(stream);

    assertEquals(1, sessions.opened.get(0).session().received.size());
    assertEquals(2, stream.demandCount());
  }

  @Test
  void promiseBeforeHeadersBindsTheSameControlStream() {
    DaemonHttp2ClientSession clientSession = newClientSession();
    TestSession h2Session = new TestSession();
    TestStream stream = new TestStream();

    clientSession.openControlStream(h2Session);
    h2Session.promise.succeeded(stream);
    h2Session.listener.onHeaders(stream, okResponse());

    assertEquals(1, sessions.opened.size());
    DaemonControlChannel control = sessions.opened.get(0).control();
    assertNotNull(control);
    assertEquals(DaemonOfferResult.ACCEPTED, control.offer(settingsEnvelope()));
    assertEquals(1, stream.sentFrames.size());
    assertEquals(1, stream.demandCount());

    stream.enqueueInbound(ControlProtocolCodec.encodeFrame(cancelEnvelope()));
    h2Session.listener.onDataAvailable(stream);

    assertEquals(1, sessions.opened.get(0).session().received.size());
    assertEquals(2, stream.demandCount());
  }

  private DaemonHttp2ClientSession newClientSession() {
    return new DaemonHttp2ClientSession(
        ADMITTED, pipe, scheduler, executor, new ArrayByteBufferPool(), sessions);
  }

  private static HeadersFrame okResponse() {
    MetaData.Response response =
        new MetaData.Response(HttpStatus.OK_200, "OK", HttpVersion.HTTP_2, HttpFields.EMPTY);
    return new HeadersFrame(1, response, null, false);
  }

  private static Envelope settingsEnvelope() {
    return Envelope.newBuilder()
        .setMessageType(MessageType.SETTINGS)
        .setEnvironmentId(ADMITTED.environmentId().value().toString())
        .setSettings(
            Settings.newBuilder()
                .setResourceBudgets(
                    ResourceBudgets.newBuilder()
                        .setTemporaryResourceTtlSeconds(600)
                        .setTemporaryResourceCleanupIntervalSeconds(60)))
        .build();
  }

  private static Envelope cancelEnvelope() {
    return Envelope.newBuilder()
        .setMessageType(MessageType.CANCEL)
        .setEnvironmentId(ADMITTED.environmentId().value().toString())
        .setInvocationId("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")
        .setCancel(Cancel.getDefaultInstance())
        .build();
  }

  /** 捕获 newStream 的 promise 与 listener，让测试决定回调顺序。 */
  private static final class TestSession implements Session {
    private Promise<Stream> promise;
    private Stream.Listener listener;

    @Override
    public void newStream(HeadersFrame frame, Promise<Stream> promise, Stream.Listener listener) {
      this.promise = promise;
      this.listener = listener;
    }

    @Override
    public int priority(PriorityFrame frame, Callback callback) {
      return 0;
    }

    @Override
    public void settings(SettingsFrame frame, Callback callback) {}

    @Override
    public void ping(PingFrame frame, Callback callback) {}

    @Override
    public boolean close(int error, String reason, Callback callback) {
      return true;
    }

    @Override
    public boolean isClosed() {
      return false;
    }

    @Override
    public boolean isPushEnabled() {
      return false;
    }

    @Override
    public Collection<Stream> getStreams() {
      return List.of();
    }

    @Override
    public Stream getStream(int streamId) {
      return null;
    }

    @Override
    public InetSocketAddress getLocalAddress() {
      return null;
    }

    @Override
    public InetSocketAddress getRemoteAddress() {
      return null;
    }

    @Override
    public CompletableFuture<Void> shutdown() {
      return CompletableFuture.completedFuture(null);
    }
  }

  private static final class TestStream implements Stream {
    private final List<DataFrame> sentFrames = new ArrayList<>();
    private final Queue<Stream.Data> inbound = new ArrayDeque<>();
    private final AtomicInteger demands = new AtomicInteger();

    void enqueueInbound(byte[] frame) {
      inbound.add(new DataFrameData(new DataFrame(1, ByteBuffer.wrap(frame), false)));
    }

    int demandCount() {
      return demands.get();
    }

    @Override
    public int getId() {
      return 1;
    }

    @Override
    public Stream.Data readData() {
      return inbound.poll();
    }

    @Override
    public void demand() {
      demands.incrementAndGet();
    }

    @Override
    public void data(DataFrame frame, Callback callback) {
      sentFrames.add(frame);
      callback.succeeded();
    }

    @Override
    public void reset(ResetFrame frame, Callback callback) {
      callback.succeeded();
    }

    @Override
    public Stream.Listener getListener() {
      return null;
    }

    @Override
    public Session getSession() {
      return null;
    }

    @Override
    public void headers(HeadersFrame frame, Callback callback) {}

    @Override
    public void push(PushPromiseFrame frame, Promise<Stream> promise, Listener listener) {}

    @Override
    public Object getAttribute(String name) {
      return null;
    }

    @Override
    public void setAttribute(String name, Object value) {}

    @Override
    public Object removeAttribute(String name) {
      return null;
    }

    @Override
    public boolean isLocal() {
      return true;
    }

    @Override
    public boolean isReset() {
      return false;
    }

    @Override
    public boolean isRemotelyClosed() {
      return false;
    }

    @Override
    public boolean isClosed() {
      return false;
    }

    @Override
    public long getIdleTimeout() {
      return 0;
    }

    @Override
    public void setIdleTimeout(long idleTimeout) {}
  }

  private static final class DataFrameData extends Stream.Data {
    DataFrameData(DataFrame frame) {
      super(frame);
    }
  }

  private static final class TestDaemonSession implements DaemonSession {
    private final List<Envelope> received = new ArrayList<>();

    @Override
    public void receive(Envelope envelope) {
      received.add(envelope);
    }

    @Override
    public void close() {}

    @Override
    public void closed() {}
  }

  private static final class TestSessions implements DaemonSessions {
    private final List<OpenCall> opened = new ArrayList<>();

    record OpenCall(
        DaemonAdmission.Admitted admitted,
        DaemonControlChannel control,
        TestDaemonSession session) {}

    @Override
    public DaemonAdmission admit(DaemonHandshake handshake) {
      return ADMITTED;
    }

    @Override
    public void abandon(DaemonAdmission.Admitted admitted) {}

    @Override
    public DaemonSession open(
        DaemonAdmission.Admitted admitted, DaemonControlChannel control, EnvironmentTunnel tunnel) {
      TestDaemonSession session = new TestDaemonSession();
      opened.add(new OpenCall(admitted, control, session));
      return session;
    }
  }

  /** 只收集定时任务，不在测试线程上自动执行。 */
  private static final class TestScheduler extends ScheduledExecutorScheduler {
    private final List<Runnable> scheduledTasks = new ArrayList<>();

    @Override
    public Task schedule(Runnable task, long delay, TimeUnit unit) {
      scheduledTasks.add(task);
      return () -> scheduledTasks.remove(task);
    }
  }
}
