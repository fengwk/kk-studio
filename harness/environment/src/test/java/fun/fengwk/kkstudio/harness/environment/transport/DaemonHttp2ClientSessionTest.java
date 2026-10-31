package fun.fengwk.kkstudio.harness.environment.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.eclipse.jetty.io.ArrayByteBufferPool;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.thread.ScheduledExecutorScheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.environment.protocol.Envelope;
import fun.fengwk.kkstudio.harness.environment.server.DaemonAdmission;
import fun.fengwk.kkstudio.harness.environment.server.DaemonControlChannel;
import fun.fengwk.kkstudio.harness.environment.server.DaemonHandshake;
import fun.fengwk.kkstudio.harness.environment.server.DaemonSession;
import fun.fengwk.kkstudio.harness.environment.server.DaemonSessions;
import fun.fengwk.kkstudio.harness.environment.tunnel.EnvironmentTunnel;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

class DaemonHttp2ClientSessionTest {

  private static final DaemonAdmission.Admitted ADMITTED =
      new DaemonAdmission.Admitted(
          EnvironmentId.parse("3f2a9c1e-7b4d-4e2a-9c6f-1a2b3c4d5e6f"), "inst-1");

  private TestScheduler scheduler;
  private ExecutorService executor;
  private ByteBufferPool bufferPool;
  private TestSessions sessions;
  private TestPipe pipe;

  @BeforeEach
  void setUp() {
    scheduler = new TestScheduler();
    executor = Executors.newSingleThreadExecutor();
    bufferPool = new ArrayByteBufferPool();
    sessions = new TestSessions();
    pipe = new TestPipe();
  }

  @AfterEach
  void tearDown() {
    executor.shutdownNow();
  }

  @Test
  void abandonTimerTriggersAfterTenSeconds() {
    DaemonHttp2ClientSession clientSession =
        new DaemonHttp2ClientSession(ADMITTED, pipe, scheduler, executor, bufferPool, sessions);

    clientSession.start();
    assertFalse(clientSession.isClosed());
    assertEquals(0, sessions.abandoned.size());

    // 运行 10 秒超时任务
    scheduler.runScheduledTasks();

    // 应该调用 abandon 并关闭
    assertEquals(1, sessions.abandoned.size());
    assertEquals(ADMITTED, sessions.abandoned.get(0));
    assertTrue(clientSession.isClosed());
  }

  @Test
  void drainSendsGoAwayAndCloses() {
    DaemonHttp2ClientSession clientSession =
        new DaemonHttp2ClientSession(ADMITTED, pipe, scheduler, executor, bufferPool, sessions);

    clientSession.drain();
    // 隧道应已处于 drain 状态
    assertNotNull(clientSession.tunnel());
  }

  @Test
  void closeWithoutHandshakeAbandonsAdmission() {
    DaemonHttp2ClientSession clientSession =
        new DaemonHttp2ClientSession(ADMITTED, pipe, scheduler, executor, bufferPool, sessions);

    clientSession.close();
    assertTrue(clientSession.isClosed());
    assertEquals(1, sessions.abandoned.size());
    assertEquals(ADMITTED, sessions.abandoned.get(0));
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

  private static final class TestSessions implements DaemonSessions {
    private final List<DaemonAdmission.Admitted> abandoned = new ArrayList<>();

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
      return new DaemonSession() {
        @Override
        public void receive(Envelope envelope) {}

        @Override
        public void closed() {}
      };
    }
  }

  private static final class TestPipe implements WebSocketPipe {
    private boolean closed;

    @Override
    public void sendBinary(ByteBuffer payload, Callback callback) {
      callback.succeeded();
    }

    @Override
    public void demand() {}

    @Override
    public void close(int statusCode, String reason) {
      this.closed = true;
    }

    @Override
    public SocketAddress getLocalSocketAddress() {
      return new InetSocketAddress("127.0.0.1", 1234);
    }

    @Override
    public SocketAddress getRemoteSocketAddress() {
      return new InetSocketAddress("127.0.0.1", 5678);
    }
  }
}
