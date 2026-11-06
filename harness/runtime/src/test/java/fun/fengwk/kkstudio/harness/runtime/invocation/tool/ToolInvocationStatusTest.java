package fun.fengwk.kkstudio.harness.runtime.invocation.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.api.tool.ToolInvocationStatus;

import java.util.Set;

/** ToolInvocationStatus 终态分类。 */
class ToolInvocationStatusTest {

  @Test
  void classifiesTerminalStates() {
    // 状态集与可创建、可执行及终态生命周期保持一致。
    assertEquals(
        Set.of(
            ToolInvocationStatus.WAITING_APPROVAL,
            ToolInvocationStatus.WAITING_INPUT,
            ToolInvocationStatus.READY,
            ToolInvocationStatus.RUNNING,
            ToolInvocationStatus.SUCCEEDED,
            ToolInvocationStatus.FAILED,
            ToolInvocationStatus.CANCELLED,
            ToolInvocationStatus.UNKNOWN),
        Set.of(ToolInvocationStatus.values()));
    assertTrue(ToolInvocationStatus.SUCCEEDED.isTerminal());
    assertTrue(ToolInvocationStatus.FAILED.isTerminal());
    assertTrue(ToolInvocationStatus.CANCELLED.isTerminal());
    assertTrue(ToolInvocationStatus.UNKNOWN.isTerminal());
    assertFalse(ToolInvocationStatus.WAITING_APPROVAL.isTerminal());
    assertFalse(ToolInvocationStatus.WAITING_INPUT.isTerminal());
    assertFalse(ToolInvocationStatus.READY.isTerminal());
    assertFalse(ToolInvocationStatus.RUNNING.isTerminal());
  }
}
