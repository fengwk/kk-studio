package fun.fengwk.kkstudio.harness.runtime.reconcile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.kernel.execution.ExecutionTarget;
import fun.fengwk.kkstudio.harness.kernel.execution.ExecutionTargetKind;

/** {@link ThreadOwnership} 字段约束、值相等性与 threadTarget 映射测试。 */
class ThreadOwnershipTest {

  @Test
  void rejectsNonPositiveThreadId() {
    assertThrows(IllegalArgumentException.class, () -> new ThreadOwnership(0L, 0L, "token"));
  }

  @Test
  void rejectsNegativeEpoch() {
    assertThrows(IllegalArgumentException.class, () -> new ThreadOwnership(1L, -1L, "token"));
  }

  @Test
  void rejectsBlankToken() {
    assertThrows(IllegalArgumentException.class, () -> new ThreadOwnership(1L, 0L, " "));
    assertThrows(NullPointerException.class, () -> new ThreadOwnership(1L, 0L, null));
  }

  @Test
  void valueEqualityAndIdentity() {
    ThreadOwnership a = new ThreadOwnership(7L, 3L, "tok");
    ThreadOwnership b = new ThreadOwnership(7L, 3L, "tok");
    ThreadOwnership c = new ThreadOwnership(7L, 4L, "tok");

    assertEquals(a, b);
    assertEquals(a.hashCode(), b.hashCode());
    assertNotEquals(a, c);
  }

  @Test
  void threadTargetProducesThreadKind() {
    ThreadOwnership ownership = new ThreadOwnership(42L, 1L, "tok");
    ExecutionTarget target = ownership.threadTarget();

    assertEquals(ExecutionTargetKind.THREAD, target.kind());
    assertEquals(42L, target.id());
  }

  @Test
  void zeroEpochIsAccepted() {
    // epoch 从 0 起算；只有负值被拒绝。
    ThreadOwnership ownership = new ThreadOwnership(1L, 0L, "tok");
    assertTrue(ownership.executionEpoch() == 0L);
  }
}
