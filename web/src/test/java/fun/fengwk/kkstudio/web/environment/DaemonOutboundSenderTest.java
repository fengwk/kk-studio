package fun.fengwk.kkstudio.web.environment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** DaemonOutboundSender 的严格顺序、双限、失败/超时与 close 围栏契约。 */
class DaemonOutboundSenderTest {

  /** 构造时拒绝所有非正数预算，避免无界或立即超时配置进入运行期。 */
  @Test
  void rejectsInvalidBounds() throws Exception {
    WebSocketSession session = session(message -> {});
    assertThrows(
        IllegalArgumentException.class,
        () -> new DaemonOutboundSender(session, 0, 100, 100, error -> {}));
    assertThrows(
        IllegalArgumentException.class,
        () -> new DaemonOutboundSender(session, 1, 0, 100, error -> {}));
    assertThrows(
        IllegalArgumentException.class,
        () -> new DaemonOutboundSender(session, 1, 100, 0, error -> {}));
  }

  /** 底层首帧阻塞时 enqueue 仍立即返回，释放后按入队顺序串行发送。 */
  @Test
  void sendsStrictlyInOrderWithoutBlockingEnqueue() throws Exception {
    CountDownLatch firstEntered = new CountDownLatch(1);
    CountDownLatch releaseFirst = new CountDownLatch(1);
    List<String> sent = new CopyOnWriteArrayList<>();
    WebSocketSession session =
        session(
            message -> {
              sent.add(((TextMessage) message).getPayload());
              if (sent.size() == 1) {
                firstEntered.countDown();
                releaseFirst.await();
              }
            });
    DaemonOutboundSender sender = new DaemonOutboundSender(session, 4, 100, 5_000, error -> {});
    try {
      assertTrue(sender.enqueue("a"));
      assertTrue(firstEntered.await(5, TimeUnit.SECONDS));
      assertTrue(sender.enqueue("b"));
      assertTrue(sender.enqueue("c"));
      assertEquals(List.of("a"), sent);
      releaseFirst.countDown();
      await(() -> sent.size() == 3);
      assertEquals(List.of("a", "b", "c"), sent);
    } finally {
      releaseFirst.countDown();
      sender.close();
    }
  }

  /** 帧数与 UTF-8 字节都包含在途帧；恰好到限允许，超过一字节或一帧拒绝。 */
  @Test
  void enforcesFrameAndUtf8ByteBounds() throws Exception {
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    WebSocketSession session =
        session(
            message -> {
              entered.countDown();
              release.await();
            });
    DaemonOutboundSender sender = new DaemonOutboundSender(session, 2, 7, 5_000, error -> {});
    try {
      assertTrue(sender.enqueue("中文"));
      assertTrue(entered.await(5, TimeUnit.SECONDS));
      assertTrue(sender.enqueue("x"));
      assertFalse(sender.enqueue("y"));
      assertFalse(sender.enqueue("toolong"));
    } finally {
      sender.close();
      release.countDown();
    }
  }

  /** 同步 send 异常关闭连接、通知一次失败，并允许失败回调重入 close 而不死锁。 */
  @Test
  void sendFailureClosesAndAllowsReentrantClose() throws Exception {
    AtomicInteger failures = new AtomicInteger();
    AtomicReference<DaemonOutboundSender> senderRef = new AtomicReference<>();
    WebSocketSession session =
        session(
            message -> {
              throw new IOException("broken pipe");
            });
    DaemonOutboundSender sender =
        new DaemonOutboundSender(
            session,
            4,
            100,
            5_000,
            error -> {
              failures.incrementAndGet();
              senderRef.get().close();
            });
    senderRef.set(sender);

    assertTrue(sender.enqueue("a"));
    verify(session, timeout(5_000)).close(any());
    assertEquals(1, failures.get());
    assertFalse(sender.enqueue("b"));
  }

  /** 底层 session 已关闭时不调用 sendMessage；失败回调自身异常也不能阻止 close，close IOException 被吞掉。 */
  @Test
  void closedSessionAndFailingCallbackStillCloseIdempotently() throws Exception {
    WebSocketSession session = session(message -> {});
    when(session.isOpen()).thenReturn(false);
    doAnswer(
            invocation -> {
              throw new IOException("already closed");
            })
        .when(session)
        .close(any());
    DaemonOutboundSender sender =
        new DaemonOutboundSender(
            session,
            2,
            100,
            5_000,
            error -> {
              throw new IllegalStateException("callback failed");
            });

    assertFalse(sender.isOpen());
    assertTrue(sender.enqueue("a"));
    verify(session, timeout(5_000)).close(any());
    assertFalse(sender.enqueue("b"));
    sender.close();
  }

