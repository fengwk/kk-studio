package fun.fengwk.kkstudio.harness.runtime.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.entry.EntryPayload;
import fun.fengwk.kkstudio.harness.runtime.entry.EntryType;

/** SessionEntry 字段约束、Entry parent 不变量测试。 */
class SessionEntryTest {

  /** 仅用于测试的最小 EntryPayload：固定类型。 */
  private record TestPayload(EntryType type) implements EntryPayload {}

  @Test
  void rootEntryHasNoParent() {
    SessionEntry root = new SessionEntry(1L, null, new TestPayload(EntryType.ROOT));

    assertNull(root.parentEntryId());
    assertEquals(EntryType.ROOT, root.payload().type());
  }

  @Test
  void nonRootEntryRequiresParent() {
    EntryPayload payload = new TestPayload(EntryType.MESSAGE);
    assertThrows(IllegalArgumentException.class, () -> new SessionEntry(2L, null, payload));
  }

  @Test
  void rootEntryCannotHaveParent() {
    EntryPayload payload = new TestPayload(EntryType.ROOT);
    assertThrows(IllegalArgumentException.class, () -> new SessionEntry(1L, 100L, payload));
  }

  @Test
  void nonRootEntryAcceptsParent() {
    EntryPayload payload = new TestPayload(EntryType.MESSAGE);
    SessionEntry entry = new SessionEntry(2L, 1L, payload);

    assertEquals(1L, entry.parentEntryId());
    assertEquals(payload, entry.payload());
  }

  @Test
  void rejectsNonPositiveIds() {
    EntryPayload payload = new TestPayload(EntryType.MESSAGE);
    assertThrows(IllegalArgumentException.class, () -> new SessionEntry(0L, 1L, payload));
    assertThrows(IllegalArgumentException.class, () -> new SessionEntry(1L, 0L, payload));
  }

  @Test
  void rejectsNullPayload() {
    assertThrows(NullPointerException.class, () -> new SessionEntry(1L, null, null));
  }
}
