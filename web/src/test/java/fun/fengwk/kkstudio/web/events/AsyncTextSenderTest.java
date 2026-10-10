package fun.fengwk.kkstudio.web.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.websocket.CloseReason;
import jakarta.websocket.RemoteEndpoint.Async;
import jakarta.websocket.SendHandler;
import jakarta.websocket.SendResult;
import jakarta.websocket.Session;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.share.notification.NotificationCarrier;
import fun.fengwk.kkstudio.share.notification.NotificationLimits;
import fun.fengwk.kkstudio.share.notification.NotificationPeerLink;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** AsyncTextSender：共享 outbox 公平批次驱动 AsyncRemote、发送超时、字节/包数预算、内联回调不栈溢出，以及失败/关闭释放。 */
class AsyncTextSenderTest {

  private static final String TOPIC = "app.events.v2";
  private static final UUID SELF = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
  private static final UUID DECODER_SELF = UUID.fromString("00000000-0000-0000-0000-0000000000bb");
  private static final int SEND_TIMEOUT_MILLIS = 10_000;

  private ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
  private ExecutorService sendExecutor = Executors.newSingleThreadExecutor();
  private final List<String> sent = new CopyOnWriteArrayList<>();

  @BeforeEach
  void setUp() {
    timer = Executors.newSingleThreadScheduledExecutor();
    sendExecutor = Executors.newSingleThreadExecutor();
  }

  @AfterEach
  void tearDown() {
    sendExecutor.shutdownNow();
    timer.shutdownNow();
  }

  @Test
  void setsBoundedSendTimeoutOnTheAsyncRemote() {
    Async async = mock(Async.class);
    Session session = mock(Session.class);
    when(session.getAsyncRemote()).thenReturn(async);

    new AsyncTextSender(session, newLink(limits(8, 32)), sendExecutor, SEND_TIMEOUT_MILLIS);

    verify(async).setSendTimeout(SEND_TIMEOUT_MILLIS);
  }

  @Test
  void sendsLogicalMessageAsSingleCarrierFragment() throws Exception {
    RecordingAsync recording = recordingSession(false, null);
    AsyncTextSender sender =
        new AsyncTextSender(
            recording.session, newLink(limits(8, 32)), sendExecutor, SEND_TIMEOUT_MILLIS);

    assertTrue(sender.offer("hello"));

    assertTrue(await(() -> sent.size() == 1));
    NotificationCarrier carrier = decode(sent.get(0));
    assertEquals("hello", new String(carrier.bytes(), StandardCharsets.UTF_8));
    assertEquals(1, carrier.count());
  }

  @Test
  void largeMessageRotatesWithSmallMessageFairly() throws Exception {
    RecordingAsync recording = recordingSession(false, null);
    // 每批只发 1 片：大包（3 片）与小包交替，验证共享 outbox 公平轮转。
    AsyncTextSender sender =
        new AsyncTextSender(
            recording.session, newLink(limits(8, 1)), sendExecutor, SEND_TIMEOUT_MILLIS);

    String big = "a".repeat(15_000);
    assertTrue(sender.offer(big));
    assertTrue(sender.offer("small"));

    assertTrue(await(() -> sent.size() == 4));
    // big 3 片 + small 1 片：小包必须插在大包中间，而不是等大包全部发完。
    assertEquals(
        List.of(15_000, 5, 15_000, 15_000),
        sent.stream().map(this::decode).map(NotificationCarrier::totalBytes).toList());
    assertEquals(5, decode(sent.get(1)).bytes().length);
  }

