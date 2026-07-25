package fun.fengwk.kkstudio.harness.runtime.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.continuation.ContinuationRef;

/** StepResult sealed 接口各变体的值语义与构造约束测试。 */
class StepResultTest {

  private static ContinuationRef continuation() {
    return new ContinuationRef(
        new ExecutionTarget(ExecutionTargetKind.THREAD, 1L),
        new ExecutionTarget(ExecutionTargetKind.MODEL_INVOCATION, 7L));
  }

  @Test
  void progressedIsZeroArg() {
    StepResult.Progressed progressed = new StepResult.Progressed();
    assertEquals(new StepResult.Progressed(), progressed);
  }

  @Test
  void quiescentIsZeroArg() {
    StepResult.Quiescent quiescent = new StepResult.Quiescent();
    assertEquals(new StepResult.Quiescent(), quiescent);
  }

  @Test
  void lostOwnershipIsZeroArg() {
    StepResult.LostOwnership lostOwnership = new StepResult.LostOwnership();
    assertEquals(new StepResult.LostOwnership(), lostOwnership);
  }

  @Test
  void suspendedCarriesContinuation() {
    ContinuationRef ref = continuation();
    StepResult.Suspended suspended = new StepResult.Suspended(ref);

    assertSame(ref, suspended.continuation());
  }

  @Test
  void suspendedRejectsNullContinuation() {
    assertThrows(NullPointerException.class, () -> new StepResult.Suspended(null));
  }

  @Test
  void failedCarriesFailure() {
    Failure failure = new Failure("CODE", "msg");
    StepResult.Failed failed = new StepResult.Failed(failure);

    assertSame(failure, failed.failure());
  }

  @Test
  void failedRejectsNullFailure() {
    assertThrows(NullPointerException.class, () -> new StepResult.Failed(null));
  }
}
