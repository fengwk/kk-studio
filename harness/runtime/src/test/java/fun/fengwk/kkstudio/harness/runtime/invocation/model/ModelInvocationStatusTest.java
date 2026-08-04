package fun.fengwk.kkstudio.harness.runtime.invocation.model;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** ModelInvocationStatus terminal classification. */
class ModelInvocationStatusTest {

  @Test
  void classifiesTerminalStates() {
    assertTrue(ModelInvocationStatus.SUCCEEDED.isTerminal());
    assertTrue(ModelInvocationStatus.FAILED.isTerminal());
    assertTrue(ModelInvocationStatus.CANCELLED.isTerminal());
    assertTrue(ModelInvocationStatus.UNKNOWN.isTerminal());
    assertFalse(ModelInvocationStatus.READY.isTerminal());
    assertFalse(ModelInvocationStatus.DISPATCHING.isTerminal());
    assertFalse(ModelInvocationStatus.RUNNING.isTerminal());
  }
}
