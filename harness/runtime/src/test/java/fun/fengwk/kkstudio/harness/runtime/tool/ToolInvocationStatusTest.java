package fun.fengwk.kkstudio.harness.runtime.tool;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ToolInvocationStatusTest {

  /** 状态机只允许 preparation、approval、claim、execution 和 cancel 的显式路径。 */
  @Test
  void enforcesExplicitTransitions() {
    assertTrue(ToolInvocationStatus.PREPARING.canTransitionTo(ToolInvocationStatus.QUEUED));
    assertTrue(
        ToolInvocationStatus.PREPARING.canTransitionTo(ToolInvocationStatus.WAITING_APPROVAL));
    assertTrue(ToolInvocationStatus.WAITING_APPROVAL.canTransitionTo(ToolInvocationStatus.FAILED));
    assertTrue(ToolInvocationStatus.QUEUED.canTransitionTo(ToolInvocationStatus.RUNNING));
    assertTrue(ToolInvocationStatus.RUNNING.canTransitionTo(ToolInvocationStatus.CANCEL_REQUESTED));
    assertTrue(
        ToolInvocationStatus.CANCEL_REQUESTED.canTransitionTo(ToolInvocationStatus.CANCELLED));
    assertFalse(
        ToolInvocationStatus.WAITING_APPROVAL.canTransitionTo(ToolInvocationStatus.RUNNING));
    assertFalse(ToolInvocationStatus.QUEUED.canTransitionTo(ToolInvocationStatus.SUCCEEDED));
    assertThrows(
        IllegalStateException.class,
        () -> ToolInvocationStatus.FAILED.requireTransitionTo(ToolInvocationStatus.QUEUED));
  }

  /** 只有 QUEUED 可 claim；所有结果终态包括 UNKNOWN 都不可再次转换。 */
  @Test
  void definesTerminalAndClaimSemantics() {
    for (ToolInvocationStatus status : ToolInvocationStatus.values()) {
      assertTrue(status.isClaimable() == (status == ToolInvocationStatus.QUEUED));
    }
    for (ToolInvocationStatus terminal :
        new ToolInvocationStatus[] {
          ToolInvocationStatus.SUCCEEDED,
          ToolInvocationStatus.FAILED,
          ToolInvocationStatus.CANCELLED,
          ToolInvocationStatus.UNKNOWN
        }) {
      assertTrue(terminal.isTerminal());
      assertFalse(terminal.canTransitionTo(ToolInvocationStatus.QUEUED));
    }
    assertFalse(ToolInvocationStatus.CANCEL_REQUESTED.isTerminal());
  }
}
