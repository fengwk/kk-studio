package fun.fengwk.kkstudio.harness.runtime.work;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** WorkTarget identity 校验。 */
class WorkTargetTest {

  @Test
  void acceptsAllTargetTypes() {
    assertEquals(WorkTargetType.THREAD, new WorkTarget(WorkTargetType.THREAD, 1L).type());
    assertEquals(2L, new WorkTarget(WorkTargetType.MODEL, 2L).id());
    assertEquals(WorkTargetType.TOOL, new WorkTarget(WorkTargetType.TOOL, 3L).type());
  }

  @Test
  void rejectsInvalidTargets() {
    assertThrows(NullPointerException.class, () -> new WorkTarget(null, 1L));
    assertThrows(IllegalArgumentException.class, () -> new WorkTarget(WorkTargetType.THREAD, 0L));
    assertThrows(IllegalArgumentException.class, () -> new WorkTarget(WorkTargetType.THREAD, -1L));
  }
}
