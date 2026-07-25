package fun.fengwk.kkstudio.harness.runtime.thread;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** ThreadInputType 分类辅助方法测试。 */
class ThreadInputTypeTest {

  @Test
  void messageTypes() {
    assertTrue(ThreadInputType.USER_MESSAGE.isMessage());
    assertTrue(ThreadInputType.CUSTOM_MESSAGE.isMessage());
    assertFalse(ThreadInputType.SET_AGENT.isMessage());
    assertFalse(ThreadInputType.SET_MODEL.isMessage());
    assertFalse(ThreadInputType.SET_YOLO.isMessage());
  }

  @Test
  void configTypes() {
    assertTrue(ThreadInputType.SET_AGENT.isConfig());
    assertTrue(ThreadInputType.SET_MODEL.isConfig());
    assertTrue(ThreadInputType.SET_YOLO.isConfig());
    assertFalse(ThreadInputType.USER_MESSAGE.isConfig());
    assertFalse(ThreadInputType.CUSTOM_MESSAGE.isConfig());
  }

  @Test
  void messageAndConfigAreMutuallyExclusive() {
    for (ThreadInputType type : ThreadInputType.values()) {
      assertFalse(type.isMessage() && type.isConfig());
    }
  }
}
