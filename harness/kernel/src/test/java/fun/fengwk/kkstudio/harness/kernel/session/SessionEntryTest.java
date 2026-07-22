package fun.fengwk.kkstudio.harness.kernel.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import java.time.Instant;

/** SessionEntry 字段约束、Entry parent 不变量测试。 */
class SessionEntryTest {

  private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

  /** 仅用于测试的最小 EntryPayload：固定类型。 */
  private record TestPayload(EntryType type) implements EntryPayload {}

  @Test
  void rootEntryHasNoParent() {
    SessionEntry root =
        new SessionEntry(1L, 10L, null, EntryType.ROOT, new TestPayload(EntryType.ROOT), NOW);

    assertNull(root.parentEntryId());
    assertEquals(EntryType.ROOT, root.type());
  }

  @Test
  void nonRootEntryRequiresParent() {
    EntryPayload payload = new TestPayload(EntryType.MESSAGE);
    assertThrows(
        IllegalArgumentException.class,
        () -> new SessionEntry(2L, 10L, null, EntryType.MESSAGE, payload, NOW));
  }

  @Test
  void rootEntryCannotHaveParent() {
    EntryPayload payload = new TestPayload(EntryType.ROOT);
    assertThrows(
        IllegalArgumentException.class,
        () -> new SessionEntry(1L, 10L, 100L, EntryType.ROOT, payload, NOW));
  }

  @Test
  void nonRootEntryAcceptsParent() {
    EntryPayload payload = new TestPayload(EntryType.MESSAGE);
    SessionEntry entry = new SessionEntry(2L, 10L, 1L, EntryType.MESSAGE, payload, NOW);

    assertEquals(1L, entry.parentEntryId());
  }

  @Test
  void rejectsNonPositiveIds() {
    EntryPayload payload = new TestPayload(EntryType.MESSAGE);
    assertThrows(
        IllegalArgumentException.class,
        () -> new SessionEntry(0L, 10L, 1L, EntryType.MESSAGE, payload, NOW));
    assertThrows(
        IllegalArgumentException.class,
        () -> new SessionEntry(1L, 0L, 1L, EntryType.MESSAGE, payload, NOW));
    assertThrows(
        IllegalArgumentException.class,
        () -> new SessionEntry(1L, 10L, 0L, EntryType.MESSAGE, payload, NOW));
  }

  @Test
  void rejectsPayloadTypeMismatch() {
    EntryPayload payload = new TestPayload(EntryType.MESSAGE);
    assertThrows(
        IllegalArgumentException.class,
        () -> new SessionEntry(1L, 10L, 1L, EntryType.ROOT, payload, NOW));
  }
}
