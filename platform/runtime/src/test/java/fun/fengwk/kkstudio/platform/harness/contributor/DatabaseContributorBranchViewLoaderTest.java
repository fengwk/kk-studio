package fun.fengwk.kkstudio.platform.harness.contributor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.contributor.api.BranchView;
import fun.fengwk.kkstudio.harness.contributor.api.ContributorId;
import fun.fengwk.kkstudio.harness.contributor.api.CustomStateSnapshot;
import fun.fengwk.kkstudio.harness.contributor.api.GoalSnapshot;
import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.entry.GoalSetting;
import fun.fengwk.kkstudio.harness.runtime.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.runtime.history.CustomEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/** 验证 scoped contributor state 的窄查询加载与 branch 用户 Goal 的只读暴露。 */
class DatabaseContributorBranchViewLoaderTest {

  /** 验证 scoped contributor state 与 branch 用户 Goal 都走窄查询加载，绝不物化完整 EntryPath。 */
  @Test
  void loadsScopedContributorStateAndExposesBranchGoalWithoutFullEntryPath() {
    HarnessStore store = mock(HarnessStore.class);
    HarnessStore.Transaction transaction = mock(HarnessStore.Transaction.class);
    UUID entryId = new UUID(0L, 11L);
    UUID goalId = new UUID(0L, 42L);
    Entry customEntry = customEntry(entryId, "goal", "goal.progress", "{\"state\":\"ok\"}");
    when(transaction.loadContributorCustomEntriesOnPath(entryId, "goal"))
        .thenReturn(List.of(customEntry));
    when(transaction.loadContributorCustomEntriesOnPath(entryId, "other")).thenReturn(List.of());
    when(transaction.loadBranchSettings(entryId)).thenReturn(settings(goalId, "ship it"));
    when(store.transaction(any()))
        .thenAnswer(
            invocation -> {
              Function<HarnessStore.Transaction, ?> callback = invocation.getArgument(0);
              return callback.apply(transaction);
            });
    DatabaseContributorBranchViewLoader loader = new DatabaseContributorBranchViewLoader(store);

    BranchView view = loader.load(entryId, "goal");

    assertEquals(
        List.of(new CustomStateSnapshot(1, "{\"state\":\"ok\"}")),
        view.customEntries("goal.progress"));
    assertEquals(
        Optional.of(new CustomStateSnapshot(1, "{\"state\":\"ok\"}")),
        view.latestCustomEntry("goal.progress"));
    assertTrue(view.customEntries("other.type").isEmpty());
    // 用户 Goal 来自 branch 生效 settings，而不是 contributor 自定义状态。
    assertEquals(Optional.of(new GoalSnapshot(goalId, "ship it")), view.goal());

    // Scoped to "goal", other contributor's entry is not visible
    BranchView otherView = loader.load(entryId, new ContributorId("other"));
    assertTrue(otherView.customEntries("goal.progress").isEmpty());

    verify(transaction).loadContributorCustomEntriesOnPath(entryId, "goal");
    verify(transaction).loadContributorCustomEntriesOnPath(entryId, "other");
    verify(transaction, times(2)).loadBranchSettings(entryId);
    // 窄读取守卫：工具执行路径绝不加载完整 EntryPath。
    verify(transaction, never()).loadEntryPath(any());
    assertThrows(NullPointerException.class, () -> loader.load(null, "goal"));
    assertThrows(NullPointerException.class, () -> loader.load(entryId, (String) null));
    assertThrows(NullPointerException.class, () -> new DatabaseContributorBranchViewLoader(null));
  }

  /** 用户清除 Goal（settings.goal == null）时 branch view 必须报告 empty，而不是空文本 Goal。 */
  @Test
  void reportsEmptyGoalWhenBranchSettingsHaveNoGoal() {
    HarnessStore store = mock(HarnessStore.class);
    HarnessStore.Transaction transaction = mock(HarnessStore.Transaction.class);
    UUID entryId = new UUID(0L, 11L);
    when(transaction.loadContributorCustomEntriesOnPath(entryId, "goal")).thenReturn(List.of());
    when(transaction.loadBranchSettings(entryId)).thenReturn(settings(null, null));
    when(store.transaction(any()))
        .thenAnswer(
            invocation -> {
              Function<HarnessStore.Transaction, ?> callback = invocation.getArgument(0);
              return callback.apply(transaction);
            });
    DatabaseContributorBranchViewLoader loader = new DatabaseContributorBranchViewLoader(store);

    assertTrue(loader.load(entryId, "goal").goal().isEmpty());
  }

  /** 构造窄查询返回的 branch settings：Goal 为 null 表示用户未设置或已清除。 */
  private static BranchSettings settings(UUID goalId, String goalText) {
    return new BranchSettings(
        "coding",
        new ModelSelection("anthropic", "claude-sonnet", "default"),
        null,
        goalId == null ? null : new GoalSetting(goalId, goalText));
  }

  private static Entry customEntry(
      UUID id, String contributorId, String customType, String dataJson) {
    UUID sessionId = new UUID(0L, 1L);
    UUID parentId = new UUID(0L, 2L);
    return new Entry(
        id,
        sessionId,
        parentId,
        new CustomEntryPayload(contributorId, customType, 1, dataJson),
        Instant.parse("2026-01-01T00:00:01Z"));
  }
}
