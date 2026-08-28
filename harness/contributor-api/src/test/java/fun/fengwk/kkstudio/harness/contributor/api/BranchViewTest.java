package fun.fengwk.kkstudio.harness.contributor.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.runtime.history.CustomEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.history.RootPayload;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** {@link BranchView} 的不可变投影、精准结构化匹配与 head-recent 顺序测试。 */
class BranchViewTest {

  private static final UUID SESSION_ID = UUID.randomUUID();
  private static final ContributorId GOAL = new ContributorId("goal");
  private static final ContributorId MEMORY = new ContributorId("memory");

  /** 空 path 产生只有 ROOT 的合法 BranchView，查询匹配返回空。 */
  @Test
  void handlesEmptyCustomHistory() {
    BranchView view = new BranchView(rootOnlyPath());
    assertTrue(view.customEntries(GOAL, "state").isEmpty());
    assertTrue(view.latestCustomEntry(GOAL, "state").isEmpty());
  }

  /** 多条 CUSTOM entry 按 root-to-head 顺序返回，跨 contributor / customType 严格隔离。 */
  @Test
  void filtersByStructuredOwnerKeyInOrder() {
    List<Entry> entries = new ArrayList<>();
    UUID rootId = UUID.randomUUID();
    entries.add(rootEntry(rootId));

    UUID e1 = UUID.randomUUID();
    CustomEntryPayload goal1 = new CustomEntryPayload("goal", "state", 1, "{\"version\":1}");
    entries.add(new Entry(e1, SESSION_ID, rootId, goal1, Instant.now()));

    UUID e2 = UUID.randomUUID();
    CustomEntryPayload memory1 = new CustomEntryPayload("memory", "state", 1, "{\"item\":\"a\"}");
    entries.add(new Entry(e2, SESSION_ID, e1, memory1, Instant.now()));

    UUID e3 = UUID.randomUUID();
    CustomEntryPayload goalOther =
        new CustomEntryPayload("goal", "archive", 1, "{\"archived\":true}");
    entries.add(new Entry(e3, SESSION_ID, e2, goalOther, Instant.now()));

    UUID e4 = UUID.randomUUID();
    CustomEntryPayload goal2 = new CustomEntryPayload("goal", "state", 2, "{\"version\":2}");
    entries.add(new Entry(e4, SESSION_ID, e3, goal2, Instant.now()));

    BranchView view = new BranchView(new EntryPath(entries));

    assertEquals(List.of(goal1, goal2), view.customEntries(GOAL, "state"));
    assertEquals(goal2, view.latestCustomEntry(GOAL, "state").orElseThrow());
    assertEquals(List.of(memory1), view.customEntries(MEMORY, "state"));
    assertEquals(memory1, view.latestCustomEntry(MEMORY, "state").orElseThrow());
    assertEquals(List.of(goalOther), view.customEntries(GOAL, "archive"));
    assertEquals(goalOther, view.latestCustomEntry(GOAL, "archive").orElseThrow());
    assertTrue(view.customEntries(new ContributorId("unknown"), "state").isEmpty());
    assertTrue(view.latestCustomEntry(new ContributorId("unknown"), "state").isEmpty());
  }

  /** 参数校验与不可变性。 */
  @Test
  void validatesParameters() {
    assertThrows(NullPointerException.class, () -> new BranchView(null));
    BranchView view = new BranchView(rootOnlyPath());
    assertThrows(NullPointerException.class, () -> view.customEntries(null, "state"));
    assertThrows(NullPointerException.class, () -> view.customEntries(GOAL, null));
    assertThrows(IllegalArgumentException.class, () -> view.customEntries(GOAL, "Invalid"));
    assertThrows(NullPointerException.class, () -> view.latestCustomEntry(null, "state"));
    assertThrows(NullPointerException.class, () -> view.latestCustomEntry(GOAL, null));
    assertThrows(IllegalArgumentException.class, () -> view.latestCustomEntry(GOAL, "Invalid"));
  }

  private static EntryPath rootOnlyPath() {
    UUID rootId = UUID.randomUUID();
    return new EntryPath(List.of(rootEntry(rootId)));
  }

  private static Entry rootEntry(UUID rootId) {
    BranchSettings settings =
        new BranchSettings(null, "test", new ModelSelection("openai", "gpt-4", "default"));
    return new Entry(rootId, SESSION_ID, null, new RootPayload(settings, null), Instant.now());
  }
}
