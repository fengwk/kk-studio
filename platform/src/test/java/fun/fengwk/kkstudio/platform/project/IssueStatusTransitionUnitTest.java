package fun.fengwk.kkstudio.platform.project;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.platform.project.model.Issue;
import fun.fengwk.kkstudio.platform.project.model.IssueRun;
import fun.fengwk.kkstudio.platform.project.model.IssueRunActorType;
import fun.fengwk.kkstudio.platform.project.model.IssueRunRole;
import fun.fengwk.kkstudio.platform.project.model.IssueRunStatus;
import fun.fengwk.kkstudio.platform.project.model.IssueStatus;

/** 六态生命周期模型、终态判定与可归档条件的单元测试。 验证 RFC 3.2 规定的终端状态、归档条件与运行态生命周期的基本模型不变式。 */
class IssueStatusTransitionUnitTest {

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
  void testIssueRunStatusActiveAndTerminalInvariants() {
    // RUNNING 和 WAITING_HUMAN 属于活跃态；COMPLETED、FAILED、CANCELLED、UNKNOWN 属于终态
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

  @Test
  void testIssueRunRoleAndActorTypeEnums() {
    // 验证角色和行为者类型的枚举定义
    assertTrue(IssueRunRole.EXECUTOR != null);
    assertTrue(IssueRunRole.REVIEWER != null);
    assertTrue(IssueRunActorType.AGENT != null);
    assertTrue(IssueRunActorType.HUMAN != null);
  }
}
