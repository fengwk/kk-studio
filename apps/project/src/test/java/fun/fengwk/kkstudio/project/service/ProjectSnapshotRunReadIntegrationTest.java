package fun.fengwk.kkstudio.project.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import fun.fengwk.kkstudio.project.ProjectTestSupport;
import fun.fengwk.kkstudio.project.domain.IssueRunStatus;
import fun.fengwk.kkstudio.project.dto.ProjectIssueSnapshotDTO;
import fun.fengwk.kkstudio.project.dto.ProjectSnapshotDTO;
import fun.fengwk.kkstudio.project.model.Issue;
import fun.fengwk.kkstudio.project.model.IssueRun;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 看板快照的当前/最近 Run 定向读取：在真实 PostgreSQL 上验证选取语义与读取边界。
 *
 * <p>测试意图：活动 Run 优先于更大 ordinal 的终态 Run、无活动 Run 时取最大 ordinal、看板四个读取面各自只取必要行（Run 每 Issue 至多一行）， 并且
 * Agent 名称来自项目级绑定读取而不是每 Issue 一次查询。
 */
class ProjectSnapshotRunReadIntegrationTest extends ProjectTestSupport {

  @Autowired private ProjectSnapshotAssembler snapshotAssembler;

  /** 活动 Run 先于 ordinal 更大的终态 Run 被选中，且看板只读该一行。 */
  @Test
  void activeRunWinsOverTerminalRunWithGreaterOrdinal() {
    String designAgent = createAgent();
    String reviewAgent = createAgent();
    UUID projectId = createProjectWithStages("看板当前 Run", designAgent, reviewAgent, 3);
    Issue issue = createIssue(projectId);
    issueLifecycleService.transition(issue.getId(), issue.getVersion(), key("t"), "DESIGN");
    IssueRun designRun =
        issueRunService.acceptRun(
            issueEditingService.getIssue(issue.getId()).getId(), key("accept-design"));
    issueRunService.finishRun(
        designRun.getId(),
        designRun.getVersion(),
        key("finish-design"),
        IssueRunStatus.COMPLETED,
        appendHistoryEntry(designRun.getThreadId()),
        null,
        "REVIEW",
        null);

    IssueRun reviewRun = issueRunService.acceptRun(issue.getId(), key("accept-review"));
    assertEquals(2L, reviewRun.getOrdinal());
    // 直接写入一条 ordinal 更大的终态 Run：满足全部 FK/check，只是应用路径不会产生（活动 Run 唯一索引不限制终态行）。
    insertTerminalRun(reviewRun, 3L);
    assertEquals(
        3L, count("select count(*) from project_issue_run where issue_id = ?", issue.getId()));

    List<IssueRun> runs = issueRunRepository.listCurrentOrLatestByProjectId(projectId);

    assertEquals(1, runs.size());
    assertEquals(reviewRun.getId(), runs.get(0).getId());

    ProjectSnapshotDTO snapshot = snapshotAssembler.assemble(projectId);
    ProjectIssueSnapshotDTO boardIssue = snapshot.getIssues().get(0);
    assertNotNull(boardIssue.getCurrentOrLatestRun());
    assertEquals(reviewRun.getId().toString(), boardIssue.getCurrentOrLatestRun().getId());
    assertEquals(2L, boardIssue.getCurrentOrLatestRun().getOrdinal());
    assertEquals(IssueRunStatus.RUNNING, boardIssue.getCurrentOrLatestRun().getStatus());
    // Run 冻结的 Thread 归属 REVIEW 阶段的 Agent：项目级绑定读取解析出正确名称。
    assertEquals(reviewAgent, boardIssue.getCurrentOrLatestRun().getAgentName());
  }

  /** 没有活动 Run 时取最大 ordinal，且每个 Issue 只返回一行；没有 Run 的 Issue 保持空摘要。 */
  @Test
  void fallsBackToMaxOrdinalAndReturnsOneRowPerIssue() {
    String designAgent = createAgent();
    String reviewAgent = createAgent();
    UUID projectId = createProjectWithStages("看板最近 Run", designAgent, reviewAgent, 3);
    Issue issue = createIssue(projectId);
    Issue untouched = createIssue(projectId);
    issueLifecycleService.transition(issue.getId(), issue.getVersion(), key("t"), "DESIGN");
    IssueRun designRun =
        issueRunService.acceptRun(
            issueEditingService.getIssue(issue.getId()).getId(), key("accept-design"));
    issueRunService.finishRun(
        designRun.getId(),
        designRun.getVersion(),
        key("finish-design"),
        IssueRunStatus.COMPLETED,
        appendHistoryEntry(designRun.getThreadId()),
        null,
        "REVIEW",
        null);
    IssueRun reviewRun = issueRunService.acceptRun(issue.getId(), key("accept-review"));
    issueRunService.finishRun(
        reviewRun.getId(),
        reviewRun.getVersion(),
        key("finish-review"),
        IssueRunStatus.FAILED,
        appendHistoryEntry(reviewRun.getThreadId()),
        null,
        null,
        "execution failed");
    assertEquals(2L, reviewRun.getOrdinal());
    assertEquals(
        2L, count("select count(*) from project_issue_run where issue_id = ?", issue.getId()));

    List<IssueRun> runs = issueRunRepository.listCurrentOrLatestByProjectId(projectId);

    assertEquals(1, runs.size());
    assertEquals(reviewRun.getId(), runs.get(0).getId());

    ProjectSnapshotDTO snapshot = snapshotAssembler.assemble(projectId);
    assertEquals(2, snapshot.getIssues().size());
    ProjectIssueSnapshotDTO latest = snapshot.getIssues().get(0);
    assertEquals(issue.getId().toString(), latest.getIssue().getId());
    assertEquals(reviewRun.getId().toString(), latest.getCurrentOrLatestRun().getId());
    assertEquals(2L, latest.getCurrentOrLatestRun().getOrdinal());
    assertEquals(IssueRunStatus.FAILED, latest.getCurrentOrLatestRun().getStatus());
    assertEquals(reviewAgent, latest.getCurrentOrLatestRun().getAgentName());
    assertEquals(untouched.getId().toString(), snapshot.getIssues().get(1).getIssue().getId());
    assertNull(snapshot.getIssues().get(1).getCurrentOrLatestRun());
  }

  /**
   * 直接写入一条 ordinal 更大的终态 Run：借用活动 Run 的 Session/Thread/起始 Entry，因此所有外键与 check 都成立。
   *
   * <p>只有 {@code uk_project_issue_run_active} 限制活动 Run 的唯一性，终态行不受该约束。
   */
  private void insertTerminalRun(IssueRun activeRun, long ordinal) {
    Timestamp now = Timestamp.from(Instant.now());
    jdbc.update(
        "insert into project_issue_run (id, issue_id, ordinal, state, session_id, thread_id,"
            + " status, start_entry_id, remaining_execution_ms, version, started_at, ended_at)"
            + " values (?, ?, ?, ?, ?, ?, 'FAILED', ?, 60000, 0, ?, ?)",
        UUID.randomUUID(),
        activeRun.getIssueId(),
        ordinal,
        activeRun.getState(),
        activeRun.getSessionId(),
        activeRun.getThreadId(),
        activeRun.getStartEntryId(),
        now,
        now);
  }
}
