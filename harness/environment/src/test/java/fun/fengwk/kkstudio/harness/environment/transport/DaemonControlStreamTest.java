package fun.fengwk.kkstudio.harness.environment.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.eclipse.jetty.http2.api.Session;
import org.eclipse.jetty.http2.api.Stream;
import org.eclipse.jetty.http2.frames.DataFrame;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.http2.frames.PushPromiseFrame;
import org.eclipse.jetty.http2.frames.ResetFrame;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.thread.ScheduledExecutorScheduler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.environment.protocol.Cancel;
import fun.fengwk.kkstudio.harness.environment.protocol.ControlProtocolCodec;
import fun.fengwk.kkstudio.harness.environment.protocol.Envelope;
import fun.fengwk.kkstudio.harness.environment.protocol.MessageType;
import fun.fengwk.kkstudio.harness.environment.server.DaemonConnectionIdentity;
import fun.fengwk.kkstudio.harness.environment.server.DaemonOfferResult;
import fun.fengwk.kkstudio.harness.environment.server.DaemonSession;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

class DaemonControlStreamTest {

  private static final DaemonConnectionIdentity IDENTITY =
      new DaemonConnectionIdentity(
          EnvironmentId.parse("3f2a9c1e-7b4d-4e2a-9c6f-1a2b3c4d5e6f"), "inst-1");

  private TestStream stream;
  private TestScheduler scheduler;
  private AtomicBoolean sessionClosed;
  private TestDaemonSession daemonSession;
  private DaemonControlStream controlStream;

  @BeforeEach
  void setUp() {
    stream = new TestStream();
    scheduler = new TestScheduler();
    sessionClosed = new AtomicBoolean(false);
    daemonSession = new TestDaemonSession();
    controlStream =
        new DaemonControlStream(IDENTITY, stream, () -> sessionClosed.set(true), scheduler);
  }

  private Envelope createCancelEnvelope(String invocationId) {
    return Envelope.newBuilder()
        .setMessageType(MessageType.CANCEL)
        .setEnvironmentId(IDENTITY.environmentId().value().toString())
        .setInvocationId(invocationId)
        .setCancel(Cancel.getDefaultInstance())
        .build();
  }

  @Test
  void doesNotDemandBeforeSessionBound() {
    // 刚构造时未绑定会话，绝不调用 stream.demand
    assertEquals(0, stream.demandCount.get());

    // 绑定会话后，触发首次 demand
    controlStream.bindSession(daemonSession);
    assertEquals(1, stream.demandCount.get());
  }

  @Test
  void incrementallyParsesFragmentedFramesAndDispatchesOutOfLock() {
    controlStream.bindSession(daemonSession);

    Envelope env1 = createCancelEnvelope("inv-1");
    Envelope env2 = createCancelEnvelope("inv-2");

    byte[] frame1 = ControlProtocolCodec.encodeFrame(env1);
    byte[] frame2 = ControlProtocolCodec.encodeFrame(env2);
    byte[] combined = new byte[frame1.length + frame2.length];
    System.arraycopy(frame1, 0, combined, 0, frame1.length);
    System.arraycopy(frame2, 0, combined, frame1.length, frame2.length);

    // 切分为 3 个小碎片分别传入
    int split1 = 3;
    int split2 = frame1.length + 2;

    byte[] part1 = new byte[split1];
    byte[] part2 = new byte[split2 - split1];
    byte[] part3 = new byte[combined.length - split2];

    System.arraycopy(combined, 0, part1, 0, part1.length);
    System.arraycopy(combined, split1, part2, 0, part2.length);
    System.arraycopy(combined, split2, part3, 0, part3.length);

    stream.inboundDataQueue.add(new TestData(ByteBuffer.wrap(part1), false));
    controlStream.listener().onDataAvailable(stream);
    assertEquals(0, daemonSession.received.size());

    stream.inboundDataQueue.add(new TestData(ByteBuffer.wrap(part2), false));
    controlStream.listener().onDataAvailable(stream);
    // 第一帧完整了
    assertEquals(1, daemonSession.received.size());
    assertEquals("inv-1", daemonSession.received.get(0).getInvocationId());

    stream.inboundDataQueue.add(new TestData(ByteBuffer.wrap(part3), false));
    controlStream.listener().onDataAvailable(stream);
    // 第二帧完整了，按到达顺序交付
    assertEquals(2, daemonSession.received.size());
    assertEquals("inv-2", daemonSession.received.get(1).getInvocationId());
  }

  @Test
  void triggersWriteTimeoutViaSchedulerWhenUnacknowledged() {
    controlStream.bindSession(daemonSession);

    Envelope env = createCancelEnvelope("inv-timeout");

    assertEquals(DaemonOfferResult.ACCEPTED, controlStream.offer(env));
    assertEquals(1, stream.sentFrames.size());
    assertEquals(1, scheduler.scheduledTasks.size());
    assertFalse(sessionClosed.get());

    // 模拟 10 秒超时任务触发
    scheduler.runScheduledTasks();

    // 控制信道失败并通知关闭会话
    assertTrue(sessionClosed.get());
    assertFalse(controlStream.isOpen());
  }

  @Test
  void closeAfterFlushWaitsForEndStreamCallback() {
    controlStream.bindSession(daemonSession);

    Envelope env = createCancelEnvelope("inv-end");
    controlStream.offer(env);
    controlStream.closeAfterFlush();

    // 此时数据帧还未回调，会话不应关闭
    assertFalse(sessionClosed.get());
    assertEquals(1, stream.sentFrames.size());

    // 完成第一帧发送回调
    stream.completeLatestSend();

    // 现在发送了 END_STREAM 帧，但该回调还未完成，会话仍然不应关闭
    assertEquals(2, stream.sentFrames.size());
    assertTrue(stream.sentFrames.get(1).isEndStream());
    assertFalse(sessionClosed.get());

    // 完成 END_STREAM 帧回调
    stream.completeLatestSend();
    assertTrue(sessionClosed.get());
  }

