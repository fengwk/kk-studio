package fun.fengwk.kkstudio.web.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.eclipse.jetty.websocket.api.Callback;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** AsyncTextSender：Jetty 原生异步串行发送链、有界队列、显式截止时间超时、ping/文本按序互斥不并发、溢出/失败关闭与 error 帧兜底。 */
class AsyncTextSenderTest {

  /** 与 SystemSettings.Advanced 默认一致的仓库级发送软策略（测试用显式值，不依赖生产默认常量）。 */
  private static final long DEFAULT_MAX_BYTES = 2L * 1024 * 1024;

  private static final long SEND_TIMEOUT_MILLIS = 10_000L;

  private ScheduledExecutorService scheduler;

  @BeforeEach
  void setUp() {
    scheduler = Executors.newSingleThreadScheduledExecutor();
  }

  @AfterEach
  void tearDown() {
    scheduler.shutdownNow();
  }

  @Test
  void constructorRejectsNullSchedulerOrNonPositiveLimits() {
    Session session = mock(Session.class);
    assertThrows(
        NullPointerException.class,
        () -> new AsyncTextSender(null, 4, DEFAULT_MAX_BYTES, SEND_TIMEOUT_MILLIS, scheduler));
    assertThrows(
        NullPointerException.class,
        () -> new AsyncTextSender(session, 4, DEFAULT_MAX_BYTES, SEND_TIMEOUT_MILLIS, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> new AsyncTextSender(session, 0, DEFAULT_MAX_BYTES, SEND_TIMEOUT_MILLIS, scheduler));
    assertThrows(
        IllegalArgumentException.class,
        () -> new AsyncTextSender(session, 4, 0L, SEND_TIMEOUT_MILLIS, scheduler));
    assertThrows(
        IllegalArgumentException.class,
        () -> new AsyncTextSender(session, 4, DEFAULT_MAX_BYTES, 0L, scheduler));
  }

  @Test
  void sendsFramesStrictlySeriallyInEnqueueOrder() {
    FakeJettySession fake = new FakeJettySession();
    AsyncTextSender sender =
        new AsyncTextSender(fake.session, 8, DEFAULT_MAX_BYTES, SEND_TIMEOUT_MILLIS, scheduler);

    assertTrue(sender.enqueue("a"));
    assertTrue(sender.enqueue("b"));
    assertTrue(sender.enqueue("c"));

    assertEquals(1, fake.calls.size(), "only the first frame may be in flight");
    assertEquals("a", fake.calls.get(0).text);
    assertNotNull(fake.calls.get(0).callback, "a callback must be attached");

    fake.complete(0);
    assertEquals(2, fake.calls.size());
    assertEquals("b", fake.calls.get(1).text);

    fake.complete(1);
    assertEquals(3, fake.calls.size());
    assertEquals("c", fake.calls.get(2).text);

    fake.complete(2);
    assertEquals(3, fake.calls.size(), "nothing further after queue drains");
  }

  @Test
  void textDoesNotConcurrentlySendWhenPingIsInFlight() {
    FakeJettySession fake = new FakeJettySession();
    AsyncTextSender sender =
        new AsyncTextSender(fake.session, 4, DEFAULT_MAX_BYTES, SEND_TIMEOUT_MILLIS, scheduler);

    assertTrue(sender.enqueuePing());
    assertTrue(sender.enqueue("text-after-ping"));

    assertEquals(1, fake.calls.size(), "only the ping frame must be in flight");
    assertTrue(fake.calls.get(0).isPing(), "first in-flight call must be ping");

    // ping 尚未完成时，后续文本绝不能并发发出
    assertEquals(1, fake.calls.size());

    // ping 完成回调驱动下一帧文本发出
    fake.complete(0);
    assertEquals(2, fake.calls.size(), "text frame sends only after ping completes");
    assertFalse(fake.calls.get(1).isPing());
    assertEquals("text-after-ping", fake.calls.get(1).text);

    fake.complete(1);
  }

  @Test
  void enqueueRejectsWhenQueueIsFull() {
    FakeJettySession fake = new FakeJettySession();
    AsyncTextSender sender =
        new AsyncTextSender(fake.session, 2, DEFAULT_MAX_BYTES, SEND_TIMEOUT_MILLIS, scheduler);

    assertTrue(sender.enqueue("a")); // in-flight
    assertTrue(sender.enqueue("b")); // queued
    assertFalse(sender.enqueue("c"), "queue is full");
    assertFalse(sender.enqueue("d"));
  }

  @Test
  void failFlushesErrorFrameThenClosesSession() {
    FakeJettySession fake = new FakeJettySession();
    AsyncTextSender sender =
        new AsyncTextSender(fake.session, 4, DEFAULT_MAX_BYTES, SEND_TIMEOUT_MILLIS, scheduler);

    sender.enqueue("a");
    sender.fail("{\"type\":\"error\",\"message\":\"boom\"}", StatusCode.POLICY_VIOLATION);

    assertEquals(1, fake.calls.size(), "in-flight frame completes first");
    fake.complete(0);
    assertEquals(2, fake.calls.size());
    assertEquals("{\"type\":\"error\",\"message\":\"boom\"}", fake.calls.get(1).text);
    fake.complete(1);

    ArgumentCaptor<Integer> codeCaptor = ArgumentCaptor.forClass(Integer.class);
    verify(fake.session).close(codeCaptor.capture(), any(String.class), any(Callback.class));
    assertEquals(StatusCode.POLICY_VIOLATION, codeCaptor.getValue());
    assertFalse(sender.enqueue("x"), "failed sender rejects further enqueues");
  }

  @Test
  void failedSendClosesSessionAndStopsTheChain() {
    FakeJettySession fake = new FakeJettySession();
    AsyncTextSender sender =
        new AsyncTextSender(fake.session, 4, DEFAULT_MAX_BYTES, SEND_TIMEOUT_MILLIS, scheduler);

    sender.enqueue("a");
    sender.enqueue("b");
    fake.fail(0, new IllegalStateException("broken pipe"));

    verify(fake.session).close(eq(StatusCode.SERVER_ERROR), any(String.class), any(Callback.class));
    assertEquals(1, fake.calls.size(), "chain must stop after send failure");
    assertFalse(sender.enqueue("c"));
  }

  @Test
  void failingWithoutInFlightSendStillEmitsErrorFrameAndCloses() {
    FakeJettySession fake = new FakeJettySession();
    AsyncTextSender sender =
        new AsyncTextSender(fake.session, 4, DEFAULT_MAX_BYTES, SEND_TIMEOUT_MILLIS, scheduler);

    sender.fail("{\"type\":\"error\",\"message\":\"bad\"}", StatusCode.POLICY_VIOLATION);
    assertEquals(1, fake.calls.size());
    assertEquals("{\"type\":\"error\",\"message\":\"bad\"}", fake.calls.get(0).text);
    fake.complete(0);
    verify(fake.session)
        .close(eq(StatusCode.POLICY_VIOLATION), any(String.class), any(Callback.class));
  }

  @Test
  void synchronousSendTextExceptionClosesSessionAndStopsTheChain() {
    Session session = mock(Session.class);
    doAnswer(
            inv -> {
              throw new IllegalStateException("session already closed");
            })
        .when(session)
        .sendText(any(String.class), any(Callback.class));
    AsyncTextSender sender =
        new AsyncTextSender(session, 4, DEFAULT_MAX_BYTES, SEND_TIMEOUT_MILLIS, scheduler);

    assertTrue(sender.enqueue("a"));

    verify(session).close(eq(StatusCode.SERVER_ERROR), any(String.class), any(Callback.class));
    assertFalse(sender.enqueue("b"), "failed sender rejects further enqueues");
  }

  @Test
  void enqueueRejectsWhenTotalPendingUtf8BytesExceedMax() {
    FakeJettySession fake = new FakeJettySession();
    AsyncTextSender sender =
        new AsyncTextSender(fake.session, 8, 6L, SEND_TIMEOUT_MILLIS, scheduler);

    assertTrue(sender.enqueue("ab")); // in-flight 2B
    assertTrue(sender.enqueue("cd")); // queued 2B
    assertTrue(sender.enqueue("ef")); // queued 2B，总量 6B 恰好到限
    assertFalse(sender.enqueue("g"), "total bytes must not exceed maxBytes");
    assertFalse(sender.enqueue("h"));
  }

  @Test
  void countsUtf8BytesNotCharacters() {
    FakeJettySession fake = new FakeJettySession();
    AsyncTextSender sender =
        new AsyncTextSender(fake.session, 8, 6L, SEND_TIMEOUT_MILLIS, scheduler);

    assertTrue(sender.enqueue("中文"));
    assertFalse(sender.enqueue("x"), "multibyte frames must count UTF-8 bytes");
  }

  @Test
  void inFlightFrameCountsTowardsByteLimitUntilCompleted() {
    FakeJettySession fake = new FakeJettySession();
    AsyncTextSender sender =
        new AsyncTextSender(fake.session, 8, 6L, SEND_TIMEOUT_MILLIS, scheduler);

    assertTrue(sender.enqueue("abcd")); // in-flight 4B
    assertTrue(sender.enqueue("ef")); // queued 2B，总量 6B 到限
    assertFalse(sender.enqueue("g"));

    fake.complete(0); // in-flight 完成，queued "ef" 变为新 in-flight
    assertEquals("ef", fake.calls.get(1).text);
    assertFalse(sender.enqueue("12345"), "in-flight bytes still count until completion");

    fake.complete(1);
    assertTrue(sender.enqueue("x"), "bytes are released after the frame completes");
  }

  @Test
  void oversizedSingleFrameIsRejectedWithoutBreakingTheChain() {
    FakeJettySession fake = new FakeJettySession();
    AsyncTextSender sender =
        new AsyncTextSender(fake.session, 8, 6L, SEND_TIMEOUT_MILLIS, scheduler);

    assertFalse(sender.enqueue("abcdefg"), "a single frame above maxBytes must be rejected");
    assertTrue(sender.enqueue("ab"));
    assertEquals(1, fake.calls.size(), "rejected frames must not start or break the chain");
    fake.complete(0);
  }

  @Test
  void nativePingSharesTheSerialChainAndBoundedCapacity() {
    FakeJettySession fake = new FakeJettySession();
    AsyncTextSender sender =
        new AsyncTextSender(fake.session, 3, DEFAULT_MAX_BYTES, SEND_TIMEOUT_MILLIS, scheduler);

    assertTrue(sender.enqueue("a"));
    assertTrue(sender.enqueuePing());
    assertTrue(sender.enqueue("b"));
    assertEquals(1, fake.calls.size(), "text is the only in-flight frame");
    assertEquals("a", fake.calls.get(0).text);

    // 完成第 0 帧（文本），触发第 1 帧（ping）发送；ping 异步回调未完成前，第 2 帧绝不发出
    fake.complete(0);
    assertEquals(2, fake.calls.size(), "ping uses the same serial drain");
    assertTrue(fake.calls.get(1).isPing(), "second frame must be ping");

    // ping 完成回调驱动第 2 帧（文本）发送
    fake.complete(1);
    assertEquals(3, fake.calls.size(), "text after ping is sent strictly after ping completes");
    assertEquals("b", fake.calls.get(2).text);
    fake.complete(2);
  }

  @Test
  void pingCountsAgainstTheQueueEvenWithZeroBytes() {
    FakeJettySession fake = new FakeJettySession();
    AsyncTextSender sender =
        new AsyncTextSender(fake.session, 2, DEFAULT_MAX_BYTES, SEND_TIMEOUT_MILLIS, scheduler);

    assertTrue(sender.enqueue("a")); // in-flight
    assertTrue(sender.enqueuePing()); // queued
    assertFalse(sender.enqueuePing(), "ping consumes a queue slot");
  }

  @Test
  void pingFailureClosesTheSharedWriterAndStopsTheChain() {
    FakeJettySession fake = new FakeJettySession();
    AsyncTextSender sender =
        new AsyncTextSender(fake.session, 4, DEFAULT_MAX_BYTES, SEND_TIMEOUT_MILLIS, scheduler);

    assertTrue(sender.enqueue("a"));
    assertTrue(sender.enqueuePing());
    assertTrue(sender.enqueue("b"));
    fake.complete(0); // 文本完成，触发 ping

    fake.fail(1, new IOException("ping failed")); // ping 失败回调

    verify(fake.session).close(eq(StatusCode.SERVER_ERROR), any(String.class), any(Callback.class));
    assertEquals(2, fake.calls.size(), "text after the failed ping must not be sent");
    assertFalse(sender.enqueue("c"));
  }

  @Test
  void synchronousSendPingExceptionClosesSessionAndStopsTheChain() {
    Session session = mock(Session.class);
    doAnswer(
            inv -> {
              throw new IllegalStateException("ping failed synchronously");
            })
        .when(session)
        .sendPing(any(ByteBuffer.class), any(Callback.class));
    AsyncTextSender sender =
        new AsyncTextSender(session, 4, DEFAULT_MAX_BYTES, SEND_TIMEOUT_MILLIS, scheduler);

    assertTrue(sender.enqueuePing());

    verify(session).close(eq(StatusCode.SERVER_ERROR), any(String.class), any(Callback.class));
    assertFalse(sender.enqueue("b"), "failed sender rejects further enqueues");
  }

  @Test
  void sendTimeoutClosesConnectionAndLateCallbackDoesNotDriveFurtherSends() throws Exception {
    FakeJettySession fake = new FakeJettySession();
    // 设置 50ms 超时
    AsyncTextSender sender =
        new AsyncTextSender(fake.session, 4, DEFAULT_MAX_BYTES, 50L, scheduler);

    assertTrue(sender.enqueue("slow-frame"));
    assertTrue(sender.enqueue("queued-frame"));
    assertEquals(1, fake.calls.size());
    assertFalse(sender.isFailed());

    // 不调用 fake.complete(0)，等待 50ms 超时触发
    Thread.sleep(120);

    assertTrue(sender.isFailed(), "sender must be marked failed after timeout");
    verify(fake.session).close(eq(StatusCode.SERVER_ERROR), any(String.class), any(Callback.class));
    assertFalse(sender.enqueue("next"), "new enqueues must be rejected after timeout");

    // 迟到的 succeed 回调绝不能驱动发送队列中的下一帧（"queued-frame"）
    fake.complete(0);
    assertEquals(1, fake.calls.size(), "late callback must not drive further sends");

    // 迟到的 fail 回调也安全忽略
    fake.fail(0, new IOException("late network failure"));
    assertEquals(1, fake.calls.size());
  }

  @Test
  void schedulerExceptionImmediatelyFailsSendWithoutSwallowing() {
    ScheduledExecutorService brokenScheduler = mock(ScheduledExecutorService.class);
    when(brokenScheduler.schedule(any(Runnable.class), any(long.class), any(TimeUnit.class)))
        .thenThrow(new RejectedExecutionException("scheduler shut down"));

    FakeJettySession fake = new FakeJettySession();
    AsyncTextSender sender =
        new AsyncTextSender(
            fake.session, 4, DEFAULT_MAX_BYTES, SEND_TIMEOUT_MILLIS, brokenScheduler);

    assertTrue(sender.enqueue("frame"));
    assertTrue(sender.isFailed(), "sender must fail when scheduler rejects task");
    verify(fake.session).close(eq(StatusCode.SERVER_ERROR), any(String.class), any(Callback.class));
    assertEquals(0, fake.calls.size(), "frame must not be sent when scheduling fails");
  }

  @Test
  void discardDropsQueuedFramesAndStopsTheChain() {
    FakeJettySession fake = new FakeJettySession();
    AsyncTextSender sender =
        new AsyncTextSender(fake.session, 4, DEFAULT_MAX_BYTES, SEND_TIMEOUT_MILLIS, scheduler);

    assertTrue(sender.enqueue("a"));
    assertTrue(sender.enqueue("b"));
    sender.discard();
    fake.complete(0);

    assertEquals(1, fake.calls.size(), "queued frames must be released on discard");
    assertFalse(sender.enqueue("c"), "discard must stop further enqueues");
  }

  private static final class OutboundCall {
    private final String text;
    private final Callback callback;

    private OutboundCall(String text, Callback callback) {
      this.text = text;
      this.callback = callback;
    }

    private boolean isPing() {
      return text == null;
    }
  }

  private static final class FakeJettySession {
    private final Session session = mock(Session.class);
    private final List<OutboundCall> calls = new ArrayList<>();
    private final AtomicInteger inFlight = new AtomicInteger(0);

    private FakeJettySession() {
      doAnswer(
              inv -> {
                int current = inFlight.incrementAndGet();
                if (current > 1) {
                  throw new AssertionError("Concurrent write detected: inFlight = " + current);
                }
                String text = inv.getArgument(0);
                Callback callback = inv.getArgument(1);
                calls.add(new OutboundCall(text, callback));
                return null;
              })
          .when(session)
          .sendText(any(String.class), any(Callback.class));

      doAnswer(
              inv -> {
                int current = inFlight.incrementAndGet();
                if (current > 1) {
                  throw new AssertionError("Concurrent write detected: inFlight = " + current);
                }
                Callback callback = inv.getArgument(1);
                calls.add(new OutboundCall(null, callback));
                return null;
              })
          .when(session)
          .sendPing(any(ByteBuffer.class), any(Callback.class));
    }

    private void complete(int index) {
      inFlight.decrementAndGet();
      assertNotNull(calls.get(index).callback, "no callback for frame " + index);
      calls.get(index).callback.succeed();
    }

    private void fail(int index, Throwable error) {
      inFlight.decrementAndGet();
      calls.get(index).callback.fail(error);
    }
  }
}