  @Test
  void rejectsOfferBeyondPendingByteBudget() throws Exception {
    CountDownLatch firstSendStarted = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    RecordingAsync recording = recordingSession(false, new Blocker(firstSendStarted, release));
    AsyncTextSender sender =
        new AsyncTextSender(
            recording.session, newLink(limits(8, 1)), sendExecutor, SEND_TIMEOUT_MILLIS);

    assertTrue(sender.offer("a".repeat(15_000)));
    assertTrue(firstSendStarted.await(5, TimeUnit.SECONDS), "first send must be in flight");

    // pending 15_000/20_000：再来一个 15_000 越界，5_000 恰好在预算内。
    assertFalse(sender.offer("b".repeat(15_000)));
    assertTrue(sender.offer("c".repeat(5_000)));

    release.countDown();
    // 大包 3 片 + 5_000 的 1 片（sendBatchFrames=1，逐片发送）。
    assertTrue(await(() -> sent.size() == 4));
  }

  @Test
  void rejectsOfferBeyondLogicalPacketCapacity() throws Exception {
    CountDownLatch firstSendStarted = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    RecordingAsync recording = recordingSession(false, new Blocker(firstSendStarted, release));
    AsyncTextSender sender =
        new AsyncTextSender(
            recording.session, newLink(limits(1, 1)), sendExecutor, SEND_TIMEOUT_MILLIS);

    assertTrue(sender.offer("first"));
    assertTrue(firstSendStarted.await(5, TimeUnit.SECONDS));
    // queueCapacity=1 表示逻辑包数：在途包已占满，第二个逻辑包被拒。
    assertFalse(sender.offer("second"));

    release.countDown();
    assertTrue(await(() -> sent.size() == 1));
  }

  @Test
  void inlineSendCompletionAcrossManyBatchesDoesNotStackOverflow() throws Exception {
    AsyncTextSender sender =
        new AsyncTextSender(
            recordingSession(false, null).session,
            newLink(NotificationLimits.defaults()),
            sendExecutor,
            SEND_TIMEOUT_MILLIS);

    // 5 MiB 逻辑消息：约 972 片、31 批；内联回调 + 逐批 resubmit 绝不允许递归串发。
    String huge = "x".repeat(5 * 1024 * 1024);
    assertTrue(sender.offer(huge));

    assertTrue(await(() -> sent.size() == NotificationCarrier.count(5 * 1024 * 1024)));
  }

  @Test
  void failFlushesErrorFrameThroughSameBudgetThenCloses() throws Exception {
    CountDownLatch firstSendStarted = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    RecordingAsync recording = recordingSession(false, new Blocker(firstSendStarted, release));
    AsyncTextSender sender =
        new AsyncTextSender(
            recording.session, newLink(limits(8, 32)), sendExecutor, SEND_TIMEOUT_MILLIS);

    assertTrue(sender.offer("before"));
    // 让 "before" 真正在途，再终止：已排队/in-flight 逻辑包被丢弃，error 作为最后一包走同一预算。
    assertTrue(firstSendStarted.await(5, TimeUnit.SECONDS));
    sender.fail(
        "{\"type\":\"error\",\"message\":\"boom\"}", CloseReason.CloseCodes.VIOLATED_POLICY);
    release.countDown();

    assertTrue(await(() -> sent.size() == 2));
    assertEquals(
        "{\"type\":\"error\",\"message\":\"boom\"}",
        new String(decode(sent.get(1)).bytes(), StandardCharsets.UTF_8));
    verify(recording.session, timeout(5_000)).close(any(CloseReason.class));
    assertFalse(sender.offer("after"), "failed sender rejects further offers");
  }

  @Test
  void failedSendClosesSessionAndStopsTheChain() throws Exception {
    RecordingAsync recording = recordingSession(true, null);
    AsyncTextSender sender =
        new AsyncTextSender(
            recording.session, newLink(limits(8, 32)), sendExecutor, SEND_TIMEOUT_MILLIS);

    assertTrue(sender.offer("a"));

    verify(recording.session, timeout(5_000)).close(any(CloseReason.class));
    assertFalse(sender.offer("b"));
  }

