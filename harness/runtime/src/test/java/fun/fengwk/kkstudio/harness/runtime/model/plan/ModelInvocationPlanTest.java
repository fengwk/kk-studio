package fun.fengwk.kkstudio.harness.runtime.model.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.model.ModelInvocationRequest;
import fun.fengwk.kkstudio.harness.runtime.thread.reconcile.ReconcileTestSupport;

import java.util.List;

/** {@link ModelInvocationPlan} 字段约束与值相等性测试。 */
class ModelInvocationPlanTest {

  @Test
  void rejectsNonPositiveHead() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new ModelInvocationPlan(0L, request(), ReconcileTestSupport.configSnapshot()));
  }

  @Test
  void rejectsNullRequest() {
    assertThrows(
        NullPointerException.class,
        () -> new ModelInvocationPlan(1L, null, ReconcileTestSupport.configSnapshot()));
    assertThrows(NullPointerException.class, () -> new ModelInvocationPlan(1L, request(), null));
  }

  @Test
  void valueEquality() {
    ModelInvocationPlan a =
        new ModelInvocationPlan(9L, request(), ReconcileTestSupport.configSnapshot());
    ModelInvocationPlan b = new ModelInvocationPlan(9L, a.request(), a.configSnapshot());
    ModelInvocationPlan c = new ModelInvocationPlan(10L, a.request(), a.configSnapshot());

    assertEquals(a, b);
    assertEquals(a.hashCode(), b.hashCode());
    assertNotEquals(a, c);
  }

  private static ModelInvocationRequest request() {
    return new ModelInvocationRequest(
        ReconcileTestSupport.providerRequest(), List.of(), List.of(), false);
  }
}
