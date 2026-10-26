package fun.fengwk.kkstudio.ide.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.environment.daemon.DaemonProtocolException;

/**
 * 意图：credit 窗口必须有界且对称 —— 初始 262144，每个数据帧至少消耗 512，超窗发送被拒绝，ACK 只能释放已发送未确认、且至少一帧的 credit； 这是阻止无界排队、「先
 * ACK 后缓冲」以及小帧突发撑爆接收队列的核心不变量。
 */
class IdeStreamCreditWindowTest {

  @Test
  void startsAtInitialCredit() {
    IdeStreamCreditWindow window = new IdeStreamCreditWindow();
    assertEquals(IdeStreamCreditWindow.INITIAL_CREDIT, window.available());
    assertEquals(0L, window.outstanding());
    assertEquals(IdeStreamCreditWindow.INITIAL_CREDIT, window.maxFramePayload());
  }

  @Test
  void consumesCreditOnSendAndReleasesOnAck() {
    IdeStreamCreditWindow window = new IdeStreamCreditWindow();
    assertTrue(window.onSendFrame(1000));
    assertEquals(IdeStreamCreditWindow.INITIAL_CREDIT - 1000, window.available());
    window.onAck(1000);
    assertEquals(IdeStreamCreditWindow.INITIAL_CREDIT, window.available());
    assertEquals(0L, window.outstanding());
  }

  @Test
  void chargesEveryFrameAtLeastTheMinimumCredit() {
    // 小帧与零长度帧同样按最小代价计价：窗口内在途帧数因此有界，接收端可按帧数精确定界。
    assertEquals(512, IdeStreamCreditWindow.frameCost(0));
    assertEquals(512, IdeStreamCreditWindow.frameCost(1));
    assertEquals(512, IdeStreamCreditWindow.frameCost(512));
    assertEquals(513, IdeStreamCreditWindow.frameCost(513));
    assertEquals(512, IdeStreamCreditWindow.MAX_IN_FLIGHT_FRAMES);

    IdeStreamCreditWindow window = new IdeStreamCreditWindow();
    for (int i = 0; i < IdeStreamCreditWindow.MAX_IN_FLIGHT_FRAMES; i++) {
      assertTrue(window.onSendFrame(i % 2));
    }
    assertFalse(window.canSendFrame(0));
    assertFalse(window.onSendFrame(1));
    assertEquals(0L, window.maxFramePayload());
  }

  @Test
  void rejectsSendBeyondWindow() {
    IdeStreamCreditWindow window = new IdeStreamCreditWindow();
    assertTrue(window.onSendFrame(IdeStreamCreditWindow.INITIAL_CREDIT));
    assertFalse(window.onSendFrame(1));
    assertFalse(window.canSendFrame(1));
    // 释放一个最小帧后才可继续发送。
    window.onAck(IdeStreamCreditWindow.MIN_FRAME_CREDIT);
    assertTrue(window.onSendFrame(1));
  }

  @Test
  void accumulatesAcrossChunks() {
    IdeStreamCreditWindow window = new IdeStreamCreditWindow();
    assertTrue(window.onSendFrame(65536));
    assertTrue(window.onSendFrame(65536));
    assertTrue(window.onSendFrame(65536));
    assertTrue(window.onSendFrame(65536));
    assertEquals(0L, window.available());
    assertFalse(window.onSendFrame(1));
  }

  @Test
  void maxFramePayloadIsZeroWhenLessThanOneMinimumFrameRemains() {
    IdeStreamCreditWindow window = new IdeStreamCreditWindow();
    assertTrue(window.onSendFrame(IdeStreamCreditWindow.INITIAL_CREDIT - 511));
    assertEquals(511L, window.available());
    assertEquals(0L, window.maxFramePayload());
    window.onAck(IdeStreamCreditWindow.MIN_FRAME_CREDIT);
    assertEquals(1023L, window.maxFramePayload());
  }

  @Test
  void rejectsOverAckUnderAckAndNegativeAck() {
    IdeStreamCreditWindow window = new IdeStreamCreditWindow();
    window.onSendFrame(10);
    assertThrows(DaemonProtocolException.class, () -> window.onAck(513));
    assertThrows(DaemonProtocolException.class, () -> window.onAck(-1));
    // 不足一个数据帧最小代价的 ACK 是协议错误，否则对端可用大量微小 ACK 制造无界控制帧。
    assertThrows(DaemonProtocolException.class, () -> window.onAck(511));
    window.onAck(512);
    assertThrows(DaemonProtocolException.class, () -> window.onAck(512));
  }

  @Test
  void rejectsNegativeSend() {
    IdeStreamCreditWindow window = new IdeStreamCreditWindow();
    assertThrows(IllegalArgumentException.class, () -> window.onSendFrame(-1));
  }
}
