package fun.fengwk.kkstudio.platform.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.platform.project.model.Issue;
import fun.fengwk.kkstudio.platform.project.model.IssueRun;
import fun.fengwk.kkstudio.platform.project.model.IssueRunStatus;
import fun.fengwk.kkstudio.platform.project.model.IssueStatus;
import fun.fengwk.kkstudio.platform.project.model.IssueStatusTransition;
import fun.fengwk.kkstudio.platform.project.model.IssueTransitionAction;

import java.util.Map;
import java.util.Set;

/**
 * 完整数据驱动测试： 1. 7x7 Issue pair 合法迁移真值表与 Action x 状态的目标集合白名单（其余组合无任何合法目标）； 2. 6x6 IssueRun
 * 活跃与终态单向迁移真值表； 3. 终态判定与可归档条件不变式。
 */
class IssueStatusTransitionUnitTest {

  /** 声明所有合法的 (Action, SourceStatus) -> 合法目标集合；未声明的组合均不允许任何迁移。 */
  private static final Map<IssueTransitionAction, Map<IssueStatus, Set<IssueStatus>>>
      EXPECTED_ACTION_TARGETS =
          Map.ofEntries(
              Map.entry(
                  IssueTransitionAction.READY,
                  Map.of(IssueStatus.BACKLOG, Set.of(IssueStatus.TODO))),
              Map.entry(
                  IssueTransitionAction.DEFER,
                  Map.of(IssueStatus.TODO, Set.of(IssueStatus.BACKLOG))),
              Map.entry(
                  IssueTransitionAction.START_EXECUTION,
                  Map.of(IssueStatus.TODO, Set.of(IssueStatus.IN_PROGRESS))),
              Map.entry(
                  IssueTransitionAction.SUBMIT,
                  Map.of(IssueStatus.IN_PROGRESS, Set.of(IssueStatus.IN_REVIEW))),
              // 同一业务动作的两个合法目标：未达项目阈值回 TODO，达到阈值转 BLOCKED
              Map.entry(
                  IssueTransitionAction.REQUEST_CHANGES,
                  Map.of(IssueStatus.IN_REVIEW, Set.of(IssueStatus.TODO, IssueStatus.BLOCKED))),
              Map.entry(
                  IssueTransitionAction.APPROVE,
                  Map.of(IssueStatus.IN_REVIEW, Set.of(IssueStatus.DONE))),
              // BLOCKED 只能由人工恢复回到 TODO/BACKLOG，或被授权取消
              Map.entry(
                  IssueTransitionAction.RECOVER,
                  Map.of(IssueStatus.BLOCKED, Set.of(IssueStatus.TODO))),
              Map.entry(
                  IssueTransitionAction.RECOVER_TO_BACKLOG,
                  Map.of(IssueStatus.BLOCKED, Set.of(IssueStatus.BACKLOG))),
              Map.entry(
                  IssueTransitionAction.CANCEL,
                  Map.of(
                      IssueStatus.BACKLOG, Set.of(IssueStatus.CANCELED),
                      IssueStatus.TODO, Set.of(IssueStatus.CANCELED),
                      IssueStatus.IN_PROGRESS, Set.of(IssueStatus.CANCELED),
                      IssueStatus.IN_REVIEW, Set.of(IssueStatus.CANCELED),
                      IssueStatus.BLOCKED, Set.of(IssueStatus.CANCELED))),
              Map.entry(
                  IssueTransitionAction.REOPEN,
                  Map.of(
                      IssueStatus.DONE, Set.of(IssueStatus.TODO),
                      IssueStatus.CANCELED, Set.of(IssueStatus.TODO))));

  /** 7x7 状态空间中所有允许的 (From, To) 有序对集合 */
  private static final Set<String> EXPECTED_VALID_PAIRS =
      Set.of(
          "BACKLOG->TODO",
          "TODO->BACKLOG",
          "TODO->IN_PROGRESS",
          "IN_PROGRESS->IN_REVIEW",
          "IN_REVIEW->TODO",
          "IN_REVIEW->BLOCKED",
          "IN_REVIEW->DONE",
          "BACKLOG->CANCELED",
          "TODO->CANCELED",
          "IN_PROGRESS->CANCELED",
          "IN_REVIEW->CANCELED",
          "BLOCKED->CANCELED",
          "BLOCKED->TODO",
          "BLOCKED->BACKLOG",
          "DONE->TODO",
          "CANCELED->TODO");

  @Test
  void testIssueTerminalAndCanBeArchivedInvariants() {
    // 只有 DONE 和 CANCELED 属于终态，且只有终态 Issue 允许被归档；BLOCKED 是等待人工处理的非终态
    for (IssueStatus status : IssueStatus.values()) {
      Issue issue = Issue.builder().status(status).build();
      if (status == IssueStatus.DONE || status == IssueStatus.CANCELED) {
        assertTrue(status.isTerminal(), "Status " + status + " must be terminal");
        assertTrue(status.canBeArchived(), "Status " + status + " must be archivable");
        assertTrue(issue.isTerminal(), "Issue in " + status + " must be terminal");
        assertTrue(issue.canBeArchived(), "Issue in " + status + " must be archivable");
      } else {
        assertFalse(status.isTerminal(), "Status " + status + " must not be terminal");
        assertFalse(status.canBeArchived(), "Status " + status + " must not be archivable");
        assertFalse(issue.isTerminal(), "Issue in " + status + " must not be terminal");
        assertFalse(issue.canBeArchived(), "Issue in " + status + " must not be archivable");
      }
    }
    assertFalse(IssueStatus.BLOCKED.isTerminal());
    assertFalse(IssueStatus.BLOCKED.canBeArchived());
  }

