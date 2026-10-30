package fun.fengwk.kkstudio.harness.runtime.history;

import static fun.fengwk.kkstudio.harness.runtime.store.testing.TestIds.id;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnStartReason;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** EntrySlice 只接受完整路径或紧接 base 的连续增量，不接受断链、跨 Session 或伪装 ROOT。 */
class EntrySliceTest {

  private static final UUID SESSION_ID = id(1L);
  private static final Instant BASE = Instant.parse("2026-10-31T00:00:00Z");
  private static final BranchSettings SETTINGS =
      new BranchSettings("agent", ModelSelection.model("claude", "default"), null);

  @Test
  void nullBaseRequiresCompleteEntryPathAndEmptyBaseIsHead() {
    Entry root = root();
    Entry child = child(id(2L), root.id(), SESSION_ID);
    EntrySlice complete = new EntrySlice(null, List.of(root, child));
    EntrySlice empty = new EntrySlice(child.id(), List.of());

    assertNull(complete.baseEntryId());
    assertEquals(List.of(root, child), complete.entries());
    assertEquals(child.id(), empty.baseEntryId());
    assertTrue(empty.entries().isEmpty());
  }

  @Test
  void incrementalSliceMustContinueContiguouslyInOneSession() {
    Entry root = root();
    Entry first = child(id(2L), root.id(), SESSION_ID);
    Entry second = child(id(3L), first.id(), SESSION_ID);
    EntrySlice slice = new EntrySlice(root.id(), List.of(first, second));

    assertEquals(root.id(), slice.baseEntryId());
    assertEquals(List.of(first, second), slice.entries());
    assertThrows(IllegalArgumentException.class, () -> new EntrySlice(id(9L), List.of(first)));
    assertThrows(
        IllegalArgumentException.class,
        () -> new EntrySlice(root.id(), List.of(first, child(id(4L), id(9L), SESSION_ID))));
    assertThrows(
        IllegalArgumentException.class,
        () -> new EntrySlice(root.id(), List.of(first, child(id(4L), first.id(), id(2L)))));
    assertThrows(
        IllegalArgumentException.class,
        () -> new EntrySlice(root.id(), List.of(first, child(first.id(), first.id(), SESSION_ID))));
    assertThrows(IllegalArgumentException.class, () -> new EntrySlice(id(9L), List.of(root)));
    assertThrows(NullPointerException.class, () -> new EntrySlice(null, null));
  }

  private static Entry root() {
    return new Entry(id(1L), SESSION_ID, null, new RootPayload(SETTINGS), BASE);
  }

  private static Entry child(UUID entryId, UUID parentId, UUID sessionId) {
    return new Entry(
        entryId,
        sessionId,
        parentId,
        new TurnStartPayload(TurnStartReason.INPUT, SETTINGS, id(8L)),
        BASE.plusSeconds(1));
  }
}
