package fun.fengwk.kkstudio.project;

import static fun.fengwk.kkstudio.project.ProjectTestAssertions.assertDuplicate;
import static fun.fengwk.kkstudio.project.ProjectTestAssertions.assertValidation;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.project.domain.IssueRunStatus;
import fun.fengwk.kkstudio.project.model.Issue;
import fun.fengwk.kkstudio.project.model.IssueRun;
import fun.fengwk.kkstudio.project.model.Project;
import fun.fengwk.kkstudio.project.service.StageBudgetView;

import java.util.List;
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
    issueLifecycleService.transition(issue.getId(), issue.getVersion(), key("t"), "DESIGN");

    IssueRun first = issueRunService.acceptRun(issue.getId(), key("accept"));
    assertEquals(1L, first.getOrdinal());
    assertEquals("DESIGN", first.getState());
    UUID firstThread = first.getThreadId();
    assertEquals(firstThread, issueRunRepository.getById(first.getId()).getThreadId());
    long endEntry =
        count(
            "select count(*) from harness_entry where session_id = ? and id = ?",
            first.getSessionId(),
            first.getStartEntryId());
    assertEquals(1L, endEntry, "Run start entry must belong to the frozen session");

    IssueRun completed =
        issueRunService.finishRun(
            first.getId(),
            first.getVersion(),
            key("complete"),
            IssueRunStatus.COMPLETED,
            appendHistoryEntry(firstThread),
            null,
            "REVIEW",
            null);
    assertEquals("REVIEW", issueEditingService.getIssue(issue.getId()).getState());
    assertEquals("COMPLETED", completed.getStatus().name());

    Issue inReview = issueEditingService.getIssue(issue.getId());
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
    // 详情与快照读路径按 run ordinal 升序列出该 Issue 的全部 Run。
    assertEquals(
        List.of(first.getId(), second.getId()),
        issueRunService.listRuns(issue.getId()).stream().map(IssueRun::getId).toList());
  }

  /** 不同 Agent 使用不同稳定 Thread，但共享同一 {@code (issue,state)} 阶段额度。 */
  @Test
  void differentAgentSharesStageBudgetWithDistinctThread() {
    String agentA = createAgent();
    String agentB = createAgent();
    UUID projectId = createProjectWithStages("换 Agent", agentA, agentA, 3);
    Issue issue = createIssue(projectId);
    issueLifecycleService.transition(issue.getId(), issue.getVersion(), key("t"), "DESIGN");

    IssueRun runA = issueRunService.acceptRun(issue.getId(), key("accept"));
    issueRunService.finishRun(
        runA.getId(),
        runA.getVersion(),
        key("complete"),
        IssueRunStatus.COMPLETED,
        appendHistoryEntry(runA.getThreadId()),
        null,
        null,
        null);

    Project project = projectService.getProject(projectId);
    projectService.updateWorkflow(
        project.getId(), project.getVersion(), workflowJson(agentB, agentB, 3));

    IssueRun runB = issueRunService.acceptRun(issue.getId(), key("accept"));
    assertNotEquals(runA.getThreadId(), runB.getThreadId(), "different Agent uses its own Thread");
    assertEquals(2L, runB.getOrdinal());

    StageBudgetView budget = issueStageBudgetService.getStageBudget(issue.getId(), "DESIGN");
    assertEquals(2L, budget.usedRuns(), "stage budget is shared across agents");
    assertEquals(1L, budget.remainingRuns());
    assertEquals(
        2L,
        count("select count(*) from project_issue_agent_thread where issue_id = ?", issue.getId()));
  }

  /** 额度耗尽后拒绝新建 Run，并只展示待人工重置；历史 Run 仍保留。 */
  @Test
  void exhaustedBudgetRejectsNewRun() {
    String agent = createAgent();
    UUID projectId = createProjectWithStages("额度耗尽", agent, agent, 1);
    Issue issue = createIssue(projectId);
    issueLifecycleService.transition(issue.getId(), issue.getVersion(), key("t"), "DESIGN");

    IssueRun run = issueRunService.acceptRun(issue.getId(), key("accept"));
    issueRunService.finishRun(
        run.getId(),
        run.getVersion(),
        key("complete"),
        IssueRunStatus.COMPLETED,
        appendHistoryEntry(run.getThreadId()),
        null,
        null,
        null);

    assertEquals(
        0L, issueStageBudgetService.getStageBudget(issue.getId(), "DESIGN").remainingRuns());
    assertValidation(() -> issueRunService.acceptRun(issue.getId(), key("accept")));
    assertEquals(
        1L, count("select count(*) from project_issue_run where issue_id = ?", issue.getId()));
  }

  /** 重置额度把高水位推进到 {@code nextRunOrdinal - 1}，不重新授权历史 Run，随后允许新 Run。 */
  @Test
  void resetStageBudgetMovesHighWaterAndAllowsNewRun() {
    String agent = createAgent();
    UUID projectId = createProjectWithStages("重置额度", agent, agent, 1);
    Issue issue = createIssue(projectId);
    issueLifecycleService.transition(issue.getId(), issue.getVersion(), key("t"), "DESIGN");
    IssueRun run = issueRunService.acceptRun(issue.getId(), key("accept"));
    issueRunService.finishRun(
        run.getId(),
        run.getVersion(),
        key("complete"),
        IssueRunStatus.COMPLETED,
        appendHistoryEntry(run.getThreadId()),
        null,
        null,
        null);

    Issue before = issueEditingService.getIssue(issue.getId());
    StageBudgetView reset =
        issueStageBudgetService.resetStageBudget(
            before.getId(), before.getVersion(), key("reset"), "DESIGN", 2);

    assertEquals(2, reset.maxRuns());
    assertEquals(1L, reset.budgetAfterOrdinal(), "high-water is nextRunOrdinal - 1");
    assertEquals(0L, reset.usedRuns(), "historical runs must not be re-authorized");
    IssueRun next = issueRunService.acceptRun(issue.getId(), key("accept"));
    assertEquals(2L, next.getOrdinal());
  }

  /** 重放绕过旧版本与活动 Run 检查；异 payload 和新的重置请求不能改变额度。 */
  @Test
  void budgetReceiptsReplayAndActiveRunBlocksNewReset() {
    String agent = createAgent();
    UUID projectId = createProjectWithStages("额度重放", agent, agent, 2);
    Issue issue = createIssue(projectId);
    String resetKey = key("reset");
    StageBudgetView reset =
        issueStageBudgetService.resetStageBudget(
            issue.getId(), issue.getVersion(), resetKey, "REVIEW", 2);
    assertEquals(0, reset.budgetAfterOrdinal());
    Issue afterReset = issueEditingService.getIssue(issue.getId());
    issueLifecycleService.transition(issue.getId(), afterReset.getVersion(), key("t"), "DESIGN");
    issueRunService.acceptRun(issue.getId(), key("accept"));
    Issue active = issueEditingService.getIssue(issue.getId());
    assertEquals(
        reset,
        issueStageBudgetService.resetStageBudget(
            issue.getId(), afterReset.getVersion(), resetKey, "REVIEW", 2));
    assertDuplicate(
        () ->
            issueStageBudgetService.resetStageBudget(
                issue.getId(), active.getVersion(), resetKey, "REVIEW", 3));
    DomainException error =
        assertValidation(
            () ->
                issueStageBudgetService.resetStageBudget(
                    issue.getId(), active.getVersion(), key("reset"), "DESIGN", 4));
    assertTrue(error.getMessage().contains("active run"));
    assertEquals(2, issueStageBudgetService.getStageBudget(issue.getId(), "DESIGN").maxRuns());
    assertEquals(active.getVersion(), issueEditingService.getIssue(issue.getId()).getVersion());
  }

  /** 额度计算不按 Agent 过滤，因此切换 Agent 不重置已消耗次数。 */
  @Test
  void budgetCountingIgnoresAgentIdentity() {
    String agentA = createAgent();
    String agentB = createAgent();
    UUID projectId = createProjectWithStages("跨 Agent 计数", agentA, agentA, 3);
    Issue issue = createIssue(projectId);
    issueLifecycleService.transition(issue.getId(), issue.getVersion(), key("t"), "DESIGN");
    issueRunService.acceptRun(issue.getId(), key("accept"));

    long usedByState =
        count(
            "select count(*) from project_issue_run where issue_id = ? and state = 'DESIGN'",
            issue.getId());
    assertEquals(1L, usedByState);
    assertEquals(
        1L, count("select count(*) from project_issue_run where issue_id = ?", issue.getId()));
    assertEquals(
        2, issueStageBudgetService.getStageBudget(issue.getId(), "DESIGN").remainingRuns());
    assertNotEquals(agentA, agentB, "fixtures must be two distinct agents");
  }

  /**
   * 终态消耗：FAILED/CANCELLED 各按 {@code (issue,state)} 高水位消耗一次，不因收尾状态被豁免。
   *
   * <p>意图：锁定真实 SQL 计数的「失败与取消同样计数」语义——三种终态各消费一次后额度耗尽；显式恢复暂停后，新建 Run 因额度耗尽被确定性拒绝，而不是被 门禁或活动 Run 拒绝。
   */
  @Test
  void terminalRunsOfEveryStatusConsumeBudget() {
    String agent = createAgent();
    UUID projectId = createProjectWithStages("终态消耗", agent, agent, 3);
    Issue issue = createIssue(projectId);
    issueLifecycleService.transition(issue.getId(), issue.getVersion(), key("t"), "DESIGN");

    IssueRun failedRun = issueRunService.acceptRun(issue.getId(), key("accept"));
    issueRunService.finishRun(
        failedRun.getId(),
        failedRun.getVersion(),
        key("fail"),
        IssueRunStatus.FAILED,
        failedRun.getStartEntryId(),
        null,
        null,
        "boom");
    assertEquals(
        1L,
        issueStageBudgetService.getStageBudget(issue.getId(), "DESIGN").usedRuns(),
        "FAILED run consumes one");

    Issue afterFail = issueEditingService.getIssue(issue.getId());
    issueLifecycleService.resumeIssue(afterFail.getId(), afterFail.getVersion(), key("resume"));

    IssueRun cancelledRun = issueRunService.acceptRun(issue.getId(), key("accept"));
    issueRunService.finishRun(
        cancelledRun.getId(),
        cancelledRun.getVersion(),
        key("cancel"),
        IssueRunStatus.CANCELLED,
        appendHistoryEntry(cancelledRun.getThreadId()),
        null,
        null,
        null);
    assertEquals(
        2L,
        issueStageBudgetService.getStageBudget(issue.getId(), "DESIGN").usedRuns(),
        "CANCELLED run consumes one");

    Issue afterCancel = issueEditingService.getIssue(issue.getId());
    issueLifecycleService.resumeIssue(afterCancel.getId(), afterCancel.getVersion(), key("resume"));

    IssueRun thirdRun = issueRunService.acceptRun(issue.getId(), key("accept"));
    issueRunService.finishRun(
        thirdRun.getId(),
        thirdRun.getVersion(),
        key("fail-2"),
        IssueRunStatus.FAILED,
        appendHistoryEntry(thirdRun.getThreadId()),
        null,
        null,
        "Run stopped with in-flight side effects; outcome unknown");
    StageBudgetView exhausted = issueStageBudgetService.getStageBudget(issue.getId(), "DESIGN");
    assertEquals(3L, exhausted.usedRuns(), "a second FAILED run consumes one");
    assertEquals(0L, exhausted.remainingRuns());

    // 先显式解除 ERROR 暂停；此后唯一阻塞新 Run 的因素是额度耗尽。
    Issue afterFailure = issueEditingService.getIssue(issue.getId());
    issueLifecycleService.resumeIssue(
        afterFailure.getId(), afterFailure.getVersion(), key("resume"));

    DomainException budgetExhausted =
        assertValidation(() -> issueRunService.acceptRun(issue.getId(), key("accept")));
    assertTrue(
        budgetExhausted.getMessage().contains("budget is exhausted"), budgetExhausted.getMessage());
    assertEquals(
        3L, count("select count(*) from project_issue_run where issue_id = ?", issue.getId()));
  }
}