  @Test
  void testIssueStatusSevenBySevenPairTruthTable() {
    // 遍历完整的 7x7 状态转移矩阵，确保白名单外的所有迁移均为 false
    assertEquals(7, IssueStatus.values().length);
    for (IssueStatus from : IssueStatus.values()) {
      for (IssueStatus to : IssueStatus.values()) {
        String key = from.name() + "->" + to.name();
        boolean expectedValid = EXPECTED_VALID_PAIRS.contains(key);
        assertEquals(
            expectedValid,
            IssueStatusTransition.isValidPair(from, to),
            () -> "Pair " + key + " isValidPair mismatch");
      }
    }

    assertFalse(IssueStatusTransition.isValidPair(null, IssueStatus.TODO));
    assertFalse(IssueStatusTransition.isValidPair(IssueStatus.TODO, null));
    assertFalse(IssueStatusTransition.isValidPair(null, null));
    // 跨轮次未声明的迁移必须被拒绝
    assertFalse(IssueStatusTransition.isValidPair(IssueStatus.BLOCKED, IssueStatus.DONE));
    assertFalse(IssueStatusTransition.isValidPair(IssueStatus.BLOCKED, IssueStatus.IN_PROGRESS));
    assertFalse(IssueStatusTransition.isValidPair(IssueStatus.DONE, IssueStatus.IN_PROGRESS));
    assertFalse(IssueStatusTransition.isValidPair(IssueStatus.CANCELED, IssueStatus.IN_REVIEW));
  }

  @Test
  void testIssueActionBySourceStatusComprehensiveMatrix() {
    // 遍历全部 Action x 状态空间：目标集合必须与白名单完全一致，未声明的组合不得允许任何迁移，
    // 且多目标动作必须由调用方显式选择目标，禁止用 transition(...) 猜测。
    assertEquals(IssueTransitionAction.values().length, EXPECTED_ACTION_TARGETS.size());
    int expectedPairCount = 0;
    for (Map.Entry<IssueTransitionAction, Map<IssueStatus, Set<IssueStatus>>> actionEntry :
        EXPECTED_ACTION_TARGETS.entrySet()) {
      IssueTransitionAction action = actionEntry.getKey();
      Map<IssueStatus, Set<IssueStatus>> expectedForAction = actionEntry.getValue();
      expectedPairCount += expectedForAction.values().stream().mapToInt(Set::size).sum();

      for (IssueStatus source : IssueStatus.values()) {
        Set<IssueStatus> expectedTargets = expectedForAction.getOrDefault(source, Set.of());
        Set<IssueStatus> actualTargets = IssueStatusTransition.targets(source, action);
        assertEquals(
            expectedTargets,
            actualTargets,
            () -> "Targets of " + action + " from " + source + " mismatch");
        for (IssueStatus anyTarget : IssueStatus.values()) {
          assertEquals(
              expectedTargets.contains(anyTarget),
              IssueStatusTransition.isAllowed(source, anyTarget, action),
              () -> "isAllowed(" + source + ", " + anyTarget + ", " + action + ") mismatch");
        }
        if (expectedTargets.size() == 1) {
          assertEquals(
              expectedTargets.iterator().next(),
              IssueStatusTransition.transition(source, action),
              () -> "Unique transition of " + action + " from " + source + " mismatch");
        } else {
          assertThrows(
              IllegalStateException.class,
              () -> IssueStatusTransition.transition(source, action),
              () -> "Action " + action + " from " + source + " must require an explicit target");
        }
      }
    }
    // 两张真值表必须互相自洽：Action 白名单覆盖的迁移数等于 pair 白名单规模
    assertEquals(EXPECTED_VALID_PAIRS.size(), expectedPairCount);

    assertThrows(
        IllegalStateException.class,
        () -> IssueStatusTransition.transition(null, IssueTransitionAction.READY));
    assertThrows(
        IllegalStateException.class,
        () -> IssueStatusTransition.transition(IssueStatus.BACKLOG, null));
    assertEquals(Set.of(), IssueStatusTransition.targets(null, IssueTransitionAction.READY));
    assertEquals(Set.of(), IssueStatusTransition.targets(IssueStatus.BACKLOG, null));
    assertFalse(IssueStatusTransition.isAllowed(null, null, null));
  }

