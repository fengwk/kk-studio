package fun.fengwk.kkstudio.platform.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.project.error.ProjectDuplicateException;
import fun.fengwk.kkstudio.project.error.ProjectValidationException;
import fun.fengwk.kkstudio.project.model.Issue;
import fun.fengwk.kkstudio.project.model.IssueRun;
import fun.fengwk.kkstudio.project.model.Project;
import fun.fengwk.kkstudio.project.service.IssueService.StageBudgetView;

import java.util.UUID;

/**
 * Issue 阶段额度与稳定 Issue+Agent Thread 的集成契约（真实 PostgreSQL）。
 *
 * <p>意图：额度按 {@code (issue,state)} 共享、不随 Agent 变化；同一 Agent 跨阶段复用同一 Thread，不同 Agent 使用不同 Thread。
 */
class IssueStageBudgetIntegrationTest extends ProjectTestSupport {

  /** 同一 Agent 跨阶段续接同一 Thread，ordinal 在整个 Issue 内单调递增。 */
  @Test
  void sameAgentAcrossStagesReusesThread() {
    String agent = createAgent();
    UUID projectId = createProjectWithStages("跨阶段", agent, agent, 3);
    Issue issue = createIssue(projectId);
    issueService.transition(issue.getId(), issue.getVersion(), key("t"), "DESIGN");

    IssueRun first = issueRunService.acceptRun(issue.getId(), key("accept"));
    assertEquals(1L, first.getOrdinal());
    assertEquals("DESIGN", first.getState());
    UUID firstThread = first.getThreadId();
    assertEquals(firstThread, issueRunService.getRun(first.getId()).getThreadId());
    long endEntry =
        count(
            "select count(*) from harness_entry where session_id = ? and id = ?",
            first.getSessionId(),
            first.getStartEntryId());
    assertEquals(1L, endEntry, "Run start entry must belong to the frozen session");

    IssueRun completed =
        issueRunService.completeRun(
            first.getId(),
            first.getVersion(),
            key("complete"),
            appendHistoryEntry(firstThread),
            null,
            "REVIEW");
    assertEquals("REVIEW", issueService.getIssue(issue.getId()).getState());
    assertEquals("COMPLETED", completed.getStatus().name());

    Issue inReview = issueService.getIssue(issue.getId());
    IssueRun second = issueRunService.acceptRun(inReview.getId(), key("accept"));
    assertEquals(2L, second.getOrdinal());
    assertEquals(firstThread, second.getThreadId(), "same Agent must reuse the stable Thread");
    assertEquals(first.getSessionId(), second.getSessionId());
    assertEquals(
        1L,
        count(
            "select count(*) from project_issue_agent_thread where issue_id = ? and agent_name = ?",
            issue.getId(),
            agent));
  }

  /** 不同 Agent 使用不同稳定 Thread，但共享同一 {@code (issue,state)} 阶段额度。 */
  @Test
  void differentAgentSharesStageBudgetWithDistinctThread() {
    String agentA = createAgent();
    String agentB = createAgent();
    UUID projectId = createProjectWithStages("换 Agent", agentA, agentA, 3);
    Issue issue = createIssue(projectId);
    issueService.transition(issue.getId(), issue.getVersion(), key("t"), "DESIGN");

    IssueRun runA = issueRunService.acceptRun(issue.getId(), key("accept"));
    issueRunService.completeRun(
        runA.getId(),
        runA.getVersion(),
        key("complete"),
        appendHistoryEntry(runA.getThreadId()),
        null,
        null);

    Project project = projectService.getProject(projectId);
    projectService.updateWorkflow(
        project.getId(), project.getVersion(), workflowJson(agentB, agentB, 3));

    IssueRun runB = issueRunService.acceptRun(issue.getId(), key("accept"));
    assertNotEquals(runA.getThreadId(), runB.getThreadId(), "different Agent uses its own Thread");
    assertEquals(2L, runB.getOrdinal());

    StageBudgetView budget = issueService.getStageBudget(issue.getId(), "DESIGN");
    assertEquals(2L, budget.usedRuns(), "stage budget is shared across agents");
    assertEquals(1L, budget.remainingRuns());
    assertEquals(
        2L,
        count("select count(*) from project_issue_agent_thread where issue_id = ?", issue.getId()));
  }

