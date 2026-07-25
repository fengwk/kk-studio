package fun.fengwk.kkstudio.harness.runtime.configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.reconcile.ReconcileTestSupport;

/** Pure YOLO snapshot replacement stays in runtime configuration domain. */
class RuntimeConfigSnapshotYoloTest {

  @Test
  void withYoloEnabledCopiesOnlyPolicyFlag() {
    RuntimeConfigSnapshot current = ReconcileTestSupport.configSnapshot();
    RuntimeConfigSnapshot enabled = current.withYoloEnabled(true);
    RuntimeConfigSnapshot disabled = enabled.withYoloEnabled(false);

    assertTrue(enabled.policy().yoloEnabled());
    assertFalse(disabled.policy().yoloEnabled());
    assertEquals(current.agent(), enabled.agent());
    assertEquals(current.model(), enabled.model());
    assertEquals(current.tools(), enabled.tools());
    assertEquals(current.skills(), enabled.skills());
    assertEquals(current.environment(), enabled.environment());
    assertEquals(current.policy().maxTurns(), enabled.policy().maxTurns());
  }
}
