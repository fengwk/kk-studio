package fun.fengwk.kkstudio.project.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

/** {@link IssueStageBudget} 的授权、消耗与重置不变量。 */
class IssueStageBudgetTest {

  private static final UUID ISSUE_ID = ProjectDomainFixtures.id(10L);

  private final ProjectWorkflow workflow = ProjectDomainFixtures.documentedWorkflow();

  /** 额度只属于工作阶段：保留状态、非正额度与负高水位都不能构成授权事实。 */
  @Test
  void validatesBudgetShape() {
    assertEquals(
        0L, new IssueStageBudget(ProjectStateCode.of("DESIGN"), 3, 0).budgetAfterOrdinal());
    for (String reserved : List.of("INIT", "BLOCKED", "DONE")) {
      assertThrows(
          IllegalArgumentException.class,
          () -> new IssueStageBudget(ProjectStateCode.of(reserved), 3, 0));
    }
    assertThrows(
        IllegalArgumentException.class,
        () -> new IssueStageBudget(ProjectStateCode.of("DESIGN"), 0, 0));
    assertThrows(
        IllegalArgumentException.class,
        () -> new IssueStageBudget(ProjectStateCode.of("DESIGN"), -1, 0));
    assertThrows(
        IllegalArgumentException.class,
        () -> new IssueStageBudget(ProjectStateCode.of("DESIGN"), 3, -1));
    assertThrows(NullPointerException.class, () -> new IssueStageBudget(null, 3, 0));
  }

  /** 首次授权只面向 workflow 中启用且有 Agent 的阶段：人工阶段、保留状态、停用阶段与未知编码都拒绝。 */
  @Test
  void authorizesOnlyEnabledAgentStages() {
    assertEquals(
        new IssueStageBudget(ProjectStateCode.of("DESIGN"), 2, 0),
        IssueStageBudget.authorize(workflow, ProjectStateCode.of("DESIGN"), 2));
    assertThrows(
        IllegalArgumentException.class,
        () -> IssueStageBudget.authorize(workflow, ProjectStateCode.of("INIT"), 1));
    assertThrows(
        IllegalArgumentException.class,
        () -> IssueStageBudget.authorize(workflow, ProjectStateCode.of("GHOST"), 1));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            IssueStageBudget.authorize(
                ProjectDomainFixtures.manualWorkflow(), ProjectStateCode.of("MANUAL"), 1));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            IssueStageBudget.authorize(
                ProjectDomainFixtures.disabledStageWorkflow(), ProjectStateCode.of("WORK"), 1));
    assertThrows(
        NullPointerException.class,
        () -> IssueStageBudget.authorize(null, ProjectStateCode.of("DESIGN"), 1));
  }

  /** 消耗按 (issue, state) 与高水位统计：不按 Agent/Thread/Run 状态过滤，失败与不确定的 Run 同样计数。 */
  @Test
  void countsEveryRunAfterHighWaterMark() {
    List<IssueRun> runs =
        List.of(
            ProjectDomainFixtures.run(ISSUE_ID, "DESIGN", 1L, IssueRunStatus.COMPLETED),
            ProjectDomainFixtures.run(ISSUE_ID, "REVIEW", 2L, IssueRunStatus.RUNNING),
            ProjectDomainFixtures.run(ISSUE_ID, "DESIGN", 3L, IssueRunStatus.FAILED),
            ProjectDomainFixtures.run(ISSUE_ID, "DESIGN", 4L, IssueRunStatus.UNKNOWN));

    IssueStageBudget budget = new IssueStageBudget(ProjectStateCode.of("DESIGN"), 2, 0);
    assertEquals(3L, budget.usedRuns(runs));
    // 超支只停留在 0，不产生负额度
    assertEquals(0L, budget.remainingRuns(runs));

    IssueStageBudget reset = new IssueStageBudget(ProjectStateCode.of("DESIGN"), 5, 3);
    assertEquals(1L, reset.usedRuns(runs));
    assertEquals(4L, reset.remainingRuns(runs));
    assertThrows(NullPointerException.class, () -> budget.usedRuns(null));
  }

  /** 重置高水位只能前进：nextRunOrdinal 必须为正，不能回退而重新授权历史 Run。 */
  @Test
  void resetsHighWaterMarkForwardOnly() {
    IssueStageBudget budget = new IssueStageBudget(ProjectStateCode.of("DESIGN"), 3, 2);

    assertEquals(
        new IssueStageBudget(ProjectStateCode.of("DESIGN"), 5, 3), budget.reset(workflow, 5, 4));
    assertThrows(IllegalArgumentException.class, () -> budget.reset(workflow, 5, 2));
    assertThrows(IllegalArgumentException.class, () -> budget.reset(workflow, 5, 0));
    assertThrows(IllegalArgumentException.class, () -> budget.reset(workflow, 0, 4));
    assertThrows(NullPointerException.class, () -> budget.reset(null, 5, 4));

    IssueStageBudget manual = new IssueStageBudget(ProjectStateCode.of("MANUAL"), 1, 0);
    assertThrows(
        IllegalArgumentException.class,
        () -> manual.reset(ProjectDomainFixtures.manualWorkflow(), 2, 1));
  }
}
