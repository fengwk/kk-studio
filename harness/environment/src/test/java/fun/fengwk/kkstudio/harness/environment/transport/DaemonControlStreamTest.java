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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.environment.protocol.ControlProtocolCodec;
import fun.fengwk.kkstudio.harness.environment.protocol.Envelope;
import fun.fengwk.kkstudio.harness.environment.server.DaemonOfferResult;
import fun.fengwk.kkstudio.harness.environment.server.DaemonSession;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

class DaemonControlStreamTest {

  private TestStream stream;
  private AtomicBoolean sessionClosed;
  private TestDaemonSession daemonSession;
  private TestClock clock;
  private DaemonControlStream controlStream;

  private static Envelope sampleEnvelope(long seq) {
    Envelope template = loadGolden("heartbeat.bin");
    return template.toBuilder()
        .setHeartbeat(template.getHeartbeat().toBuilder().setIdeLeaseRound(seq))
        .build();
  }

  private static Envelope loadGolden(String name) {
    try {
      Path path = Path.of("src", "main", "protocol", "golden", name);
      if (!Files.exists(path)) {
        path = Path.of("harness", "environment", "src", "main", "protocol", "golden", name);
      }
      return ControlProtocolCodec.decodeBinary(Files.readAllBytes(path));
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  @BeforeEach
  void setUp() {
    stream = new TestStream();
    sessionClosed = new AtomicBoolean(false);
    daemonSession = new TestDaemonSession();
    clock = new TestClock(1000);
    controlStream = new DaemonControlStream(stream, () -> sessionClosed.set(true), clock);
    controlStream.bindSession(daemonSession);
  }

  @Test
  void receivesInboundFramesInOrderAcrossChunks() {
    Envelope env1 = sampleEnvelope(10);
    Envelope env2 = sampleEnvelope(20);

    byte[] frame1 = ControlProtocolCodec.encodeFrame(env1);
    byte[] frame2 = ControlProtocolCodec.encodeFrame(env2);

    byte[] combined = new byte[frame1.length + frame2.length];
    System.arraycopy(frame1, 0, combined, 0, frame1.length);
    System.arraycopy(frame2, 0, combined, frame1.length, frame2.length);

    int split = combined.length / 2;
    byte[] part1 = new byte[split];
    byte[] part2 = new byte[combined.length - split];
    System.arraycopy(combined, 0, part1, 0, split);
    System.arraycopy(combined, split, part2, 0, part2.length);

    stream.inboundQueue.add(new TestData(ByteBuffer.wrap(part1), false));
    controlStream.onDataAvailable();
    assertEquals(1, daemonSession.received.size());

    stream.inboundQueue.add(new TestData(ByteBuffer.wrap(part2), false));
    controlStream.onDataAvailable();
    assertEquals(2, daemonSession.received.size());
    assertEquals(10, daemonSession.received.get(0).getHeartbeat().getIdeLeaseRound());
    assertEquals(20, daemonSession.received.get(1).getHeartbeat().getIdeLeaseRound());
  }

  @Test
  void enforcesCapacityBudgetWithBusy() {
    Envelope envelope = sampleEnvelope(1);

    for (int i = 0; i < 256; i++) {
      DaemonOfferResult res = controlStream.offer(envelope);
      assertEquals(DaemonOfferResult.ACCEPTED, res);
    }

    assertEquals(DaemonOfferResult.BUSY, controlStream.offer(envelope));
  }

  @Test
  void writeTimeoutClosesSession() {
    Envelope envelope = sampleEnvelope(1);
    // 第一帧进入并开始发送（尚未完成）
    assertEquals(DaemonOfferResult.ACCEPTED, controlStream.offer(envelope));

    // 第二帧排队等待
    Envelope envelope2 = sampleEnvelope(2);
    assertEquals(DaemonOfferResult.ACCEPTED, controlStream.offer(envelope2));

    // 排队等待流逝 11 秒（超时阈值 10 秒）
    clock.advance(11_000);

    // 第一帧发送完成，驱动 triggerFlush 处理第二帧，此时检测到排队超时
    stream.completeLatestData();

    assertTrue(sessionClosed.get());
    assertTrue(daemonSession.closedNotified.get());
    assertFalse(controlStream.isOpen());
  }

  @Test
  void peerResetClosesControlChannelAndSession() {
    controlStream.onReset();
    assertTrue(sessionClosed.get());
    assertTrue(daemonSession.closedNotified.get());
    assertFalse(controlStream.isOpen());
  }

  private static final class TestClock extends Clock {
    private final AtomicLong millis;

    TestClock(long initial) {
      this.millis = new AtomicLong(initial);
    }

    void advance(long ms) {
      millis.addAndGet(ms);
    }

    @Override
    public ZoneId getZone() {
      return ZoneId.of("UTC");
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return Instant.ofEpochMilli(millis.get());
    }

    @Override
    public long millis() {
      return millis.get();
    }
  }

  private static final class TestDaemonSession implements DaemonSession {
    private final List<Envelope> received = new ArrayList<>();
    private final AtomicBoolean closedNotified = new AtomicBoolean(false);

    @Override
    public void receive(Envelope envelope) {
      received.add(envelope);
    }

    @Override
    public void closed() {
      closedNotified.set(true);
    }
  }

  private static final class TestData extends Stream.Data {
    TestData(ByteBuffer buffer, boolean endStream) {
      super(new DataFrame(1, buffer, endStream));
    }
  }

  private static final class TestStream implements Stream {
    private final Queue<TestData> inboundQueue = new ArrayDeque<>();
    private final Queue<Callback> writeCallbacks = new ArrayDeque<>();
    private int demandCalls;

    @Override
    public int getId() {
      return 1;
    }

    @Override
    public Stream.Data readData() {
      return inboundQueue.poll();
    }

    @Override
    public void demand() {
      demandCalls++;
    }

    @Override
    public void data(DataFrame frame, Callback callback) {
      writeCallbacks.add(callback);
    }

    @Override
    public void reset(ResetFrame frame, Callback callback) {
      callback.succeeded();
    }

    void completeLatestData() {
      Callback cb = writeCallbacks.poll();
      if (cb != null) {
        cb.succeeded();
      }
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
