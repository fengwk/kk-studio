package fun.fengwk.kkstudio.harness.runtime.work;

import static fun.fengwk.kkstudio.harness.runtime.store.testing.TestIds.id;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** WorkTarget identity 校验。 */
class WorkTargetTest {

  @Test
  void acceptsAllTargetTypes() {
    assertEquals(WorkTargetType.THREAD, new WorkTarget(WorkTargetType.THREAD, id(1L)).type());
    assertEquals(id(2L), new WorkTarget(WorkTargetType.MODEL, id(2L)).id());
    assertEquals(WorkTargetType.TOOL, new WorkTarget(WorkTargetType.TOOL, id(3L)).type());
  }

  @Test
  void rejectsInvalidTargets() {
    assertThrows(NullPointerException.class, () -> new WorkTarget(null, id(1L)));
    assertThrows(NullPointerException.class, () -> new WorkTarget(WorkTargetType.THREAD, null));
  }
}