  @Test
  void closeReleasesPendingAndClosesSession() throws Exception {
    CountDownLatch firstSendStarted = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    RecordingAsync recording = recordingSession(false, new Blocker(firstSendStarted, release));
    NotificationPeerLink link = newLink(limits(8, 1));
    AsyncTextSender sender =
        new AsyncTextSender(recording.session, link, sendExecutor, SEND_TIMEOUT_MILLIS);

    assertTrue(sender.offer("a".repeat(15_000)));
    assertTrue(firstSendStarted.await(5, TimeUnit.SECONDS));
    release.countDown();

    sender.close();
    assertFalse(link.hasPending(), "close must release every queued/in-flight byte");
    verify(recording.session, timeout(5_000)).close(any(CloseReason.class));
    assertFalse(sender.offer("b"));
  }

  @Test
  void oversizedSingleMessageIsRejectedWithoutBreakingTheChain() throws Exception {
    RecordingAsync recording = recordingSession(false, null);
    AsyncTextSender sender =
        new AsyncTextSender(
            recording.session, newLink(limits(8, 32)), sendExecutor, SEND_TIMEOUT_MILLIS);

    assertFalse(sender.offer("x".repeat(30_000)), "larger than the logical message limit");
    assertTrue(sender.offer("ok"));
    assertTrue(await(() -> sent.size() == 1));
  }

  @Test
  void constructorRejectsNonPositiveSendTimeout() {
    Session session = mock(Session.class);
    when(session.getAsyncRemote()).thenReturn(mock(Async.class));

    assertThrows(
        IllegalArgumentException.class,
        () -> new AsyncTextSender(session, newLink(limits(8, 32)), sendExecutor, 0));
  }

  @Test
  void failAfterStoppedIsIdempotentAndDoesNotResubmit() throws Exception {
    RecordingAsync recording = recordingSession(false, null);
    AsyncTextSender sender =
        new AsyncTextSender(
            recording.session, newLink(limits(8, 32)), sendExecutor, SEND_TIMEOUT_MILLIS);

    sender.fail("{\"type\":\"error\"}", CloseReason.CloseCodes.VIOLATED_POLICY);
    assertTrue(await(() -> sent.size() == 1));

    sender.fail("{\"type\":\"error\",\"again\":true}", CloseReason.CloseCodes.VIOLATED_POLICY);

    assertEquals(1, sent.size(), "a stopped sender must not enqueue another error frame");
  }

  @Test
  void failWithOversizedErrorFrameClosesDirectlyWithoutUnbudgetedBypass() throws Exception {
    ManualExecutor manual = new ManualExecutor();
    RecordingAsync recording = recordingSession(false, null);
    // 逻辑整包上限 20 字节：error 帧无法进入同一预算，只能直接关闭。
    AsyncTextSender sender =
        new AsyncTextSender(recording.session, newLink(smallLimits()), manual, SEND_TIMEOUT_MILLIS);

    assertTrue(sender.offer("ok"));
    sender.fail("x".repeat(100), CloseReason.CloseCodes.VIOLATED_POLICY);
    manual.runAll();

    verify(recording.session, timeout(5_000)).close(any(CloseReason.class));
    assertFalse(sender.offer("after"));
  }

  @Test
  void synchronousSendFailureClosesSessionAndStopsTheChain() throws Exception {
    Session session = mock(Session.class);
    Async async = mock(Async.class);
    when(session.getAsyncRemote()).thenReturn(async);
    doThrow(new IllegalStateException("boom"))
        .when(async)
        .sendText(any(String.class), any(SendHandler.class));

    AsyncTextSender sender =
        new AsyncTextSender(session, newLink(limits(8, 32)), sendExecutor, SEND_TIMEOUT_MILLIS);
    assertTrue(sender.offer("a"));

    verify(session, timeout(5_000)).close(any(CloseReason.class));
    assertFalse(sender.offer("b"));
  }

