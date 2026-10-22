package fun.fengwk.kkstudio.ide.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.environment.daemon.DaemonProtocolException;

/**
 * 意图：credit 窗口必须有界且对称 —— 初始 262144，超窗发送被拒绝，ACK 只能释放已发送未确认的字节，超窗/负数 ACK 视为协议错误； 这是阻止无界排队与「先 ACK
 * 后缓冲」的核心不变量。
 */
class IdeStreamCreditWindowTest {

  @Test
  void startsAtInitialCredit() {
    IdeStreamCreditWindow window = new IdeStreamCreditWindow();
    assertEquals(IdeStreamCreditWindow.INITIAL_CREDIT, window.available());
    assertEquals(0L, window.outstanding());
  }

  @Test
  void consumesCreditOnSendAndReleasesOnAck() {
    IdeStreamCreditWindow window = new IdeStreamCreditWindow();
    assertTrue(window.onSend(1000));
    assertEquals(IdeStreamCreditWindow.INITIAL_CREDIT - 1000, window.available());
    window.onAck(1000);
    assertEquals(IdeStreamCreditWindow.INITIAL_CREDIT, window.available());
    assertEquals(0L, window.outstanding());
  }

  @Test
  void rejectsSendBeyondWindow() {
    IdeStreamCreditWindow window = new IdeStreamCreditWindow();
    assertTrue(window.onSend(IdeStreamCreditWindow.INITIAL_CREDIT));
    assertFalse(window.onSend(1));
    assertFalse(window.canSend(1));
    // 释放部分后才可继续发送。
    window.onAck(1);
    assertTrue(window.onSend(1));
  }

  @Test
  void accumulatesAcrossChunks() {
    IdeStreamCreditWindow window = new IdeStreamCreditWindow();
    assertTrue(window.onSend(65536));
    assertTrue(window.onSend(65536));
    assertTrue(window.onSend(65536));
    assertTrue(window.onSend(65536));
    assertEquals(0L, window.available());
    assertFalse(window.onSend(1));
  }

  @Test
  void rejectsOverAckAndNegativeAck() {
    IdeStreamCreditWindow window = new IdeStreamCreditWindow();
    window.onSend(10);
    assertThrows(DaemonProtocolException.class, () -> window.onAck(11));
    assertThrows(DaemonProtocolException.class, () -> window.onAck(-1));
    window.onAck(10);
    assertThrows(DaemonProtocolException.class, () -> window.onAck(1));
  }

  @Test
  void rejectsNegativeSend() {
    IdeStreamCreditWindow window = new IdeStreamCreditWindow();
    assertThrows(IllegalArgumentException.class, () -> window.onSend(-1));
  }
}
