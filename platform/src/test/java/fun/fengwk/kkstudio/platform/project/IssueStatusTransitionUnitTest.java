package fun.fengwk.kkstudio.platform.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
 * 完整数据驱动测试： 1. 6x6 Issue pair 合法迁移真值表与 8 Action x 6 状态映射白名单（其余全部抛出 IllegalStateException）； 2. 6x6
 * IssueRun 活跃与终态单向迁移真值表； 3. 终态判定与可归档条件不变式。
 */
class IssueStatusTransitionUnitTest {

  /** 声明所有合法的 (Action, SourceStatus) -> TargetStatus 映射，其余均为非法 */
  private static final Map<IssueTransitionAction, Map<IssueStatus, IssueStatus>>
      EXPECTED_ACTION_TRANSITIONS =
          Map.of(
              IssueTransitionAction.READY,
              Map.of(IssueStatus.BACKLOG, IssueStatus.TODO),
              IssueTransitionAction.DEFER,
              Map.of(IssueStatus.TODO, IssueStatus.BACKLOG),
              IssueTransitionAction.START_EXECUTION,
              Map.of(IssueStatus.TODO, IssueStatus.IN_PROGRESS),
              IssueTransitionAction.SUBMIT,
              Map.of(IssueStatus.IN_PROGRESS, IssueStatus.IN_REVIEW),
              IssueTransitionAction.REQUEST_CHANGES,
              Map.of(IssueStatus.IN_REVIEW, IssueStatus.TODO),
              IssueTransitionAction.APPROVE,
              Map.of(IssueStatus.IN_REVIEW, IssueStatus.DONE),
              IssueTransitionAction.CANCEL,
              Map.of(
                  IssueStatus.BACKLOG, IssueStatus.CANCELED,
                  IssueStatus.TODO, IssueStatus.CANCELED,
                  IssueStatus.IN_PROGRESS, IssueStatus.CANCELED,
                  IssueStatus.IN_REVIEW, IssueStatus.CANCELED),
              IssueTransitionAction.REOPEN,
              Map.of(
                  IssueStatus.DONE, IssueStatus.TODO,
                  IssueStatus.CANCELED, IssueStatus.TODO));

  /** 6x6 状态空间中所有允许的 (From, To) 有序对集合 */
  private static final Set<String> EXPECTED_VALID_PAIRS =
      Set.of(
          "BACKLOG->TODO",
          "TODO->BACKLOG",
          "TODO->IN_PROGRESS",
          "IN_PROGRESS->IN_REVIEW",
          "IN_REVIEW->TODO",
          "IN_REVIEW->DONE",
          "BACKLOG->CANCELED",
          "TODO->CANCELED",
          "IN_PROGRESS->CANCELED",
          "IN_REVIEW->CANCELED",
          "DONE->TODO",
          "CANCELED->TODO");

  @Test
  void testIssueTerminalAndCanBeArchivedInvariants() {
    // 只有 DONE 和 CANCELED 属于终态，且只有终态 Issue 允许被归档
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
  }

  @Test
  void testIssueStatusSixBySixPairTruthTable() {
    // 遍历完整的 6x6 状态转移矩阵，确保白名单外的所有迁移均为 false
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
  }

  @Test
  void testIssueActionBySourceStatusComprehensiveMatrix() {
    // 遍历 8 Action x 6 Source 空间（共 48 种组合），确保白名单外无遗漏、全部抛出 IllegalStateException
    for (IssueTransitionAction action : IssueTransitionAction.values()) {
      Map<IssueStatus, IssueStatus> expectedForAction =
          EXPECTED_ACTION_TRANSITIONS.getOrDefault(action, Map.of());

      for (IssueStatus source : IssueStatus.values()) {
        if (expectedForAction.containsKey(source)) {
          IssueStatus expectedTarget = expectedForAction.get(source);
          // transition
          IssueStatus actualTarget = IssueStatusTransition.transition(source, action);
          assertEquals(expectedTarget, actualTarget);
          // isAllowed
          assertTrue(IssueStatusTransition.isAllowed(source, expectedTarget, action));
          // 其余非 expectedTarget 均为 false
          for (IssueStatus other : IssueStatus.values()) {
            if (other != expectedTarget) {
              assertFalse(IssueStatusTransition.isAllowed(source, other, action));
            }
          }
        } else {
          // 非法动作必须抛出 IllegalStateException
          IllegalStateException ex =
              assertThrows(
                  IllegalStateException.class,
                  () -> IssueStatusTransition.transition(source, action),
                  () ->
                      "Action " + action + " from " + source + " must throw IllegalStateException");
          assertNotNull(ex.getMessage());
          // 任何目标状态 isAllowed 均为 false
          for (IssueStatus anyTarget : IssueStatus.values()) {
            assertFalse(IssueStatusTransition.isAllowed(source, anyTarget, action));
          }
        }
      }
    }

    assertThrows(
        IllegalArgumentException.class,
        () -> IssueStatusTransition.transition(null, IssueTransitionAction.READY));
    assertThrows(
        IllegalArgumentException.class,
        () -> IssueStatusTransition.transition(IssueStatus.BACKLOG, null));
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
