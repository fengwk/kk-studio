package fun.fengwk.kkstudio.harness.daemon.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** 真实 OkHttp transport 的握手期限行为：只用公共构造器与本地真实协议 fixture，不访问真实远端或凭据。 */
class OkHttpWebSocketHandshakeDeadlineTest {

  /** 与生产契约一致的握手预算（2 秒）。 */
  private static final long HANDSHAKE_BUDGET_MILLIS = 2000;

  private static final long AWAIT_MILLIS = 3000;
  private static final long THREAD_DRAIN_MILLIS = 5000;

  /** 服务端接受连接但不返回 101 时：握手在预算内有界失败、底层连接被关闭、自有线程被释放。 */
  @Test
  void failsBoundedAndClosesConnectionWhenServerNeverUpgrades() throws Exception {
    int dispatcherBaseline = dispatcherThreadCount();
    try (LocalWebSocketServer server = LocalWebSocketServer.stalled()) {
      OkHttpWebSocketTransport transport = new OkHttpWebSocketTransport(server.uri());
      RecordingListener listener = new RecordingListener();
      try {
        CompletableFuture<DaemonConnection> stage =
            transport.connect(listener).toCompletableFuture();
        assertTrue(server.awaitAccepted(AWAIT_MILLIS), "服务端应接受 TCP 连接");

        assertThrows(
            ExecutionException.class,
            () -> stage.get(HANDSHAKE_BUDGET_MILLIS + AWAIT_MILLIS, TimeUnit.MILLISECONDS));
        // future 结算与 whenComplete 侧效存在正常竞态：以断开计数为准，而非立即读取。
        assertTrue(listener.awaitDisconnected(AWAIT_MILLIS), "握手失败必须通知断开");
        assertEquals(1, listener.disconnections.get());
        assertTrue(server.awaitPeerEnded(AWAIT_MILLIS), "握手失败必须关闭底层连接");
        assertNull(server.failure(), "握手阶段不应出现 fixture I/O 故障");
      } finally {
        transport.close();
      }
    }
    assertTrue(
        awaitDispatcherThreadsAtMost(dispatcherBaseline, THREAD_DRAIN_MILLIS),
        "transport close 后不得残留自有 dispatcher 线程");
  }

  /** upgrade 成功后握手预算不再约束连接：超过预算仍保持打开并可发送数据。 */
  @Test
  void keepsEstablishedConnectionBeyondHandshakeDeadline() throws Exception {
    int dispatcherBaseline = dispatcherThreadCount();
    try (LocalWebSocketServer server = LocalWebSocketServer.upgrading()) {
      OkHttpWebSocketTransport transport = new OkHttpWebSocketTransport(server.uri());
      RecordingListener listener = new RecordingListener();
      try {
        DaemonConnection connection = transport.connect(listener).toCompletableFuture().join();
        assertTrue(server.awaitUpgraded(AWAIT_MILLIS), "服务端应完成 upgrade");

        Thread.sleep(HANDSHAKE_BUDGET_MILLIS + 1000);

        assertTrue(connection.isOpen(), "长连接不应被握手预算终结");
        assertEquals(0, listener.disconnections.get());
        connection.sendText("probe").toCompletableFuture().join();
        assertTrue(server.awaitReceivedFrame(AWAIT_MILLIS), "期限之后仍应能发送数据");
        assertNull(server.failure(), "握手阶段不应出现 fixture I/O 故障");
      } finally {
        transport.close();
      }
    }
    assertTrue(
        awaitDispatcherThreadsAtMost(dispatcherBaseline, THREAD_DRAIN_MILLIS),
        "transport close 后不得残留自有 dispatcher 线程");
  }

  private static int dispatcherThreadCount() {
    ThreadGroup group = Thread.currentThread().getThreadGroup();
    ThreadGroup parent;
    while ((parent = group.getParent()) != null) {
      group = parent;
    }
    Thread[] threads = new Thread[512];
    int count = group.enumerate(threads, true);
    int dispatchers = 0;
    for (int index = 0; index < count; index++) {
      Thread thread = threads[index];
      if (thread != null && thread.isAlive() && thread.getName().startsWith("OkHttp Dispatcher")) {
        dispatchers++;
      }
    }
    return dispatchers;
  }

  private static boolean awaitDispatcherThreadsAtMost(int baseline, long timeoutMillis)
      throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
    while (System.nanoTime() < deadline) {
      if (dispatcherThreadCount() <= baseline) {
        return true;
      }
      Thread.sleep(20);
    }
    return dispatcherThreadCount() <= baseline;
  }

  private static final class RecordingListener implements DaemonTransportListener {

    private final AtomicInteger disconnections = new AtomicInteger();
    private final CountDownLatch disconnected = new CountDownLatch(1);

    @Override
    public void onMessage(String message) {
      // 本测试只关注握手与断开。
    }

    @Override
    public void onDisconnected(Throwable cause) {
      disconnections.incrementAndGet();
      disconnected.countDown();
    }

    boolean awaitDisconnected(long timeoutMillis) throws InterruptedException {
      return disconnected.await(timeoutMillis, TimeUnit.MILLISECONDS);
    }
  }
}