  @Test
  void testBlockedStatusRequiresHumanRecovery() {
    // BLOCKED 是自动推进停止的显式业务状态：仅人工恢复/改要求或授权取消可以离开，不得直接批准或归档
    assertEquals(
        Set.of(IssueStatus.TODO, IssueStatus.BLOCKED),
        IssueStatusTransition.targets(
            IssueStatus.IN_REVIEW, IssueTransitionAction.REQUEST_CHANGES));
    assertTrue(
        IssueStatusTransition.isAllowed(
            IssueStatus.IN_REVIEW, IssueStatus.BLOCKED, IssueTransitionAction.REQUEST_CHANGES));
    assertEquals(
        Set.of(IssueStatus.TODO),
        IssueStatusTransition.targets(IssueStatus.BLOCKED, IssueTransitionAction.RECOVER));
    assertTrue(
        IssueStatusTransition.isAllowed(
            IssueStatus.BLOCKED, IssueStatus.TODO, IssueTransitionAction.RECOVER));
    assertEquals(
        Set.of(IssueStatus.BACKLOG),
        IssueStatusTransition.targets(
            IssueStatus.BLOCKED, IssueTransitionAction.RECOVER_TO_BACKLOG));
    assertTrue(
        IssueStatusTransition.isAllowed(
            IssueStatus.BLOCKED, IssueStatus.BACKLOG, IssueTransitionAction.RECOVER_TO_BACKLOG));
    assertEquals(
        Set.of(IssueStatus.CANCELED),
        IssueStatusTransition.targets(IssueStatus.BLOCKED, IssueTransitionAction.CANCEL));
    assertEquals(
        Set.of(),
        IssueStatusTransition.targets(IssueStatus.BLOCKED, IssueTransitionAction.APPROVE));
    assertEquals(
        Set.of(),
        IssueStatusTransition.targets(IssueStatus.BLOCKED, IssueTransitionAction.START_EXECUTION));
    assertEquals(
        Set.of(), IssueStatusTransition.targets(IssueStatus.BLOCKED, IssueTransitionAction.SUBMIT));
  }

  @Test
  void testIssueRunStatusSixBySixTruthTable() {
    // 完整 6x6 IssueRunStatus 转换真值表
    // RUNNING 可以转 WAITING_HUMAN, COMPLETED, FAILED, CANCELLED, UNKNOWN (5 种合法)
    // WAITING_HUMAN 可以转 RUNNING, CANCELLED, FAILED, UNKNOWN (4 种合法，禁止直接转 COMPLETED)
    // 终态 (COMPLETED, FAILED, CANCELLED, UNKNOWN) 禁止转任何状态 (0 种合法)
    for (IssueRunStatus from : IssueRunStatus.values()) {
      for (IssueRunStatus to : IssueRunStatus.values()) {
        boolean canTransition = from.canTransitionTo(to);
        if (from == IssueRunStatus.RUNNING) {
          if (to == IssueRunStatus.WAITING_HUMAN
              || to == IssueRunStatus.COMPLETED
              || to == IssueRunStatus.FAILED
              || to == IssueRunStatus.CANCELLED
              || to == IssueRunStatus.UNKNOWN) {
            assertTrue(canTransition, "RUNNING must be able to transition to " + to);
          } else {
            assertFalse(canTransition, "RUNNING must not transition to " + to);
          }
        } else if (from == IssueRunStatus.WAITING_HUMAN) {
          if (to == IssueRunStatus.RUNNING
              || to == IssueRunStatus.FAILED
              || to == IssueRunStatus.CANCELLED
              || to == IssueRunStatus.UNKNOWN) {
            assertTrue(canTransition, "WAITING_HUMAN must be able to transition to " + to);
          } else {
            assertFalse(canTransition, "WAITING_HUMAN must not transition to " + to);
          }
        } else {
          // Terminal
          assertFalse(canTransition, "Terminal status " + from + " must not transition to " + to);
        }
      }
      assertFalse(from.canTransitionTo(null));
    }
  }

  @Test
  void testIssueRunStatusActiveAndTerminalInvariants() {
    assertTrue(IssueRunStatus.RUNNING.isActive());
    assertTrue(IssueRunStatus.WAITING_HUMAN.isActive());
    assertFalse(IssueRunStatus.COMPLETED.isActive());
    assertFalse(IssueRunStatus.FAILED.isActive());
    assertFalse(IssueRunStatus.CANCELLED.isActive());
    assertFalse(IssueRunStatus.UNKNOWN.isActive());

    assertFalse(IssueRunStatus.RUNNING.isTerminal());
    assertFalse(IssueRunStatus.WAITING_HUMAN.isTerminal());
    assertTrue(IssueRunStatus.COMPLETED.isTerminal());
    assertTrue(IssueRunStatus.FAILED.isTerminal());
    assertTrue(IssueRunStatus.CANCELLED.isTerminal());
    assertTrue(IssueRunStatus.UNKNOWN.isTerminal());

    IssueRun activeRun = IssueRun.builder().status(IssueRunStatus.RUNNING).build();
    assertTrue(activeRun.isActive());
    assertFalse(activeRun.isTerminal());

    IssueRun terminalRun = IssueRun.builder().status(IssueRunStatus.COMPLETED).build();
    assertFalse(terminalRun.isActive());
    assertTrue(terminalRun.isTerminal());
  }
}