  @Test
  void closeWhileSendInFlightBreaksTheRemainingBatch() throws Exception {
    CountDownLatch firstSendStarted = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    RecordingAsync recording = recordingSession(false, new Blocker(firstSendStarted, release));
    AsyncTextSender sender =
        new AsyncTextSender(
            recording.session, newLink(limits(8, 32)), sendExecutor, SEND_TIMEOUT_MILLIS);

    assertTrue(sender.offer("a".repeat(15_000))); // 3 片同批
    assertTrue(firstSendStarted.await(5, TimeUnit.SECONDS));

    sender.close();
    release.countDown();

    verify(recording.session, timeout(5_000)).close(any(CloseReason.class));
    assertEquals(1, sent.size(), "stop must break the remaining fragments of the in-flight batch");
    assertFalse(sender.offer("c"));
  }

  @Test
  void rejectedExecutorSubmissionsCloseSessionInline() throws Exception {
    Session session = mock(Session.class);
    when(session.getAsyncRemote()).thenReturn(mock(Async.class));
    AsyncTextSender sender =
        new AsyncTextSender(
            session, newLink(limits(8, 32)), new RejectingExecutor(), SEND_TIMEOUT_MILLIS);

    assertTrue(sender.offer("a"));

    verify(session, timeout(5_000)).close(any(CloseReason.class));
    assertFalse(sender.offer("b"));
  }

  @Test
  void closeToleratesSessionCloseFailure() throws Exception {
    ManualExecutor manual = new ManualExecutor();
    Session session = mock(Session.class);
    when(session.getAsyncRemote()).thenReturn(mock(Async.class));
    doThrow(new IllegalStateException("already closed"))
        .when(session)
        .close(any(CloseReason.class));
    AsyncTextSender sender =
        new AsyncTextSender(session, newLink(limits(8, 32)), manual, SEND_TIMEOUT_MILLIS);

    sender.close();
    manual.runAll();

    assertFalse(sender.offer("after"));
  }

  @Test
  void sendsOnePhysicalFrameAtATimeAndHoldsWholePacketBudgetUntilFinalCallback() throws Exception {
    ControlledSession controlled = controlledSession();
    NotificationPeerLink link = newLink(limits(8, 32));
    AsyncTextSender sender =
        new AsyncTextSender(controlled.session, link, sendExecutor, SEND_TIMEOUT_MILLIS);

    assertTrue(sender.offer("a".repeat(15_000))); // 3 片
    assertTrue(await(() -> controlled.sent.size() == 1));
    Thread.sleep(100);
    assertEquals(1, controlled.sent.size(), "no next frame before the in-flight callback returns");
    assertEquals(15_000, link.pendingBytes(), "the whole in-flight packet stays reserved");

    controlled.completeNext(true);
    assertTrue(await(() -> controlled.sent.size() == 2));
    assertEquals(15_000, link.pendingBytes());

    controlled.completeNext(true);
    assertTrue(await(() -> controlled.sent.size() == 3));
    assertEquals(
        15_000, link.pendingBytes(), "budget released only after the final frame callback");

    controlled.completeNext(true);
    assertTrue(await(() -> link.pendingBytes() == 0));
  }

  @Test
  void eightMiBPacketBudgetIsHeldUntilFinalCallback() throws Exception {
    ControlledSession controlled = controlledSession();
    NotificationPeerLink link = newLink(NotificationLimits.defaults());
    AsyncTextSender sender =
        new AsyncTextSender(controlled.session, link, sendExecutor, SEND_TIMEOUT_MILLIS);

    int bodyBytes = NotificationLimits.DEFAULT_MAX_MESSAGE_BYTES; // 8 MiB 整包
    assertTrue(sender.offer("x".repeat(bodyBytes)));
    int total = NotificationCarrier.count(bodyBytes);

    assertTrue(await(() -> controlled.sent.size() == 1));
    for (int index = 0; index < total; index++) {
      int sentCount = index + 1;
      assertTrue(await(() -> controlled.sent.size() == sentCount));
      if (index < total - 1) {
        assertEquals(
            bodyBytes, link.pendingBytes(), "the whole packet stays reserved until the end");
      }
      controlled.completeNext(true);
    }
    assertTrue(await(() -> link.pendingBytes() == 0));
  }

