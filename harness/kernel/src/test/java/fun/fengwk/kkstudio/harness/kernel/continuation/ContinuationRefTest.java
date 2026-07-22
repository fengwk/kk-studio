package fun.fengwk.kkstudio.harness.kernel.continuation;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.kernel.execution.ExecutionTarget;
import fun.fengwk.kkstudio.harness.kernel.execution.ExecutionTargetKind;

/** ContinuationRef 字段约束测试。 */
class ContinuationRefTest {

  @Test
  void carriesOwnerAndBlocker() {
    ExecutionTarget owner = new ExecutionTarget(ExecutionTargetKind.THREAD, 1L);
    ExecutionTarget blocker = new ExecutionTarget(ExecutionTargetKind.MODEL_INVOCATION, 7L);
    ContinuationRef ref = new ContinuationRef(owner, blocker);

    assertSame(owner, ref.owner());
    assertSame(blocker, ref.blocker());
  }

  @Test
  void rejectsNullOwner() {
    ExecutionTarget blocker = new ExecutionTarget(ExecutionTargetKind.MODEL_INVOCATION, 7L);
    assertThrows(NullPointerException.class, () -> new ContinuationRef(null, blocker));
  }

  @Test
  void rejectsNullBlocker() {
    ExecutionTarget owner = new ExecutionTarget(ExecutionTargetKind.THREAD, 1L);
    assertThrows(NullPointerException.class, () -> new ContinuationRef(owner, null));
  }
}
