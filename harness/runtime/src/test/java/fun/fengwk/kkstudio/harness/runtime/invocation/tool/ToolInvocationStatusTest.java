package fun.fengwk.kkstudio.harness.runtime.invocation.tool;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** ToolInvocationStatus 终态分类。 */
class ToolInvocationStatusTest {

  @Test
  void classifiesTerminalStates() {
    assertTrue(ToolInvocationStatus.SUCCEEDED.isTerminal());
    assertTrue(ToolInvocationStatus.FAILED.isTerminal());
    assertTrue(ToolInvocationStatus.CANCELLED.isTerminal());
    assertTrue(ToolInvocationStatus.UNKNOWN.isTerminal());
    assertFalse(ToolInvocationStatus.WAITING_APPROVAL.isTerminal());
    assertFalse(ToolInvocationStatus.READY.isTerminal());
    assertFalse(ToolInvocationStatus.DISPATCHING.isTerminal());
    assertFalse(ToolInvocationStatus.RUNNING.isTerminal());
  }
}
