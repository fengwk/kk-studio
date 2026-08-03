package fun.fengwk.kkstudio.harness.runtime.entry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.util.List;

/** EntryType 集合与 ROOT 判定测试。 */
class EntryTypeTest {

  @Test
  void containsOnlySupportedTypes() {
    assertEquals(
        List.of(
            "ROOT",
            "TURN_START",
            "MESSAGE",
            "CUSTOM_MESSAGE",
            "ASSISTANT_ERROR",
            "ASSISTANT_ABORTED",
            "TURN_END"),
        List.of(EntryType.values()).stream().map(Enum::name).toList());
    assertEquals(7, EntryType.values().length);
  }

  @Test
  void rootHelper() {
    assertTrue(EntryType.ROOT.isRoot());
    for (EntryType type : EntryType.values()) {
      if (type != EntryType.ROOT) {
        assertFalse(type.isRoot());
      }
    }
  }
}
