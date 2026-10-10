package fun.fengwk.kkstudio.web.environment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;

import fun.fengwk.kkstudio.harness.environment.server.DaemonOfferResult;
import fun.fengwk.kkstudio.share.notification.NotificationCarrier;
import fun.fengwk.kkstudio.share.notification.NotificationLimits;
import fun.fengwk.kkstudio.share.notification.NotificationPeerLink;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** DaemonOutboundSender 的严格顺序、共享 carrier 出站、失败/超时与 close 围栏契约。 */
class DaemonOutboundSenderTest {

  private static final UUID DECODE_SELF = UUID.randomUUID();

  /** 共享 link 到期 timer；与被测 deadline timer 分离，避免污染 deadline 队列断言。 */
  private static final ScheduledExecutorService LINK_TIMER =
      Executors.newSingleThreadScheduledExecutor(
          runnable -> {
            Thread thread = new Thread(runnable, "sender-link-expire-timer");
            thread.setDaemon(true);
            return thread;
          });

  private final ScheduledThreadPoolExecutor timer =
      EnvironmentDaemonWebSocketConfiguration.environmentDaemonSendDeadlineTimer();

  @AfterEach
  void shutdownTimer() {
    timer.shutdownNow();
  }

  /** 构造时拒绝非正数发送超时。 */
  @Test
  void rejectsInvalidBounds() throws Exception {
    WebSocketSession session = session(message -> {});
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new DaemonOutboundSender(
                session, newLink(NotificationLimits.defaults()), 0, timer, error -> {}));
  }

  /** 底层首帧阻塞时 offerText 仍立即返回 ACCEPTED，释放后按入队顺序串行发送。 */
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
    DaemonOutboundSender sender =
        new DaemonOutboundSender(
            session, newLink(NotificationLimits.defaults()), 5_000, timer, error -> {});
    try {
      assertEquals(DaemonOfferResult.ACCEPTED, sender.offerText("a"));
      assertTrue(firstEntered.await(5, TimeUnit.SECONDS));
      assertEquals(DaemonOfferResult.ACCEPTED, sender.offerText("b"));
      assertEquals(DaemonOfferResult.ACCEPTED, sender.offerText("c"));
      assertEquals(List.of("a"), decode(sent));
      releaseFirst.countDown();
      await(() -> sent.size() == 3);
      assertEquals(List.of("a", "b", "c"), decode(sent));
    } finally {
      releaseFirst.countDown();
      sender.close();
    }
  }

  /** 队列与字节预算都由共享 outbox 持有；到限返回 BUSY 且不关闭连接。 */
  @Test
  void enforcesQueueAndByteBounds() throws Exception {
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    WebSocketSession session =
        session(
            message -> {
              entered.countDown();
              release.await();
            });
    NotificationLimits tiny = new NotificationLimits(64, 64, 1, 64, 1, Duration.ofSeconds(5), 32);
    DaemonOutboundSender sender =
        new DaemonOutboundSender(session, newLink(tiny), 5_000, timer, error -> {});
    try {
      assertEquals(DaemonOfferResult.ACCEPTED, sender.offerText("中文"));
      assertTrue(entered.await(5, TimeUnit.SECONDS));
      assertEquals(DaemonOfferResult.BUSY, sender.offerText("x"));
      assertEquals(DaemonOfferResult.BUSY, sender.offerText("y".repeat(65)));
      // BUSY 只表达本地容量拒绝：连接仍然可用，且从未被关闭。
      assertTrue(sender.isOpen());
      verify(session, never()).close(any());
    } finally {
      sender.close();
      release.countDown();
    }
  }

  /** 同步 send 异常关闭连接、通知一次失败，并允许失败回调重入 close 而不死锁；此后递交一律 CLOSED。 */
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
            newLink(NotificationLimits.defaults()),
            5_000,
            timer,
            error -> {
              failures.incrementAndGet();
              senderRef.get().close();
            });
    senderRef.set(sender);

    assertEquals(DaemonOfferResult.ACCEPTED, sender.offerText("a"));
    verify(session, timeout(5_000)).close(any());
    assertEquals(1, failures.get());
    assertEquals(DaemonOfferResult.CLOSED, sender.offerText("b"));
  }

  /**
   * 底层 session 已关闭时不调用 sendMessage；失败回调自身异常也不能阻止 close，close IOException 被吞掉； 失败收敛后 sender 对外表现为
   * CLOSED，而不是把发送失败回传给递交方。
   */
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
            newLink(NotificationLimits.defaults()),
            5_000,
            timer,
            error -> {
              throw new IllegalStateException("callback failed");
            });

    assertFalse(sender.isOpen());
    assertEquals(DaemonOfferResult.ACCEPTED, sender.offerText("a"));
    verify(session, timeout(5_000)).close(any());
    assertEquals(DaemonOfferResult.CLOSED, sender.offerText("b"));
    sender.close();
  }

  /** 超过单批发送期限时，即使底层 send 阻塞，也在 timer 派发的独立线程上失败并关闭连接。 */
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
        new DaemonOutboundSender(
            session,
            newLink(NotificationLimits.defaults()),
            30,
            timer,
            error -> failed.countDown());
    try {
      assertEquals(DaemonOfferResult.ACCEPTED, sender.offerText("a"));
      assertTrue(failed.await(5, TimeUnit.SECONDS));
      verify(session, timeout(5_000)).close(any());
      assertEquals(DaemonOfferResult.CLOSED, sender.offerText("b"));
    } finally {
      release.countDown();
      sender.close();
    }
  }

  /** close 清除排队消息并建立拒绝围栏（CLOSED），已阻塞首帧结束后不会再调用第二次 sendMessage。 */
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
    DaemonOutboundSender sender =
        new DaemonOutboundSender(
            session, newLink(NotificationLimits.defaults()), 5_000, timer, error -> {});
    assertEquals(DaemonOfferResult.ACCEPTED, sender.offerText("a"));
    assertTrue(firstEntered.await(5, TimeUnit.SECONDS));
    assertEquals(DaemonOfferResult.ACCEPTED, sender.offerText("b"));

    sender.close();
    assertEquals(DaemonOfferResult.CLOSED, sender.offerText("c"));
    releaseFirst.countDown();
    assertTrue(firstExited.await(5, TimeUnit.SECONDS));
    assertEquals(1, sends.get());
    sender.close();
  }

  /** protocol ERROR 使用 drain-close：先建立入队围栏（后续递交 CLOSED），已接受帧发送完成后再关闭，普通 close 不得丢帧。 */
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
    DaemonOutboundSender sender =
        new DaemonOutboundSender(
            session, newLink(NotificationLimits.defaults()), 5_000, timer, error -> {});
    try {
      assertEquals(DaemonOfferResult.ACCEPTED, sender.offerText("protocol-error"));
      assertTrue(sendEntered.await(5, TimeUnit.SECONDS));
      sender.closeAfterFlush();
      sender.close();
      assertEquals(DaemonOfferResult.CLOSED, sender.offerText("after-fence"));
      releaseSend.countDown();
      verify(session, timeout(5_000)).close(any());
      assertEquals(List.of("protocol-error"), decode(sent));
    } finally {
      releaseSend.countDown();
      sender.close();
    }
  }

  /** 空队列 drain-close 必须立即释放共享 link（含到期任务），不能只关 transport 而遗留 expiry。 */
  @Test
  void closeAfterFlushOnEmptyQueueReleasesLinkAndExpiry() throws Exception {
    ScheduledThreadPoolExecutor linkTimer =
        new ScheduledThreadPoolExecutor(
            1,
            runnable -> {
              Thread thread = new Thread(runnable, "empty-flush-link-timer");
              thread.setDaemon(true);
              return thread;
            });
    linkTimer.setRemoveOnCancelPolicy(true);
    NotificationPeerLink link =
        new NotificationPeerLink(
            UUID.randomUUID(),
            NotificationPeerLink.DAEMON_TOPIC,
            NotificationLimits.defaults(),
            linkTimer,
            body -> {},
            () -> {});
    WebSocketSession session = session(message -> {});
    DaemonOutboundSender sender =
        new DaemonOutboundSender(session, link, 10_000, timer, error -> {});
    try {
      assertFalse(linkTimer.getQueue().isEmpty());

      sender.closeAfterFlush();

      await(() -> link.isClosed());
      assertTrue(link.isClosed());
      assertNull(link.peer());
      assertEquals(0, link.pendingBytes());
      await(() -> linkTimer.getQueue().isEmpty());
      verify(session, timeout(5_000)).close(any());
    } finally {
      sender.close();
      linkTimer.shutdownNow();
    }
  }

  /** 多连接共享 timer：阻塞的失败回调不占用 timer，另一连接仍能裁决超时。 */
  @Test
  void blockedFailureCallbackDoesNotBlockSharedDeadlineTimer() throws Exception {
    CountDownLatch entered = new CountDownLatch(2);
    CountDownLatch releaseSend = new CountDownLatch(1);
    CountDownLatch firstFailure = new CountDownLatch(1);
    CountDownLatch releaseCallback = new CountDownLatch(1);
    CountDownLatch secondFailure = new CountDownLatch(1);
    WebSocketSession first =
        session(
            message -> {
              entered.countDown();
              releaseSend.await();
            });
    WebSocketSession second =
        session(
            message -> {
              entered.countDown();
              releaseSend.await();
            });
    AtomicInteger failures = new AtomicInteger();
    DaemonOutboundSender one =
        new DaemonOutboundSender(
            first,
            newLink(NotificationLimits.defaults()),
            500,
            timer,
            error -> {
              failures.incrementAndGet();
              firstFailure.countDown();
              try {
                releaseCallback.await();
              } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
              }
            });
    DaemonOutboundSender two =
        new DaemonOutboundSender(
            second,
            newLink(NotificationLimits.defaults()),
            500,
            timer,
            error -> {
              failures.incrementAndGet();
              secondFailure.countDown();
            });
    try {
      one.offerText("a");
      two.offerText("b");
      assertTrue(entered.await(5, TimeUnit.SECONDS));
      assertTrue(firstFailure.await(5, TimeUnit.SECONDS));
      assertTrue(secondFailure.await(5, TimeUnit.SECONDS));
      verify(first, timeout(5_000)).close(any());
      assertEquals(DaemonOfferResult.CLOSED, one.offerText("late"));
      assertEquals(DaemonOfferResult.CLOSED, two.offerText("late"));
    } finally {
      releaseCallback.countDown();
      releaseSend.countDown();
      one.close();
      two.close();
    }
    verify(first, timeout(5_000).times(1)).close(any());
    verify(second, timeout(5_000).times(1)).close(any());
    assertEquals(2, failures.get());
  }

  /** drain-close 保留在途 deadline 并在发送完成时撤销；连接关闭不关闭共享 timer。 */
  @Test
  void completedFramesRemoveDeadlinesWithoutStoppingTimer() throws Exception {
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    WebSocketSession first =
        session(
            message -> {
              entered.countDown();
              release.await();
            });
    DaemonOutboundSender one =
        new DaemonOutboundSender(
            first, newLink(NotificationLimits.defaults()), 10_000, timer, error -> {});
    try {
      one.offerText("a");
      assertTrue(entered.await(5, TimeUnit.SECONDS));
      assertEquals(1, timer.getQueue().size());
      one.closeAfterFlush();
      one.close();
      assertEquals(1, timer.getQueue().size());
      release.countDown();
      verify(first, timeout(5_000)).close(any());
      await(() -> timer.getQueue().isEmpty());
      WebSocketSession second = session(message -> {});
      DaemonOutboundSender two =
          new DaemonOutboundSender(
              second, newLink(NotificationLimits.defaults()), 10_000, timer, error -> {});
      try {
        two.offerText("b");
        verify(second, timeout(5_000)).sendMessage(any());
        await(() -> timer.getQueue().isEmpty());
        assertFalse(timer.isShutdown());
      } finally {
        two.close();
      }
    } finally {
      release.countDown();
      one.close();
    }
  }

  /** 普通 close 立即撤销正在发送的任务，即使 send 阻塞也不能保留 timer 排队项。 */
  @Test
  void closeCancelsPendingDeadline() throws Exception {
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    WebSocketSession session =
        session(
            message -> {
              entered.countDown();
              release.await();
            });
    DaemonOutboundSender sender =
        new DaemonOutboundSender(
            session, newLink(NotificationLimits.defaults()), 10_000, timer, error -> {});
    try {
      sender.offerText("a");
      assertTrue(entered.await(5, TimeUnit.SECONDS));
      assertEquals(1, timer.getQueue().size());
      sender.close();
      assertTrue(timer.getQueue().isEmpty());
      assertFalse(timer.isShutdown());
      verify(session, timeout(5_000).times(1)).close(any());
    } finally {
      release.countDown();
      sender.close();
    }
  }

  /** 手动触发同一个 deadline 两次并与 send 异常、drain-close 交错：只能有一次失败和 transport close。 */
  @Test
  void deadlineRacingWithSendFailureAndDrainClosesOnlyOnce() throws Exception {
    CapturingTimer clock = new CapturingTimer();
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    CountDownLatch failed = new CountDownLatch(1);
    AtomicInteger notifications = new AtomicInteger();
    WebSocketSession session =
        session(
            message -> {
              entered.countDown();
              release.await();
              throw new IOException("send failed after deadline");
            });
    DaemonOutboundSender sender =
        new DaemonOutboundSender(
            session,
            newLink(NotificationLimits.defaults()),
            10_000,
            clock,
            error -> {
              notifications.incrementAndGet();
              failed.countDown();
            });
    try {
      sender.offerText("a");
      assertTrue(entered.await(5, TimeUnit.SECONDS));
      assertEquals(DaemonOfferResult.ACCEPTED, sender.offerText("queued"));
      sender.closeAfterFlush();
      Runnable deadline = clock.deadline.get();
      deadline.run();
      deadline.run();
      assertTrue(failed.await(5, TimeUnit.SECONDS));
      release.countDown();
      verify(session, timeout(5_000).times(1)).close(any());
      assertEquals(DaemonOfferResult.CLOSED, sender.offerText("b"));
      verify(session, times(1)).sendMessage(any());
      assertEquals(1, notifications.get());
    } finally {
      release.countDown();
      sender.close();
      clock.shutdownNow();
    }
  }

  /** 已成功完成的帧即使过期任务被错误地再次执行，状态门禁也必须忽略它。 */
  @Test
  void cancelledDeadlineCannotFailCompletedFrame() throws Exception {
    CapturingTimer clock = new CapturingTimer();
    CountDownLatch sent = new CountDownLatch(1);
    AtomicInteger notifications = new AtomicInteger();
    WebSocketSession session = session(message -> sent.countDown());
    DaemonOutboundSender sender =
        new DaemonOutboundSender(
            session,
            newLink(NotificationLimits.defaults()),
            10_000,
            clock,
            error -> notifications.incrementAndGet());
    try {
      sender.offerText("a");
      assertTrue(sent.await(5, TimeUnit.SECONDS));
      await(() -> clock.getQueue().isEmpty());
      clock.deadline.get().run();
      assertEquals(0, notifications.get());
      assertEquals(DaemonOfferResult.ACCEPTED, sender.offerText("b"));
    } finally {
      sender.close();
      clock.shutdownNow();
    }
  }

  private static NotificationPeerLink newLink(NotificationLimits limits) {
    return new NotificationPeerLink(
        UUID.randomUUID(),
        NotificationPeerLink.DAEMON_TOPIC,
        limits,
        LINK_TIMER,
        body -> {},
        () -> {});
  }

  private static List<String> decode(List<String> frames) {
    List<String> bodies = new ArrayList<>(frames.size());
    for (String frame : frames) {
      bodies.add(
          new String(
              NotificationCarrier.decode(frame, NotificationLimits.defaults(), DECODE_SELF).bytes(),
              StandardCharsets.UTF_8));
    }
    return bodies;
  }

  private static final class CapturingTimer extends ScheduledThreadPoolExecutor {
    private final AtomicReference<Runnable> deadline = new AtomicReference<>();

    private CapturingTimer() {
      super(1);
      setRemoveOnCancelPolicy(true);
    }

    @Override
    public ScheduledFuture<?> schedule(Runnable task, long delay, TimeUnit unit) {
      deadline.set(task);
      return super.schedule(task, delay, unit);
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
