package fun.fengwk.kkstudio.harness.kernel.session;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** EntryType ROOT 判定测试。 */
class EntryTypeTest {

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
