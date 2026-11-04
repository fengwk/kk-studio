package fun.fengwk.kkstudio.harness.spi.work;

import static fun.fengwk.kkstudio.harness.runtime.store.testing.TestIds.id;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.store.testing.TestWorkTypes;

/** WorkTarget identity 校验。 */
class WorkTargetTest {

  @Test
  void acceptsCoreAndDriverTargetTypes() {
    assertEquals(WorkTargetType.THREAD, new WorkTarget(WorkTargetType.THREAD, id(1L)).type());
    assertEquals(id(2L), new WorkTarget(TestWorkTypes.MODEL, id(2L)).id());
    assertEquals(WorkTargetType.TOOL, new WorkTarget(WorkTargetType.TOOL, id(3L)).type());
  }

  @Test
  void rejectsInvalidTargets() {
    assertThrows(NullPointerException.class, () -> new WorkTarget(null, id(1L)));
    assertThrows(NullPointerException.class, () -> new WorkTarget(WorkTargetType.THREAD, null));
  }
}
