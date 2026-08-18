package fun.fengwk.kkstudio.web.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.websocket.CloseReason;
import jakarta.websocket.RemoteEndpoint.Async;
import jakarta.websocket.SendHandler;
import jakarta.websocket.SendResult;
import jakarta.websocket.Session;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;

/** AsyncTextSender：串行发送链、有界队列、send timeout、溢出/失败关闭与 error 帧兜底。 */
class AsyncTextSenderTest {

  /** 与 SystemSettings.Advanced 默认一致的仓库级发送软策略（测试用显式值，不依赖生产默认常量）。 */
  private static final long DEFAULT_MAX_BYTES = 2L * 1024 * 1024;

  private static final long SEND_TIMEOUT_MILLIS = 10_000L;

  @Test
  void setsBoundedSendTimeoutOnTheAsyncRemote() {
    Async async = mock(Async.class);
    Session session = mock(Session.class);
    when(session.getAsyncRemote()).thenReturn(async);

    new AsyncTextSender(session, 4, DEFAULT_MAX_BYTES, SEND_TIMEOUT_MILLIS);

    verify(async).setSendTimeout(SEND_TIMEOUT_MILLIS);
  }

  @Test
  void sendsFramesStrictlySeriallyInEnqueueOrder() {
    FakeAsyncRemote remote = new FakeAsyncRemote();
    AsyncTextSender sender =
        new AsyncTextSender(session(remote), 8, DEFAULT_MAX_BYTES, SEND_TIMEOUT_MILLIS);

    assertTrue(sender.enqueue("a"));
    assertTrue(sender.enqueue("b"));
    assertTrue(sender.enqueue("c"));

    assertEquals(1, remote.sent.size(), "only the first frame may be in flight");
    assertEquals("a", remote.sent.get(0).text);
    assertNotNull(remote.sent.get(0).handler, "a send handler must be attached");

    remote.complete(0);
    assertEquals(2, remote.sent.size());
    assertEquals("b", remote.sent.get(1).text);

    remote.complete(1);
    assertEquals(3, remote.sent.size());
    assertEquals("c", remote.sent.get(2).text);

    remote.complete(2);
    assertEquals(3, remote.sent.size(), "nothing further after queue drains");
  }

  @Test
  void enqueueRejectsWhenQueueIsFull() {
    FakeAsyncRemote remote = new FakeAsyncRemote();
    AsyncTextSender sender =
        new AsyncTextSender(session(remote), 2, DEFAULT_MAX_BYTES, SEND_TIMEOUT_MILLIS);

    assertTrue(sender.enqueue("a")); // in-flight
    assertTrue(sender.enqueue("b")); // queued
    assertFalse(sender.enqueue("c"), "queue is full");
    assertFalse(sender.enqueue("d"));
  }

  @Test
  void failFlushesErrorFrameThenClosesSession() throws Exception {
    FakeAsyncRemote remote = new FakeAsyncRemote();
    Session session = session(remote);
    AsyncTextSender sender =
        new AsyncTextSender(session, 4, DEFAULT_MAX_BYTES, SEND_TIMEOUT_MILLIS);

    sender.enqueue("a");
    sender.fail(
        "{\"type\":\"error\",\"message\":\"boom\"}", CloseReason.CloseCodes.VIOLATED_POLICY);

    assertEquals(1, remote.sent.size(), "in-flight frame completes first");
    remote.complete(0);
    assertEquals(2, remote.sent.size());
    assertEquals("{\"type\":\"error\",\"message\":\"boom\"}", remote.sent.get(1).text);
    remote.complete(1);

    ArgumentCaptor<CloseReason> reasonCaptor = ArgumentCaptor.forClass(CloseReason.class);
    verify(session).close(reasonCaptor.capture());
    assertEquals(CloseReason.CloseCodes.VIOLATED_POLICY, reasonCaptor.getValue().getCloseCode());
    assertFalse(sender.enqueue("x"), "failed sender rejects further enqueues");
  }

  @Test
  void failedSendClosesSessionAndStopsTheChain() throws Exception {
    FakeAsyncRemote remote = new FakeAsyncRemote();
    Session session = session(remote);
    AsyncTextSender sender =
        new AsyncTextSender(session, 4, DEFAULT_MAX_BYTES, SEND_TIMEOUT_MILLIS);

    sender.enqueue("a");
    sender.enqueue("b");
    remote.fail(0, new IllegalStateException("broken pipe"));

    verify(session).close(any(CloseReason.class));
    assertEquals(1, remote.sent.size(), "chain must stop after send failure");
    assertFalse(sender.enqueue("c"));
  }

  @Test
  void failingWithoutInFlightSendStillEmitsErrorFrameAndCloses() throws Exception {
    FakeAsyncRemote remote = new FakeAsyncRemote();
    Session session = session(remote);
    AsyncTextSender sender =
        new AsyncTextSender(session, 4, DEFAULT_MAX_BYTES, SEND_TIMEOUT_MILLIS);

    sender.fail("{\"type\":\"error\",\"message\":\"bad\"}", CloseReason.CloseCodes.VIOLATED_POLICY);
    assertEquals(1, remote.sent.size());
    assertEquals("{\"type\":\"error\",\"message\":\"bad\"}", remote.sent.get(0).text);
    remote.complete(0);
    verify(session).close(any(CloseReason.class));
  }

