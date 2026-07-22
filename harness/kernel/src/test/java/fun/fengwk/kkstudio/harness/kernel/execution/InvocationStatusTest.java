package fun.fengwk.kkstudio.harness.kernel.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** InvocationStatus terminal 判定测试。 */
class InvocationStatusTest {

  @Test
  void terminalStates() {
    assertTrue(InvocationStatus.SUCCEEDED.isTerminal());
    assertTrue(InvocationStatus.FAILED.isTerminal());
    assertTrue(InvocationStatus.CANCELLED.isTerminal());
    assertTrue(InvocationStatus.UNKNOWN.isTerminal());
  }

  @Test
  void nonTerminalStates() {
    assertFalse(InvocationStatus.QUEUED.isTerminal());
    assertFalse(InvocationStatus.RUNNING.isTerminal());
    assertFalse(InvocationStatus.RETRY_WAIT.isTerminal());
  }

  @Test
  void terminalCountIsExactlyFour() {
    int terminalCount = 0;
    for (InvocationStatus status : InvocationStatus.values()) {
      if (status.isTerminal()) {
        terminalCount++;
      }
    }
    assertEquals(4, terminalCount);
  }
}
