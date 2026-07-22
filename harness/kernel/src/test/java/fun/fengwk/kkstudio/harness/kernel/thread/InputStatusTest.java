package fun.fengwk.kkstudio.harness.kernel.thread;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** InputStatus terminal 判定测试。 */
class InputStatusTest {

  @Test
  void queuedIsNonTerminal() {
    assertFalse(InputStatus.QUEUED.isTerminal());
  }

  @Test
  void appliedIsTerminal() {
    assertTrue(InputStatus.APPLIED.isTerminal());
  }

  @Test
  void cancelledIsTerminal() {
    assertTrue(InputStatus.CANCELLED.isTerminal());
  }
}