  /** 额度耗尽后拒绝新建 Run，并只展示待人工授权；历史 Run 仍保留。 */
  @Test
  void exhaustedBudgetRejectsNewRun() {
    String agent = createAgent();
    UUID projectId = createProjectWithStages("额度耗尽", agent, agent, 1);
    Issue issue = createIssue(projectId);
    issueService.transition(issue.getId(), issue.getVersion(), key("t"), "DESIGN");

    IssueRun run = issueRunService.acceptRun(issue.getId(), key("accept"));
    issueRunService.completeRun(
        run.getId(),
        run.getVersion(),
        key("complete"),
        appendHistoryEntry(run.getThreadId()),
        null,
        null);

    assertEquals(0L, issueService.getStageBudget(issue.getId(), "DESIGN").remainingRuns());
    assertThrows(
        ProjectValidationException.class,
        () -> issueRunService.acceptRun(issue.getId(), key("accept")));
    assertEquals(
        1L, count("select count(*) from project_issue_run where issue_id = ?", issue.getId()));
  }

  /** 重置额度把高水位推进到 {@code nextRunOrdinal - 1}，不重新授权历史 Run，随后允许新 Run。 */
  @Test
  void resetStageBudgetMovesHighWaterAndAllowsNewRun() {
    String agent = createAgent();
    UUID projectId = createProjectWithStages("重置额度", agent, agent, 1);
    Issue issue = createIssue(projectId);
    issueService.transition(issue.getId(), issue.getVersion(), key("t"), "DESIGN");
    IssueRun run = issueRunService.acceptRun(issue.getId(), key("accept"));
    issueRunService.completeRun(
        run.getId(),
        run.getVersion(),
        key("complete"),
        appendHistoryEntry(run.getThreadId()),
        null,
        null);

    Issue before = issueService.getIssue(issue.getId());
    StageBudgetView reset =
        issueService.resetStageBudget(
            before.getId(), before.getVersion(), key("reset"), "DESIGN", 2);

    assertEquals(2, reset.maxRuns());
    assertEquals(1L, reset.budgetAfterOrdinal(), "high-water is nextRunOrdinal - 1");
    assertEquals(0L, reset.usedRuns(), "historical runs must not be re-authorized");
    IssueRun next = issueRunService.acceptRun(issue.getId(), key("accept"));
    assertEquals(2L, next.getOrdinal());
  }

  /** 首次授权只接受启用且有 Agent 的工作阶段，且不能重复授权。 */
  @Test
  void authorizeStageBudgetValidatesStageAndRejectsDuplicate() {
    String agent = createAgent();
    Project project = projectService.createProject("授权", "描述", true);
    projectService.updateWorkflow(
        project.getId(), project.getVersion(), workflowJson(agent, agent, 1));
    Issue issue = createIssue(project.getId());

    assertThrows(
        ProjectValidationException.class,
        () ->
            issueService.authorizeStageBudget(
                issue.getId(), issue.getVersion(), key("auth"), "INIT", 1));

    Issue fresh = issueService.getIssue(issue.getId());
    StageBudgetView view =
        issueService.authorizeStageBudget(
            fresh.getId(), fresh.getVersion(), key("auth"), "DESIGN", 2);
    assertEquals(2, view.maxRuns());
    Issue afterAuth = issueService.getIssue(issue.getId());
    assertThrows(
        ProjectDuplicateException.class,
        () ->
            issueService.authorizeStageBudget(
                afterAuth.getId(), afterAuth.getVersion(), key("auth"), "DESIGN", 5));
  }