  @Test
  void failureCallbackReleasesEverythingAndIgnoresRepeatedCallback() throws Exception {
    ControlledSession controlled = controlledSession();
    NotificationPeerLink link = newLink(limits(8, 32));
    AsyncTextSender sender =
        new AsyncTextSender(controlled.session, link, sendExecutor, SEND_TIMEOUT_MILLIS);

    assertTrue(sender.offer("a".repeat(15_000)));
    assertTrue(await(() -> controlled.sent.size() == 1));

    controlled.completeNext(false);
    verify(controlled.session, timeout(5_000)).close(any(CloseReason.class));
    assertFalse(sender.offer("b"));
    assertTrue(link.isClosed(), "a native failure must release the whole link");

    // 迟到/重复回调由 batch+index 身份守卫丢弃：不发新帧、不抛异常。
    controlled.replay(0, true);
    Thread.sleep(50);
    assertEquals(1, controlled.sent.size());
  }

  @Test
  void closeDuringInFlightDelayedSendIgnoresLateCallback() throws Exception {
    ControlledSession controlled = controlledSession();
    AsyncTextSender sender =
        new AsyncTextSender(
            controlled.session, newLink(limits(8, 32)), sendExecutor, SEND_TIMEOUT_MILLIS);

    assertTrue(sender.offer("a".repeat(15_000)));
    assertTrue(await(() -> controlled.sent.size() == 1));

    sender.close();
    controlled.completeNext(true);
    Thread.sleep(50);
    assertEquals(1, controlled.sent.size(), "a closed sender must not send further frames");
    verify(controlled.session, timeout(5_000)).close(any(CloseReason.class));
    assertFalse(sender.offer("b"));
  }

  @Test
  void failAbortsWithoutDiscardingInFlightWhenErrorFrameCannotFit() throws Exception {
    ControlledSession controlled = controlledSession();
    NotificationPeerLink link = newLink(smallLimits()); // 逻辑整包上限仅 20 字节
    AsyncTextSender sender =
        new AsyncTextSender(controlled.session, link, sendExecutor, SEND_TIMEOUT_MILLIS);

    assertTrue(sender.offer("0123456789")); // 一帧在途
    assertTrue(await(() -> controlled.sent.size() == 1));

    // error 帧本身超预算：不得先丢弃在途逻辑包再另放无预算帧，只能放弃并直接关闭。
    sender.fail("x".repeat(100), CloseReason.CloseCodes.VIOLATED_POLICY);

    verify(controlled.session, timeout(5_000)).close(any(CloseReason.class));
    assertTrue(link.isClosed(), "abort must release the whole link");
    assertFalse(sender.offer("after"));
    controlled.completeNext(true); // 迟到回调安全
    Thread.sleep(50);
    assertEquals(1, controlled.sent.size());
  }

  private NotificationPeerLink newLink(NotificationLimits limits) {
    return new NotificationPeerLink(SELF, TOPIC, limits, timer, body -> {}, () -> {});
  }

  private RecordingAsync recordingSession(boolean failSends, Blocker blocker) {
    Async async = mock(Async.class);
    Session session = mock(Session.class);
    doAnswer(
            invocation -> {
              String text = invocation.getArgument(0);
              SendHandler handler = invocation.getArgument(1);
              sent.add(text);
              if (blocker != null && sent.size() == 1) {
                blocker.started.countDown();
                blocker.release.await(5, TimeUnit.SECONDS);
              }
              if (failSends) {
                handler.onResult(new SendResult(new IllegalStateException("broken pipe")));
              } else {
                handler.onResult(new SendResult());
              }
              return null;
            })
        .when(async)
        .sendText(any(String.class), any(SendHandler.class));
    when(session.getAsyncRemote()).thenReturn(async);
    return new RecordingAsync(session);
  }

