package fun.fengwk.kkstudio.harness.plugin;

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
import java.util.List;
import java.util.Optional;

class BranchViewTest {

  private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
  private static final PluginId GOAL_PLUGIN = new PluginId("com.example.goal");
  private static final PluginId OTHER_PLUGIN = new PluginId("com.example.other");

  @Test
  void returnsMatchingCustomEntriesInRootToHeadOrder() {
    BranchView view = new BranchView(pathWithCustomEntries());
    List<CustomEntryPayload> goals = view.customEntries(GOAL_PLUGIN, "goal");
    assertEquals(2, goals.size());
    assertEquals("{\"state\":\"first\"}", goals.get(0).dataJson());
    assertEquals("{\"state\":\"second\"}", goals.get(1).dataJson());
    assertEquals(1, view.customEntries(GOAL_PLUGIN, "memory").size());
    assertTrue(view.customEntries(GOAL_PLUGIN, "unknown").isEmpty());
  }

  @Test
  void latestCustomEntryReturnsHeadMostMatch() {
    BranchView view = new BranchView(pathWithCustomEntries());
    assertEquals(
        Optional.of("{\"state\":\"second\"}"),
        view.latestCustomEntry(GOAL_PLUGIN, "goal").map(CustomEntryPayload::dataJson));
    assertEquals(Optional.empty(), view.latestCustomEntry(GOAL_PLUGIN, "unknown"));
  }

  @Test
  void matchesBothPluginIdAndCustomType() {
    BranchView view = new BranchView(pathWithCustomEntries());
    // 不同插件可以各自拥有同名 customType；查询必须同时限定 pluginId 与 customType。
    assertEquals(
        Optional.of("{\"state\":\"other\"}"),
        view.latestCustomEntry(OTHER_PLUGIN, "goal").map(CustomEntryPayload::dataJson));
    assertEquals(2, view.customEntries(GOAL_PLUGIN, "goal").size());
    assertTrue(view.customEntries(OTHER_PLUGIN, "memory").isEmpty());
  }

  @Test
  void requiresPluginIdAndCanonicalQueryKeys() {
    BranchView view = new BranchView(pathWithCustomEntries());
    assertThrows(NullPointerException.class, () -> view.customEntries(null, "goal"));
    assertThrows(IllegalArgumentException.class, () -> view.customEntries(GOAL_PLUGIN, "Goal"));
    assertThrows(IllegalArgumentException.class, () -> view.latestCustomEntry(GOAL_PLUGIN, ""));
    assertThrows(NullPointerException.class, () -> view.latestCustomEntry(null, "goal"));
    assertThrows(NullPointerException.class, () -> new BranchView(null));
  }

  private static EntryPath pathWithCustomEntries() {
    BranchSettings settings =
        new BranchSettings(
            null, "coding", new ModelSelection("anthropic", "claude-sonnet", "default"), List.of());
    return new EntryPath(
        List.of(
            new Entry(1L, 10L, null, new RootPayload(settings), NOW),
            new Entry(
                2L,
                10L,
                1L,
                new CustomEntryPayload("com.example.goal", "goal", 1, "{\"state\":\"first\"}"),
                NOW),
            new Entry(
                3L,
                10L,
                2L,
                new CustomEntryPayload("com.example.goal", "memory", 1, "{\"note\":\"memo\"}"),
                NOW),
            new Entry(
                4L,
                10L,
                3L,
                new CustomEntryPayload("com.example.goal", "goal", 2, "{\"state\":\"second\"}"),
                NOW),
            new Entry(
                5L,
                10L,
                4L,
                new CustomEntryPayload("com.example.other", "goal", 1, "{\"state\":\"other\"}"),
                NOW)));
  }
}