  /** 额度计算不按 Agent 过滤，因此切换 Agent 不重置已消耗次数。 */
  @Test
  void budgetCountingIgnoresAgentIdentity() {
    String agentA = createAgent();
    String agentB = createAgent();
    UUID projectId = createProjectWithStages("跨 Agent 计数", agentA, agentA, 3);
    Issue issue = createIssue(projectId);
    issueService.transition(issue.getId(), issue.getVersion(), key("t"), "DESIGN");
    issueRunService.acceptRun(issue.getId(), key("accept"));

    long usedByState =
        count(
            "select count(*) from project_issue_run where issue_id = ? and state = 'DESIGN'",
            issue.getId());
    assertEquals(1L, usedByState);
    assertEquals(
        1L, count("select count(*) from project_issue_run where issue_id = ?", issue.getId()));
    assertEquals(2, issueService.getStageBudget(issue.getId(), "DESIGN").remainingRuns());
    assertNotEquals(agentA, agentB, "fixtures must be two distinct agents");
  }

  /**
   * 终态消耗：FAILED/CANCELLED/UNKNOWN 各按 {@code (issue,state)} 高水位消耗一次，不因收尾状态被豁免。
   *
   * <p>意图：锁定真实 SQL 计数的「失败、取消与 UNKNOWN 同样计数」语义——三种终态各消费一次后额度耗尽；人工核查 UNKNOWN 门禁并恢复后，新建 Run
   * 因额度耗尽被确定性拒绝，而不是被门禁或活动 Run 拒绝。
   */
  @Test
  void terminalRunsOfEveryStatusConsumeBudget() {
    String agent = createAgent();
    UUID projectId = createProjectWithStages("终态消耗", agent, agent, 3);
    Issue issue = createIssue(projectId);
    issueService.transition(issue.getId(), issue.getVersion(), key("t"), "DESIGN");

    IssueRun failedRun = issueRunService.acceptRun(issue.getId(), key("accept"));
    issueRunService.failRun(
        failedRun.getId(),
        failedRun.getVersion(),
        key("fail"),
        failedRun.getStartEntryId(),
        "boom");
    assertEquals(
        1L,
        issueService.getStageBudget(issue.getId(), "DESIGN").usedRuns(),
        "FAILED run consumes one");

    Issue afterFail = issueService.getIssue(issue.getId());
    issueService.resumeIssue(afterFail.getId(), afterFail.getVersion(), key("resume"));

    IssueRun cancelledRun = issueRunService.acceptRun(issue.getId(), key("accept"));
    issueRunService.cancelRun(
        cancelledRun.getId(),
        cancelledRun.getVersion(),
        key("cancel"),
        appendHistoryEntry(cancelledRun.getThreadId()));
    assertEquals(
        2L,
        issueService.getStageBudget(issue.getId(), "DESIGN").usedRuns(),
        "CANCELLED run consumes one");

    Issue afterCancel = issueService.getIssue(issue.getId());
    issueService.resumeIssue(afterCancel.getId(), afterCancel.getVersion(), key("resume"));

    IssueRun unknownRun = issueRunService.acceptRun(issue.getId(), key("accept"));
    issueRunService.markUnknown(
        unknownRun.getId(),
        unknownRun.getVersion(),
        key("unknown"),
        appendHistoryEntry(unknownRun.getThreadId()),
        "side effect lost");
    StageBudgetView exhausted = issueService.getStageBudget(issue.getId(), "DESIGN");
    assertEquals(3L, exhausted.usedRuns(), "UNKNOWN run consumes one");
    assertEquals(0L, exhausted.remainingRuns());

    // UNKNOWN 门禁先由人工核查解除再恢复；此后唯一阻塞新 Run 的因素是额度耗尽。
    Issue afterUnknown = issueService.getIssue(issue.getId());
    issueService.resolveUnknown(
        afterUnknown.getId(), afterUnknown.getVersion(), key("resolve"), "checked");
    Issue afterResolve = issueService.getIssue(issue.getId());
    issueService.resumeIssue(afterResolve.getId(), afterResolve.getVersion(), key("resume"));

    ProjectValidationException budgetExhausted =
        assertThrows(
            ProjectValidationException.class,
            () -> issueRunService.acceptRun(issue.getId(), key("accept")));
    assertTrue(
        budgetExhausted.getMessage().contains("budget is exhausted"), budgetExhausted.getMessage());
    assertEquals(
        3L, count("select count(*) from project_issue_run where issue_id = ?", issue.getId()));
  }
}