  /** 延迟回调的 Async mock：sendText 只登记物理帧与回调，测试显式 complete 才推进，用于验证每帧一次在途与预算时机。 */
  private ControlledSession controlledSession() {
    Async async = mock(Async.class);
    Session session = mock(Session.class);
    ControlledSession controlled = new ControlledSession(session);
    doAnswer(
            invocation -> {
              controlled.sent.add(invocation.getArgument(0));
              controlled.handlers.add(invocation.getArgument(1));
              return null;
            })
        .when(async)
        .sendText(any(String.class), any(SendHandler.class));
    when(session.getAsyncRemote()).thenReturn(async);
    return controlled;
  }

  private NotificationCarrier decode(String frame) {
    NotificationCarrier carrier =
        NotificationCarrier.decode(frame, NotificationLimits.defaults(), DECODER_SELF);
    assertNotNull(carrier);
    return carrier;
  }

  private static NotificationLimits limits(int queueCapacity, int sendBatchFrames) {
    return new NotificationLimits(
        20_000, 20_000, queueCapacity, 20_000, 8, Duration.ofSeconds(5), sendBatchFrames);
  }

  /** 逻辑整包上限仅 20 字节：用于验证 error 帧本身超预算时只能直接关闭。 */
  private static NotificationLimits smallLimits() {
    return new NotificationLimits(20, 20, 8, 20, 8, Duration.ofSeconds(5), 32);
  }

  private static boolean await(BooleanSupplier condition) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (System.nanoTime() < deadline) {
      if (condition.getAsBoolean()) {
        return true;
      }
      Thread.sleep(5);
    }
    return condition.getAsBoolean();
  }

  private record RecordingAsync(Session session) {}

  private record Blocker(CountDownLatch started, CountDownLatch release) {}

  /** 延迟回调载体：登记每次 native send 的帧与回调，测试显式 completeNext/replay 才推进或重放。 */
  private static final class ControlledSession {
    private final Session session;
    private final List<String> sent = new CopyOnWriteArrayList<>();
    private final List<SendHandler> handlers = new CopyOnWriteArrayList<>();
    private int next;

    private ControlledSession(Session session) {
      this.session = session;
    }

    synchronized void completeNext(boolean ok) {
      assertTrue(next < handlers.size(), "expected an in-flight send callback");
      handlers.get(next++).onResult(result(ok));
    }

    void replay(int index, boolean ok) {
      handlers.get(index).onResult(result(ok));
    }

    private static SendResult result(boolean ok) {
      return ok ? new SendResult() : new SendResult(new IllegalStateException("broken pipe"));
    }
  }

  /** 手动 ExecutorService：任务排队由测试显式运行，保证 drain/close 顺序确定。 */
  private static final class ManualExecutor extends AbstractExecutorService {
    private final Deque<Runnable> tasks = new ArrayDeque<>();
    private boolean shutdown;

    @Override
    public void execute(Runnable command) {
      tasks.add(command);
    }

    @Override
    public void shutdown() {
      shutdown = true;
    }

    @Override
    public List<Runnable> shutdownNow() {
      shutdown = true;
      List<Runnable> pending = new ArrayList<>(tasks);
      tasks.clear();
      return pending;
    }

    @Override
    public boolean isShutdown() {
      return shutdown;
    }

    @Override
    public boolean isTerminated() {
      return shutdown && tasks.isEmpty();
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) {
      return tasks.isEmpty();
    }

    private void runAll() {
      while (!tasks.isEmpty()) {
        tasks.poll().run();
      }
    }
  }

  /** 始终拒绝提交的 ExecutorService：验证提交失败时收敛到唯一关闭路径。 */
  private static final class RejectingExecutor extends AbstractExecutorService {

    @Override
    public void execute(Runnable command) {
      throw new RejectedExecutionException("rejected");
    }

    @Override
    public void shutdown() {}

    @Override
    public List<Runnable> shutdownNow() {
      return List.of();
    }

    @Override
    public boolean isShutdown() {
      return true;
    }

    @Override
    public boolean isTerminated() {
      return true;
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) {
      return true;
    }
  }
}
