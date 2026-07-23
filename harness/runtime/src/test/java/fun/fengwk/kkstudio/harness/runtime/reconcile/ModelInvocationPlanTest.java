package fun.fengwk.kkstudio.harness.runtime.reconcile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** {@link ModelInvocationPlan} 字段约束与值相等性测试。 */
class ModelInvocationPlanTest {

  @Test
  void rejectsNonPositiveHead() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new ModelInvocationPlan(0L, ReconcileTestSupport.providerRequest()));
  }

  @Test
  void rejectsNullRequest() {
    assertThrows(NullPointerException.class, () -> new ModelInvocationPlan(1L, null));
  }

  @Test
  void valueEquality() {
    ModelInvocationPlan a = new ModelInvocationPlan(9L, ReconcileTestSupport.providerRequest());
    ModelInvocationPlan b = new ModelInvocationPlan(9L, a.request());
    ModelInvocationPlan c = new ModelInvocationPlan(10L, a.request());

    assertEquals(a, b);
    assertEquals(a.hashCode(), b.hashCode());
    assertNotEquals(a, c);
  }
}