  @Test
  void invalidLengthOffersErrorAndClosesAfterFlush() {
    controlStream.bindSession(daemonSession);

    // 传入一个前缀为 4 字节非法长度（例如 0）的帧
    ByteBuffer invalidLenBuf = ByteBuffer.allocate(4).putInt(0).flip();
    stream.inboundDataQueue.add(new TestData(invalidLenBuf, false));
    controlStream.listener().onDataAvailable(stream);

    // 应该发出了包含 Error 的帧
    assertEquals(1, stream.sentFrames.size());
    DataFrame errorDataFrame = stream.sentFrames.get(0);
    byte[] errorBytes = new byte[errorDataFrame.getByteBuffer().remaining()];
    errorDataFrame.getByteBuffer().get(errorBytes);
    Envelope errEnv = ControlProtocolCodec.decodeFrame(errorBytes);
    assertTrue(errEnv.hasError());
    assertTrue(errEnv.getError().getMessage().contains("invalid inbound frame length"));
  }

  @Test
  void offerRejectsWhenClosedOrDraining() {
    controlStream.bindSession(daemonSession);
    controlStream.close();
    assertFalse(controlStream.isOpen());
    assertEquals(DaemonOfferResult.CLOSED, controlStream.offer(createCancelEnvelope("inv-closed")));
  }

  @Test
  void offerRejectsWithBusyWhenPendingBudgetExceeded() {
    controlStream.bindSession(daemonSession);
    Envelope env = createCancelEnvelope("inv-busy");
    for (int i = 0; i <= DaemonControlStream.MAX_PENDING_FRAMES; i++) {
      assertEquals(DaemonOfferResult.ACCEPTED, controlStream.offer(env));
    }
    assertEquals(DaemonOfferResult.BUSY, controlStream.offer(env));
  }

  @Test
  void closeImmediatelyResetsStreamAndNotifiesSessionCloser() {
    controlStream.bindSession(daemonSession);
    controlStream.offer(createCancelEnvelope("inv-reset"));
    controlStream.close();
    assertFalse(controlStream.isOpen());
    assertTrue(daemonSession.closed);
  }

  @Test
  void onResetAndOnFailureNotifyChannelFailure() {
    controlStream.bindSession(daemonSession);
    controlStream.listener().onReset(stream, new ResetFrame(1, 8), Callback.NOOP);
    assertTrue(sessionClosed.get());

    controlStream
        .listener()
        .onFailure(stream, 8, "internal error", new IOException("stream fail"), Callback.NOOP);
  }

  @Test
  void corruptedEnvelopeBodyTriggersErrorOffer() {
    controlStream.bindSession(daemonSession);
    byte[] badBody = new byte[] {0x7F, 0x7F, 0x7F, 0x7F};
    ByteBuffer buf =
        ByteBuffer.allocate(4 + badBody.length).putInt(badBody.length).put(badBody).flip();
    stream.inboundDataQueue.add(new TestData(buf, false));
    controlStream.listener().onDataAvailable(stream);

    assertEquals(1, stream.sentFrames.size());
  }

  @Test
  void peerEndStreamTriggersClose() {
    controlStream.bindSession(daemonSession);
    stream.inboundDataQueue.add(new TestData(ByteBuffer.allocate(0), true));
    controlStream.listener().onDataAvailable(stream);
    assertTrue(daemonSession.closed);
  }

  private static final class TestScheduler extends ScheduledExecutorScheduler {
    private final List<Runnable> scheduledTasks = new ArrayList<>();

    @Override
    public Task schedule(Runnable task, long delay, TimeUnit unit) {
      scheduledTasks.add(task);
      return () -> scheduledTasks.remove(task);
    }

    void runScheduledTasks() {
      List<Runnable> copy = new ArrayList<>(scheduledTasks);
      scheduledTasks.clear();
      for (Runnable r : copy) {
        r.run();
      }
    }
  }

  private static final class TestDaemonSession implements DaemonSession {
    private final List<Envelope> received = new ArrayList<>();
    private boolean closed;

    @Override
    public void receive(Envelope envelope) {
      received.add(envelope);
    }

    @Override
    public void close() {
      this.closed = true;
    }

    @Override
    public void closed() {
      this.closed = true;
    }
  }

  private static final class TestData extends Stream.Data {
    TestData(ByteBuffer buffer, boolean endStream) {
      super(new DataFrame(1, buffer, endStream));
    }
  }

  private static final class TestStream implements Stream {
    private final List<DataFrame> sentFrames = new ArrayList<>();
    private final Queue<Callback> writeCallbacks = new ArrayDeque<>();
    private final Queue<TestData> inboundDataQueue = new ArrayDeque<>();
    private final AtomicInteger demandCount = new AtomicInteger();

    @Override
    public int getId() {
      return 1;
    }

    @Override
    public Stream.Data readData() {
      return inboundDataQueue.poll();
    }

    @Override
    public void demand() {
      demandCount.incrementAndGet();
    }

    @Override
    public void data(DataFrame frame, Callback callback) {
      sentFrames.add(frame);
      writeCallbacks.add(callback);
    }

    void completeLatestSend() {
      Callback cb = writeCallbacks.poll();
      if (cb != null) {
        cb.succeeded();
      }
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
}
