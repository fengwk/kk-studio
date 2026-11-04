package fun.fengwk.kkstudio.harness.api.history;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.api.entry.GoalSetting;
import fun.fengwk.kkstudio.harness.contributor.api.BranchView;
import fun.fengwk.kkstudio.harness.contributor.api.CustomStateSnapshot;
import fun.fengwk.kkstudio.harness.contributor.api.GoalSnapshot;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Scoped branch view：只暴露本 Contributor 的 custom state 与 branch 生效 Goal，不泄漏其它路径内容。 */
class ScopedBranchViewTest {

  /** 验证 scoped contributor state 与 branch 用户 Goal 一起暴露，其它 Contributor 的 entry 不可见。 */
  @Test
  void exposesOnlyOwnContributorStateAndBranchGoal() {
    UUID goalId = new UUID(0L, 42L);
    BranchView view =
        new ScopedBranchView(
            List.of(
                customEntry("goal", "goal.progress", "{\"state\":\"ok\"}"),
                customEntry("other", "goal.progress", "{\"state\":\"other\"}"),
                customEntry("goal", "unrelated", "{}")),
            "goal",
            new GoalSetting(goalId, "ship it"));

    assertEquals(
        List.of(new CustomStateSnapshot("{\"state\":\"ok\"}")),
        view.customEntries("goal.progress"));
    assertEquals(
        Optional.of(new CustomStateSnapshot("{\"state\":\"ok\"}")),
        view.latestCustomEntry("goal.progress"));
    assertTrue(view.customEntries("unrelated").contains(new CustomStateSnapshot("{}")));
    // 用户 Goal 来自 branch 生效 settings，而不是 contributor 自定义状态。
    assertEquals(Optional.of(new GoalSnapshot(goalId, "ship it")), view.goal());
  }

  /** 用户清除 Goal（settings.goal == null）时 branch view 必须报告 empty，而不是空文本 Goal。 */
  @Test
  void reportsEmptyGoalWhenBranchSettingsHaveNoGoal() {
    BranchView view = new ScopedBranchView(List.of(), "goal", null);

    assertTrue(view.goal().isEmpty());
    assertTrue(view.customEntries("goal.progress").isEmpty());
    assertThrows(NullPointerException.class, () -> view.customEntries(null));
    assertThrows(NullPointerException.class, () -> new ScopedBranchView(null, "goal", null));
    assertThrows(NullPointerException.class, () -> new ScopedBranchView(List.of(), null, null));
  }

  private static Entry customEntry(String contributorId, String customType, String dataJson) {
    return new Entry(
        UUID.randomUUID(),
        new UUID(0L, 1L),
        new UUID(0L, 2L),
        new CustomEntryPayload(contributorId, customType, dataJson),
        Instant.parse("2026-01-01T00:00:01Z"));
  }
}
