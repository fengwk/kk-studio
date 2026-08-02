package fun.fengwk.kkstudio.harness.runtime.thread.reconcile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionTarget;
import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionTargetKind;

/** {@link ModelCreationOutcome} 三种变体的值相等性与构造约束测试。 */
class ModelCreationOutcomeTest {

  @Test
  void createdEqualityAndIdentity() {
    ExecutionTarget target = new ExecutionTarget(ExecutionTargetKind.MODEL_INVOCATION, 99L);
    ModelCreationOutcome.Created created = new ModelCreationOutcome.Created(target);
    assertSame(target, created.target());
    assertEquals(created, new ModelCreationOutcome.Created(target));
    assertNotEquals(created, new ModelCreationOutcome.LostOwnership());
  }

  @Test
  void createdRejectsWrongKind() {
    ExecutionTarget wrongKind = new ExecutionTarget(ExecutionTargetKind.THREAD, 1L);
    assertThrows(IllegalArgumentException.class, () -> new ModelCreationOutcome.Created(wrongKind));
  }

  @Test
  void createdRejectsToolInvocationKind() {
    ExecutionTarget wrongKind = new ExecutionTarget(ExecutionTargetKind.TOOL_INVOCATION, 1L);
    assertThrows(IllegalArgumentException.class, () -> new ModelCreationOutcome.Created(wrongKind));
  }

  @Test
  void createdRejectsNullTarget() {
    assertThrows(NullPointerException.class, () -> new ModelCreationOutcome.Created(null));
  }

  @Test
  void lostOwnershipEquality() {
    assertEquals(
        new ModelCreationOutcome.LostOwnership(), new ModelCreationOutcome.LostOwnership());
  }

  @Test
  void planningFailureAppliedEquality() {
    assertEquals(
        new ModelCreationOutcome.PlanningFailureApplied(),
        new ModelCreationOutcome.PlanningFailureApplied());
  }
}