  /** 超过单帧发送期限时，即使底层 send 阻塞，也在 watcher 上失败并关闭连接。 */
  @Test
  void sendTimeoutFailsAndClosesSlowConnection() throws Exception {
    CountDownLatch release = new CountDownLatch(1);
    CountDownLatch failed = new CountDownLatch(1);
    WebSocketSession session =
        session(
            message -> {
              release.await();
            });
    DaemonOutboundSender sender =
        new DaemonOutboundSender(session, 4, 100, 30, error -> failed.countDown());
    try {
      assertTrue(sender.enqueue("a"));
      assertTrue(failed.await(5, TimeUnit.SECONDS));
      verify(session, timeout(5_000)).close(any());
      assertFalse(sender.enqueue("b"));
    } finally {
      release.countDown();
      sender.close();
    }
  }

  /** close 清除排队帧并建立拒绝围栏，已阻塞首帧结束后不会再调用第二次 sendMessage。 */
  @Test
  void closePreventsQueuedAndFutureSends() throws Exception {
    CountDownLatch firstEntered = new CountDownLatch(1);
    CountDownLatch releaseFirst = new CountDownLatch(1);
    CountDownLatch firstExited = new CountDownLatch(1);
    AtomicInteger sends = new AtomicInteger();
    WebSocketSession session =
        session(
            message -> {
              sends.incrementAndGet();
              firstEntered.countDown();
              try {
                releaseFirst.await();
              } finally {
                firstExited.countDown();
              }
            });
    DaemonOutboundSender sender = new DaemonOutboundSender(session, 4, 100, 5_000, error -> {});
    assertTrue(sender.enqueue("a"));
    assertTrue(firstEntered.await(5, TimeUnit.SECONDS));
    assertTrue(sender.enqueue("b"));

    sender.close();
    assertFalse(sender.enqueue("c"));
    releaseFirst.countDown();
    assertTrue(firstExited.await(5, TimeUnit.SECONDS));
    assertEquals(1, sends.get());
    sender.close();
  }

  /** protocol ERROR 使用 drain-close：先建立入队围栏，已接受帧发送完成后再关闭，后续普通 close 不得丢帧。 */
  @Test
  void closeAfterFlushDrainsAcceptedFrameBeforeClosing() throws Exception {
    CountDownLatch sendEntered = new CountDownLatch(1);
    CountDownLatch releaseSend = new CountDownLatch(1);
    List<String> sent = new CopyOnWriteArrayList<>();
    WebSocketSession session =
        session(
            message -> {
              sent.add(((TextMessage) message).getPayload());
              sendEntered.countDown();
              releaseSend.await();
            });
    DaemonOutboundSender sender = new DaemonOutboundSender(session, 2, 100, 5_000, error -> {});
    try {
      assertTrue(sender.enqueue("protocol-error"));
      assertTrue(sendEntered.await(5, TimeUnit.SECONDS));
      sender.closeAfterFlush();
      sender.close();
      assertFalse(sender.enqueue("after-fence"));
      releaseSend.countDown();
      verify(session, timeout(5_000)).close(any());
      assertEquals(List.of("protocol-error"), sent);
    } finally {
      releaseSend.countDown();
      sender.close();
    }
  }

  private static WebSocketSession session(SendAction action) throws Exception {
    WebSocketSession session = mock(WebSocketSession.class);
    when(session.getId()).thenReturn("daemon-session");
    when(session.isOpen()).thenReturn(true);
    doAnswer(
            invocation -> {
              action.send(invocation.getArgument(0));
              return null;
            })
        .when(session)
        .sendMessage(any(WebSocketMessage.class));
    return session;
  }

  private static void await(Check check) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (!check.get() && System.nanoTime() < deadline) {
      Thread.sleep(10);
    }
    assertTrue(check.get());
  }

  @FunctionalInterface
  private interface SendAction {
    void send(WebSocketMessage<?> message) throws Exception;
  }

  @FunctionalInterface
  private interface Check {
    boolean get();
  }
}
