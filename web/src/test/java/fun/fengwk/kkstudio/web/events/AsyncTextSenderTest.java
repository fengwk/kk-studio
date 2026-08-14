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

/** AsyncTextSender：串行发送链、有界队列、溢出/失败关闭与 error 帧兜底。 */
class AsyncTextSenderTest {

  @Test
  void sendsFramesStrictlySeriallyInEnqueueOrder() {
    FakeAsyncRemote remote = new FakeAsyncRemote();
    AsyncTextSender sender = new AsyncTextSender(session(remote), 8);

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
    AsyncTextSender sender = new AsyncTextSender(session(remote), 2);

    assertTrue(sender.enqueue("a")); // in-flight
    assertTrue(sender.enqueue("b")); // queued
    assertFalse(sender.enqueue("c"), "queue is full");
    assertFalse(sender.enqueue("d"));
  }

  @Test
  void failFlushesErrorFrameThenClosesSession() throws Exception {
    FakeAsyncRemote remote = new FakeAsyncRemote();
    Session session = session(remote);
    AsyncTextSender sender = new AsyncTextSender(session, 4);

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
    AsyncTextSender sender = new AsyncTextSender(session, 4);

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
    AsyncTextSender sender = new AsyncTextSender(session, 4);

    sender.fail("{\"type\":\"error\",\"message\":\"bad\"}", CloseReason.CloseCodes.VIOLATED_POLICY);
    assertEquals(1, remote.sent.size());
    assertEquals("{\"type\":\"error\",\"message\":\"bad\"}", remote.sent.get(0).text);
    remote.complete(0);
    verify(session).close(any(CloseReason.class));
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
