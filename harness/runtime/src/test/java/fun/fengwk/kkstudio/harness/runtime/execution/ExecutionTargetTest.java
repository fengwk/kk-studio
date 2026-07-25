package fun.fengwk.kkstudio.harness.runtime.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** ExecutionTarget id 正值与 kind 必填约束测试。 */
class ExecutionTargetTest {

  @Test
  void acceptsPositiveId() {
    ExecutionTarget target = new ExecutionTarget(ExecutionTargetKind.THREAD, 1L);

    assertEquals(ExecutionTargetKind.THREAD, target.kind());
    assertEquals(1L, target.id());
  }

  @Test
  void rejectsNonPositiveId() {
    assertThrows(
        IllegalArgumentException.class, () -> new ExecutionTarget(ExecutionTargetKind.THREAD, 0L));
    assertThrows(
        IllegalArgumentException.class, () -> new ExecutionTarget(ExecutionTargetKind.THREAD, -1L));
  }

  @Test
  void rejectsNullKind() {
    assertThrows(NullPointerException.class, () -> new ExecutionTarget(null, 1L));
  }
}
