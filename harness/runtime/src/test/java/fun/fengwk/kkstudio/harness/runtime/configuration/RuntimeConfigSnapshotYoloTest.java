package fun.fengwk.kkstudio.harness.runtime.configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.thread.reconcile.ReconcileTestSupport;

/** Pure YOLO snapshot replacement stays in runtime configuration domain. */
class RuntimeConfigSnapshotYoloTest {

  @Test
  void withYoloEnabledCopiesOnlyPolicyFlag() {
    RuntimeConfigSnapshot current = ReconcileTestSupport.configSnapshot();
    RuntimeConfigSnapshot enabled = current.withYoloEnabled(true);
    RuntimeConfigSnapshot disabled = enabled.withYoloEnabled(false);

    assertTrue(enabled.yoloEnabled());
    assertFalse(disabled.yoloEnabled());
    assertEquals(current.agent(), enabled.agent());
    assertEquals(current.model(), enabled.model());
    assertEquals(current.toolNames(), enabled.toolNames());
    assertEquals(current.skillNames(), enabled.skillNames());
  }
}