  @Test
  void synchronousSendTextExceptionClosesSessionAndStopsTheChain() throws Exception {
    Async async = mock(Async.class);
    doAnswer(
            inv -> {
              throw new IllegalStateException("session already closed");
            })
        .when(async)
        .sendText(any(String.class), any(SendHandler.class));
    Session session = mock(Session.class);
    when(session.getAsyncRemote()).thenReturn(async);
    AsyncTextSender sender =
        new AsyncTextSender(session, 4, DEFAULT_MAX_BYTES, SEND_TIMEOUT_MILLIS);

    assertTrue(sender.enqueue("a"));

    ArgumentCaptor<CloseReason> reasonCaptor = ArgumentCaptor.forClass(CloseReason.class);
    verify(session).close(reasonCaptor.capture());
    assertEquals(
        CloseReason.CloseCodes.UNEXPECTED_CONDITION, reasonCaptor.getValue().getCloseCode());
    assertFalse(sender.enqueue("b"), "failed sender rejects further enqueues");
  }

  @Test
  void enqueueRejectsWhenTotalPendingUtf8BytesExceedMax() {
    // maxBytes=6：三帧 2 字节各入队后，第四帧使总量 7 > 6 被拒；在途帧（2B）计入上限。
    FakeAsyncRemote remote = new FakeAsyncRemote();
    AsyncTextSender sender = new AsyncTextSender(session(remote), 8, 6L, SEND_TIMEOUT_MILLIS);

    assertTrue(sender.enqueue("ab")); // in-flight 2B
    assertTrue(sender.enqueue("cd")); // queued 2B
    assertTrue(sender.enqueue("ef")); // queued 2B，总量 6B 恰好到限
    assertFalse(sender.enqueue("g"), "total bytes must not exceed maxBytes");
    assertFalse(sender.enqueue("h"));
  }

  @Test
  void countsUtf8BytesNotCharacters() {
    // "中文" 是 2 个字符、6 个 UTF-8 字节；maxBytes=6 时单帧通过、再加 1 字节帧即拒绝。
    FakeAsyncRemote remote = new FakeAsyncRemote();
    AsyncTextSender sender = new AsyncTextSender(session(remote), 8, 6L, SEND_TIMEOUT_MILLIS);

    assertTrue(sender.enqueue("中文"));
    assertFalse(sender.enqueue("x"), "multibyte frames must count UTF-8 bytes");
  }

  @Test
  void inFlightFrameCountsTowardsByteLimitUntilCompleted() {
    FakeAsyncRemote remote = new FakeAsyncRemote();
    AsyncTextSender sender = new AsyncTextSender(session(remote), 8, 6L, SEND_TIMEOUT_MILLIS);

    assertTrue(sender.enqueue("abcd")); // in-flight 4B
    assertTrue(sender.enqueue("ef")); // queued 2B，总量 6B 到限
    assertFalse(sender.enqueue("g"));

    remote.complete(0); // in-flight 完成，queued "ef" 变为新 in-flight
    assertEquals("ef", remote.sent.get(1).text);
    assertFalse(sender.enqueue("12345"), "in-flight bytes still count until completion");

    remote.complete(1);
    assertTrue(sender.enqueue("x"), "bytes are released after the frame completes");
  }

  @Test
  void oversizedSingleFrameIsRejectedWithoutBreakingTheChain() {
    FakeAsyncRemote remote = new FakeAsyncRemote();
    AsyncTextSender sender = new AsyncTextSender(session(remote), 8, 6L, SEND_TIMEOUT_MILLIS);

    assertFalse(sender.enqueue("abcdefg"), "a single frame above maxBytes must be rejected");
    assertTrue(sender.enqueue("ab"));
    assertEquals(1, remote.sent.size(), "rejected frames must not start or break the chain");
    remote.complete(0);
  }

  private static Session session(FakeAsyncRemote remote) {
    Async async = mock(Async.class);
    doAnswer(
            inv -> {
              String text = inv.getArgument(0);
              SendHandler handler = inv.getArgument(1);
              remote.sent.add(new Sent(text, handler));
              return null;
            })
        .when(async)
        .sendText(any(String.class), any(SendHandler.class));
    Session session = mock(Session.class);
    when(session.getAsyncRemote()).thenReturn(async);
    return session;
  }

  private static final class Sent {
    private final String text;
    private final SendHandler handler;

    private Sent(String text, SendHandler handler) {
      this.text = text;
      this.handler = handler;
    }
  }

  private static final class FakeAsyncRemote {
    private final List<Sent> sent = new ArrayList<>();

    private void complete(int index) {
      assertNotNull(sent.get(index).handler, "no handler recorded for frame " + index);
      sent.get(index).handler.onResult(new SendResult());
    }

    private void fail(int index, Throwable error) {
      sent.get(index).handler.onResult(new SendResult(error));
    }
  }
}
