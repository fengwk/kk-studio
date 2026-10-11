package fun.fengwk.kkstudio.web.environment.ide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.web.socket.WebSocketSession;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** 反向流 peer 的有界入站队列与关闭唤醒契约。 */
class WebSocketIdeStreamPeerTest {

  private final WebSocketSession session = mock(WebSocketSession.class);

  /** 空闲超时不是关闭：poll 返回 null 但 closed() 仍为 false，数据面可继续等到真实 deadline。 */
  @Test
  void idleTimeoutIsNotClosed() throws Exception {
    WebSocketIdeStreamPeer peer = new WebSocketIdeStreamPeer(session);
    assertNull(peer.poll(Duration.ofMillis(20)));
    assertFalse(peer.closed());
    assertNull(peer.poll(Duration.ZERO));
    assertFalse(peer.closed());
  }

  /** 帧数超界即拒绝入队，绝不无界缓冲。 */
  @Test
  void rejectsBeyondFrameBudget() {
    WebSocketIdeStreamPeer peer = new WebSocketIdeStreamPeer(session);
    for (int i = 0; i < WebSocketIdeStreamPeer.MAX_QUEUED_FRAMES; i++) {
      assertTrue(peer.offer(new IdeStreamPeer.Inbound.Text("{}")), "frame " + i + " must fit");
    }
    assertFalse(peer.offer(new IdeStreamPeer.Inbound.Text("{}")));
  }

  /** 小零载荷控制帧同样受字节预算约束：恶意 ACK/text 洪水不能无限占用内存。 */
  @Test
  void rejectsBeyondByteBudget() {
    WebSocketIdeStreamPeer peer = new WebSocketIdeStreamPeer(session);
    byte[] payload = new byte[4096];
    int accepted = 0;
    while (peer.offer(new IdeStreamPeer.Inbound.Binary(payload))) {
      accepted++;
      assertTrue(accepted <= WebSocketIdeStreamPeer.MAX_QUEUED_BYTES / payload.length + 1);
    }
    assertEquals((int) (WebSocketIdeStreamPeer.MAX_QUEUED_BYTES / payload.length), accepted);
  }

  /** 被消费的帧释放字节预算，可继续入队。 */
  @Test
  void consumingFramesReleasesBudget() throws Exception {
    WebSocketIdeStreamPeer peer = new WebSocketIdeStreamPeer(session);
    byte[] payload = new byte[4096];
    for (int i = 0; i < WebSocketIdeStreamPeer.MAX_QUEUED_BYTES / payload.length; i++) {
      assertTrue(peer.offer(new IdeStreamPeer.Inbound.Binary(payload)));
    }
    assertFalse(peer.offer(new IdeStreamPeer.Inbound.Binary(payload)));
    assertEquals(
        payload.length, ((IdeStreamPeer.Inbound.Binary) peer.poll(Duration.ZERO)).frame().length);
    assertTrue(peer.offer(new IdeStreamPeer.Inbound.Binary(payload)));
  }

  /** 关闭必须立即唤醒等待者，而不是让数据面等满超时。 */
  @Test
  void closeWakesBlockedPoll() throws Exception {
    WebSocketIdeStreamPeer peer = new WebSocketIdeStreamPeer(session);
    CountDownLatch started = new CountDownLatch(1);
    AtomicBoolean returnedNull = new AtomicBoolean();
    Thread waiter =
        Thread.ofVirtual()
            .start(
                () -> {
                  started.countDown();
                  try {
                    returnedNull.set(peer.poll(Duration.ofSeconds(30)) == null);
                  } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                  }
                });
    assertTrue(started.await(5, TimeUnit.SECONDS));
    Thread.sleep(50);
    peer.close();
    waiter.join(5_000);
    assertFalse(waiter.isAlive(), "close must wake the blocked poll");
    assertTrue(returnedNull.get());
    assertTrue(peer.closed());
  }

  /** 连接侧关闭同样唤醒等待者。 */
  @Test
  void transportCloseWakesBlockedPoll() throws Exception {
    WebSocketIdeStreamPeer peer = new WebSocketIdeStreamPeer(session);
    Thread waiter =
        Thread.ofVirtual()
            .start(
                () -> {
                  try {
                    peer.poll(Duration.ofSeconds(30));
                  } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                  }
                });
    Thread.sleep(50);
    peer.onTransportClosed();
    waiter.join(5_000);
    assertFalse(waiter.isAlive(), "transport close must wake the blocked poll");
  }

  /** 关闭后拒绝继续发送与入队。 */
  @Test
  void closedPeerRejectsSendAndOffer() {
    WebSocketIdeStreamPeer peer = new WebSocketIdeStreamPeer(session);
    peer.close();
    assertThrows(IllegalStateException.class, () -> peer.sendText("{}"));
    assertFalse(peer.offer(new IdeStreamPeer.Inbound.Text("{}")));
  }

  /** 发送失败即视为连接关闭（fail-closed）。 */
  @Test
  void sendFailureClosesPeer() throws Exception {
    org.mockito.Mockito.doThrow(new java.io.IOException("boom")).when(session).sendMessage(any());
    WebSocketIdeStreamPeer peer = new WebSocketIdeStreamPeer(session);
    assertThrows(IllegalStateException.class, () -> peer.sendBinary(new byte[] {1}));
    assertTrue(peer.closed());
  }
}
