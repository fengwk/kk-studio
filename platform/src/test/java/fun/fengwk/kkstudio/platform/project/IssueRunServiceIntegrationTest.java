package fun.fengwk.kkstudio.platform.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import fun.fengwk.kkstudio.platform.error.AiResourceNotFoundException;
import fun.fengwk.kkstudio.platform.error.AiValidationException;
import fun.fengwk.kkstudio.platform.project.model.Issue;
import fun.fengwk.kkstudio.platform.project.model.IssueActivity;
import fun.fengwk.kkstudio.platform.project.model.IssueActivityActorType;
import fun.fengwk.kkstudio.platform.project.model.IssueActivityKind;
import fun.fengwk.kkstudio.platform.project.model.IssueAgentSession;
import fun.fengwk.kkstudio.platform.project.model.IssueDependency;
import fun.fengwk.kkstudio.platform.project.model.IssueRun;
import fun.fengwk.kkstudio.platform.project.model.IssueRunOutcome;
import fun.fengwk.kkstudio.platform.project.model.IssueRunRole;
import fun.fengwk.kkstudio.platform.project.model.IssueRunStatus;
import fun.fengwk.kkstudio.platform.project.model.IssueStatus;
import fun.fengwk.kkstudio.platform.project.model.Project;
import fun.fengwk.kkstudio.platform.project.model.ReviewDecision;
import fun.fengwk.kkstudio.platform.project.repo.IssueActivityRepository;
import fun.fengwk.kkstudio.platform.project.repo.IssueAgentSessionRepository;
import fun.fengwk.kkstudio.platform.project.repo.IssueDependencyRepository;
import fun.fengwk.kkstudio.platform.project.repo.IssueRepository;
import fun.fengwk.kkstudio.platform.project.repo.IssueRunRepository;
import fun.fengwk.kkstudio.platform.project.service.IssueRunService;
import fun.fengwk.kkstudio.platform.project.service.IssueService;
import fun.fengwk.kkstudio.platform.project.service.ProjectService;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 验证 IssueRunService 运行生命周期及围栏校验： 包含 Executor 启动约束、Complete/Review 状态机跃迁、AGENT/HUMAN 评审双模式、 失败不改
 * Issue 状态、明确 Retry 追加事实流、打回阈值迁移至 BLOCKED 以及稳定的 IssueAgentSession 归属查询。
 */
class IssueRunServiceIntegrationTest extends ProjectTestSupport {

  @Autowired private IssueRunService issueRunService;
  @Autowired private IssueService issueService;
  @Autowired private ProjectService projectService;
  @Autowired private IssueRepository issueRepository;
  @Autowired private IssueRunRepository issueRunRepository;
  @Autowired private IssueActivityRepository issueActivityRepository;
  @Autowired private IssueAgentSessionRepository issueAgentSessionRepository;
  @Autowired private IssueDependencyRepository issueDependencyRepository;

  @Test
  void testStartExecutorRunSuccessAndFences() {
    String agent1 = createTestAgent();
    String agent2 = createTestAgent();
    Project project = projectService.createProject("Run Proj", "Desc", true, 3);
    UUID projId = project.getId();

    Issue issue = issueService.createIssue(projId, "Task", "Desc", agent1, null, IssueStatus.TODO);
    UUID issueId = issue.getId();

    // Assignee 不匹配时拒绝启动
    assertThrows(
        AiValidationException.class,
        () ->
            issueRunService.startExecutorRun(issueId, agent2, Instant.now().plusSeconds(3600), 10));

    // 成功启动 Executor Run
    IssueRun run =
        issueRunService.startExecutorRun(issueId, agent1, Instant.now().plusSeconds(3600), 10);
    assertNotNull(run.getId());
    assertEquals(1L, run.getOrdinal());
    assertEquals(IssueRunRole.EXECUTOR, run.getRole());
    assertEquals(agent1, run.getAgentName());
    assertEquals(IssueRunStatus.RUNNING, run.getStatus());
    assertNull(run.getOutcome());
    assertEquals(0L, run.getObservedActivitySequence());

    // Issue 状态同步跃迁至 IN_PROGRESS
    Issue currentIssue = issueService.getIssue(issueId);
    assertEquals(IssueStatus.IN_PROGRESS, currentIssue.getStatus());

    // 已有活跃 Run 时禁止启动第二个活跃 Run
    assertThrows(
        AiValidationException.class,
        () ->
            issueRunService.startExecutorRun(issueId, agent1, Instant.now().plusSeconds(3600), 10));
  }

  @Test
  void testStartExecutorRunBlockedByDependencyFails() {
    String agent = createTestAgent();
    Project project = projectService.createProject("Blocked Run Proj", "Desc", true, 3);
    UUID projId = project.getId();

    Issue dep = issueService.createIssue(projId, "Dep", "Desc", agent, null, IssueStatus.TODO);
    Issue target =
        issueService.createIssue(projId, "Target", "Desc", agent, null, IssueStatus.TODO);

    issueDependencyRepository.addDependency(
        IssueDependency.builder()
            .issueId(target.getId())
            .dependsOnIssueId(dep.getId())
            .projectId(projId)
            .build());

    // 依赖未完成时禁止启动 Executor
    assertThrows(
        AiValidationException.class,
        () ->
            issueRunService.startExecutorRun(
                target.getId(), agent, Instant.now().plusSeconds(3600), 10));

    // 依赖变更为 DONE 后允许启动
    dep.setStatus(IssueStatus.DONE);
    issueRepository.updateById(dep, dep.getVersion());

    IssueRun run =
        issueRunService.startExecutorRun(
            target.getId(), agent, Instant.now().plusSeconds(3600), 10);
    assertNotNull(run);
    assertEquals(IssueRunStatus.RUNNING, run.getStatus());
  }

  @Test
  void testCompleteExecutorRunSuccess() {
    String agent = createTestAgent();
    Project project = projectService.createProject("Submit Proj", "Desc", true, 3);
    Issue issue =
        issueService.createIssue(
            project.getId(), "Submit Issue", "Desc", agent, null, IssueStatus.TODO);
    UUID issueId = issue.getId();

    IssueRun run =
        issueRunService.startExecutorRun(issueId, agent, Instant.now().plusSeconds(3600), 10);
    UUID runId = run.getId();

    // completeExecutorRun 成功完成 Run 并将 Issue 推进至 IN_REVIEW
    IssueRun submitted =
        issueRunService.completeExecutorRun(runId, "action:1", "Summary", "Verification");
    assertEquals(IssueRunStatus.COMPLETED, submitted.getStatus());
    assertEquals(IssueRunOutcome.SUBMITTED, submitted.getOutcome());
    assertEquals("action:1", submitted.getTerminalActionId());
    assertNotNull(submitted.getCompletedAt());
    assertTrue(submitted.getResult().contains("Summary"));
    assertTrue(submitted.getResult().contains("Verification"));

    // Issue 状态同步跃迁至 IN_REVIEW
    assertEquals(IssueStatus.IN_REVIEW, issueService.getIssue(issueId).getStatus());
  }

  @Test
  void testRequestInputLeavesRunWaitingHuman() {
    String agent = createTestAgent();
    Project project = projectService.createProject("Request Input Proj", "Desc", true, 3);
    Issue issue =
        issueService.createIssue(project.getId(), "Task", "Desc", agent, null, IssueStatus.TODO);
    UUID issueId = issue.getId();

    IssueRun run =
        issueRunService.startExecutorRun(issueId, agent, Instant.now().plusSeconds(3600), 10);
    UUID runId = run.getId();

    // requestInput 成功后 Run 变为 WAITING_HUMAN，Issue 仍保持 IN_PROGRESS
    IssueRun waiting = issueRunService.requestInput(runId, "Need clarification?", "Detail context");
    assertEquals(IssueRunStatus.WAITING_HUMAN, waiting.getStatus());
    assertEquals("Need clarification?\n\nContext:\nDetail context", waiting.getWaitingReason());
    assertEquals(IssueStatus.IN_PROGRESS, issueService.getIssue(issueId).getStatus());

    // Activity 事实流记录：初始 SPEC_CHANGE 与追加的 INSTRUCTION
    List<IssueActivity> activities = issueActivityRepository.listByIssueId(issueId);
    assertEquals(2, activities.size());
    assertEquals(IssueActivityKind.SPEC_CHANGE, activities.get(0).getKind());
    assertEquals(IssueActivityKind.INSTRUCTION, activities.get(1).getKind());
    assertEquals(IssueActivityActorType.AGENT, activities.get(1).getActorType());
    assertEquals(agent, activities.get(1).getActorAgentName());
  }

  @Test
  void testAgentReviewApproveAndRequestChanges() {
    String execAgent = createTestAgent();
    String revAgent = createTestAgent();
    Project project = projectService.createProject("Review Proj", "Desc", true, 3);
    UUID projId = project.getId();

    // 创建带 reviewerAgent 的 Issue
    Issue issue =
        issueService.createIssue(
            projId, "Review Task", "Desc", execAgent, revAgent, IssueStatus.TODO);
    UUID issueId = issue.getId();

    // Executor 执行并提交
    IssueRun execRun =
        issueRunService.startExecutorRun(issueId, execAgent, Instant.now().plusSeconds(3600), 10);
    issueRunService.completeExecutorRun(execRun.getId(), "exec:1", "Done", "Passed");

    // 启动 Reviewer Run
    IssueRun revRun =
        issueRunService.startReviewerRun(issueId, revAgent, Instant.now().plusSeconds(3600), 10);
    assertEquals(IssueRunRole.REVIEWER, revRun.getRole());
    assertEquals(revAgent, revRun.getAgentName());
    assertEquals(execRun.getId(), revRun.getSubmissionRunId());

    // 分支 1: REQUEST_CHANGES 导致 Issue 退回 TODO 并记录 REVIEW_DECISION Activity
    IssueRun reviewed =
        issueRunService.reviewByAgent(
            revRun.getId(), revAgent, "rev:1", ReviewDecision.REQUEST_CHANGES, "Please fix bug");
    assertEquals(IssueRunStatus.COMPLETED, reviewed.getStatus());
    assertEquals(IssueRunOutcome.CHANGES_REQUESTED, reviewed.getOutcome());

    Issue issueAfterChanges = issueService.getIssue(issueId);
    assertEquals(IssueStatus.TODO, issueAfterChanges.getStatus());

    List<IssueActivity> activities = issueActivityRepository.listByIssueId(issueId);
    assertEquals(2, activities.size());
    assertEquals(IssueActivityKind.SPEC_CHANGE, activities.get(0).getKind());
    assertEquals(IssueActivityKind.REVIEW_DECISION, activities.get(1).getKind());
    assertEquals(ReviewDecision.REQUEST_CHANGES, activities.get(1).getDecision());

    // 重新执行 Executor -> Submit -> Reviewer -> APPROVE -> Issue DONE
    IssueRun execRun2 =
        issueRunService.startExecutorRun(issueId, execAgent, Instant.now().plusSeconds(3600), 10);
    issueRunService.completeExecutorRun(execRun2.getId(), "exec:2", "Fixed", "Passed");

    IssueRun revRun2 =
        issueRunService.startReviewerRun(issueId, revAgent, Instant.now().plusSeconds(3600), 10);
    issueRunService.reviewByAgent(
        revRun2.getId(), revAgent, "rev:2", ReviewDecision.APPROVE, "Looks good");

    Issue finalIssue = issueService.getIssue(issueId);
    assertEquals(IssueStatus.DONE, finalIssue.getStatus());
  }

  @Test
  void testHumanReviewApproveAndRequestChanges() {
    String execAgent = createTestAgent();
    Project project = projectService.createProject("Human Review Proj", "Desc", true, 3);
    UUID projId = project.getId();

    // reviewerAgentName 为空表示人工评审
    Issue issue =
        issueService.createIssue(
            projId, "Human Review Task", "Desc", execAgent, null, IssueStatus.TODO);
    UUID issueId = issue.getId();

    IssueRun execRun =
        issueRunService.startExecutorRun(issueId, execAgent, Instant.now().plusSeconds(3600), 10);
    issueRunService.completeExecutorRun(execRun.getId(), "exec:sub", "Done", "Passed");

    // 人工直接评审批准
    issueRunService.reviewByHuman(
        issueId, ReviewDecision.APPROVE, "Manual review approved", "human:appr");

    assertEquals(IssueStatus.DONE, issueService.getIssue(issueId).getStatus());
    List<IssueActivity> activities = issueActivityRepository.listByIssueId(issueId);
    assertEquals(2, activities.size());
    assertEquals(IssueActivityKind.SPEC_CHANGE, activities.get(0).getKind());
    assertEquals(IssueActivityKind.REVIEW_DECISION, activities.get(1).getKind());
    assertEquals(IssueActivityActorType.HUMAN, activities.get(1).getActorType());
    assertEquals(ReviewDecision.APPROVE, activities.get(1).getDecision());
  }

  @Test
  void testHumanReviewChangesRequestedAndMaxRejectionsToBlocked() {
    String execAgent = createTestAgent();
    Project project = projectService.createProject("Blocked Threshold Proj", "Desc", true, 2);
    UUID projId = project.getId();

    Issue issue =
        issueService.createIssue(
            projId, "Threshold Task", "Desc", execAgent, null, IssueStatus.TODO);
    UUID issueId = issue.getId();

    // 第 1 次执行与打回（累计 1 < 2，退回 TODO）
    IssueRun r1 =
        issueRunService.startExecutorRun(issueId, execAgent, Instant.now().plusSeconds(3600), 10);
    issueRunService.completeExecutorRun(r1.getId(), "sub:1", "Done", "Passed");
    issueRunService.reviewByHuman(
        issueId, ReviewDecision.REQUEST_CHANGES, "Fix 1", "human:reject:1");
    assertEquals(IssueStatus.TODO, issueService.getIssue(issueId).getStatus());

    // 第 2 次执行与打回（累计 2 >= 2，达到阈值，转为 BLOCKED）
    IssueRun r2 =
        issueRunService.startExecutorRun(issueId, execAgent, Instant.now().plusSeconds(3600), 10);
    issueRunService.completeExecutorRun(r2.getId(), "sub:2", "Done again", "Passed");
    issueRunService.reviewByHuman(
        issueId, ReviewDecision.REQUEST_CHANGES, "Fix 2", "human:reject:2");
    assertEquals(IssueStatus.BLOCKED, issueService.getIssue(issueId).getStatus());

    // BLOCKED 状态下禁止自动启动 Executor Run
    assertThrows(
        AiValidationException.class,
        () ->
            issueRunService.startExecutorRun(
                issueId, execAgent, Instant.now().plusSeconds(3600), 10));
  }

  @Test
  void testFailRunDoesNotAlterIssueStatusAndRetryAppendsActivity() {
    String agent = createTestAgent();
    Project project = projectService.createProject("Fail Proj", "Desc", true, 3);
    Issue issue =
        issueService.createIssue(
            project.getId(), "Fail Task", "Desc", agent, null, IssueStatus.TODO);
    UUID issueId = issue.getId();

    IssueRun run =
        issueRunService.startExecutorRun(issueId, agent, Instant.now().plusSeconds(3600), 10);
    UUID runId = run.getId();

    // failRun 将 Run 置为 FAILED，但 Issue 必须保持 IN_PROGRESS，等待处理
    IssueRun failed = issueRunService.failRun(runId, IssueRunStatus.FAILED, "Budget exhausted");
    assertEquals(IssueRunStatus.FAILED, failed.getStatus());
    assertEquals("Budget exhausted", failed.getWaitingReason());
    assertEquals(IssueStatus.IN_PROGRESS, issueService.getIssue(issueId).getStatus());

    // 明确 retry 追加 RETRY Activity，不复活旧 Run
    IssueActivity retryActivity = issueRunService.retryRun(issueId, "retry-key-1");
    assertEquals(IssueActivityKind.RETRY, retryActivity.getKind());
    assertEquals("retry-key-1", retryActivity.getIdempotencyKey());

    // 幂等重放返回同一 Activity
    IssueActivity retryReplay = issueRunService.retryRun(issueId, "retry-key-1");
    assertEquals(retryActivity.getSequence(), retryReplay.getSequence());
  }

  @Test
  void testIssueAgentSessionLookups() {
    String agent = createTestAgent();
    Project project = projectService.createProject("Session Proj", "Desc", true, 3);
    Issue issue =
        issueService.createIssue(
            project.getId(), "Session Task", "Desc", agent, null, IssueStatus.TODO);
    UUID issueId = issue.getId();

    UUID sessionId = createHarnessSession();
    UUID threadId = createHarnessThread(sessionId);
    UUID agentSessionId = UUID.randomUUID();

    IssueAgentSession agentSession =
        IssueAgentSession.builder()
            .id(agentSessionId)
            .issueId(issueId)
            .agentName(agent)
            .sessionId(sessionId)
            .threadId(threadId)
            .build();
    issueAgentSessionRepository.bindOrGet(agentSession);

    // 查询验证
    IssueAgentSession byIssueAgent = issueRunService.getAgentSession(issueId, agent);
    assertNotNull(byIssueAgent);
    assertEquals(agentSessionId, byIssueAgent.getId());
    assertEquals(sessionId, byIssueAgent.getSessionId());
    assertEquals(threadId, byIssueAgent.getThreadId());

    IssueAgentSession bySession = issueRunService.findAgentSession(sessionId);
    assertNotNull(bySession);
    assertEquals(agentSessionId, bySession.getId());

    IssueAgentSession byThread = issueRunService.findAgentSessionByThreadId(threadId);
    assertNotNull(byThread);
    assertEquals(agentSessionId, byThread.getId());
  }

  @Test
  void testRunLookupsAndRepositoryDirectOperations() {
    String agent = createTestAgent();
    Project project = projectService.createProject("Lookups Proj", "Desc", true, 3);
    Issue issue =
        issueService.createIssue(project.getId(), "T", "D", agent, null, IssueStatus.TODO);
    UUID issueId = issue.getId();

    IssueRun run1 =
        issueRunService.startExecutorRun(issueId, agent, Instant.now().plusSeconds(3600), 10);
    UUID runId1 = run1.getId();

    // getRun
    assertEquals(runId1, issueRunService.getRun(runId1).getId());
    assertThrows(
        AiResourceNotFoundException.class, () -> issueRunService.getRun(UUID.randomUUID()));

    // getActiveRun
    assertEquals(runId1, issueRunService.getActiveRun(issueId).getId());

    // listRuns
    List<IssueRun> runs = issueRunService.listRuns(issueId);
    assertEquals(1, runs.size());

    // getLatestRun
    issueRunService.failRun(runId1, IssueRunStatus.FAILED, "error");
    assertNull(issueRunService.getActiveRun(issueId));
    assertEquals(runId1, issueRunService.getLatestRun(issueId).getId());

    // repository deleteById
    assertFalse(issueRunRepository.deleteById(runId1, 999L));
    assertTrue(issueRunRepository.deleteById(runId1, issueRunService.getRun(runId1).getVersion()));
    assertThrows(AiResourceNotFoundException.class, () -> issueRunService.getRun(runId1));
  }

  @Test
  void testRunValidationsAndEdgeCases() {
    String agent = createTestAgent();
    Project project = projectService.createProject("Edge Proj", "Desc", true, 3);
    Issue issue =
        issueService.createIssue(project.getId(), "T", "D", agent, null, IssueStatus.TODO);
    UUID issueId = issue.getId();

    // startReviewerRun when not IN_REVIEW
    assertThrows(
        AiValidationException.class,
        () ->
            issueRunService.startReviewerRun(issueId, agent, Instant.now().plusSeconds(3600), 10));

    // failRun invalid status
    IssueRun run =
        issueRunService.startExecutorRun(issueId, agent, Instant.now().plusSeconds(3600), 10);
    assertThrows(
        AiValidationException.class,
        () -> issueRunService.failRun(run.getId(), IssueRunStatus.COMPLETED, "reason"));

    // requestInput validations
    assertThrows(
        AiValidationException.class, () -> issueRunService.requestInput(run.getId(), "  ", "ctx"));

    // retryRun idempotency replay
    issueRunService.failRun(run.getId(), IssueRunStatus.FAILED, "reason");
    IssueActivity retry1 = issueRunService.retryRun(issueId, "k-replay");
    IssueActivity retry2 = issueRunService.retryRun(issueId, "k-replay");
    assertEquals(retry1.getSequence(), retry2.getSequence());
  }

  @Test
  void testMoreRunValidationsAndEdgeCases() {
    String execAgent = createTestAgent();
    String revAgent = createTestAgent();
    Project project = projectService.createProject("More Val Proj", "Desc", true, 3);
    UUID projId = project.getId();

    // startExecutorRun blank agent or not found
    assertThrows(
        AiValidationException.class,
        () ->
            issueRunService.startExecutorRun(
                UUID.randomUUID(), "  ", Instant.now().plusSeconds(3600), 10));
    assertThrows(
        AiResourceNotFoundException.class,
        () ->
            issueRunService.startExecutorRun(
                UUID.randomUUID(), execAgent, Instant.now().plusSeconds(3600), 10));

    // startReviewerRun blank agent or not found
    assertThrows(
        AiValidationException.class,
        () ->
            issueRunService.startReviewerRun(
                UUID.randomUUID(), "  ", Instant.now().plusSeconds(3600), 10));
    assertThrows(
        AiResourceNotFoundException.class,
        () ->
            issueRunService.startReviewerRun(
                UUID.randomUUID(), execAgent, Instant.now().plusSeconds(3600), 10));

    // completeExecutorRun not found
    assertThrows(
        AiResourceNotFoundException.class,
        () -> issueRunService.completeExecutorRun(UUID.randomUUID(), "act", "s", "v"));

    // requestInput not found
    assertThrows(
        AiResourceNotFoundException.class,
        () -> issueRunService.requestInput(UUID.randomUUID(), "q", "ctx"));

    // reviewByHuman issue not found
    assertThrows(
        AiResourceNotFoundException.class,
        () -> issueRunService.reviewByHuman(UUID.randomUUID(), ReviewDecision.APPROVE, "s", "k"));

    // failRun not found
    assertThrows(
        AiResourceNotFoundException.class,
        () -> issueRunService.failRun(UUID.randomUUID(), IssueRunStatus.FAILED, "r"));

    // retryRun not found
    assertThrows(
        AiResourceNotFoundException.class, () -> issueRunService.retryRun(UUID.randomUUID(), "k"));

    // UNKNOWN 运行支持 retryRun
    Issue issue =
        issueService.createIssue(projId, "Unknown Task", "Desc", execAgent, null, IssueStatus.TODO);
    IssueRun execRun =
        issueRunService.startExecutorRun(
            issue.getId(), execAgent, Instant.now().plusSeconds(3600), 10);
    issueRunService.failRun(execRun.getId(), IssueRunStatus.UNKNOWN, "lost connection");
    IssueActivity unknownRetry = issueRunService.retryRun(issue.getId(), "k-unknown");
    assertEquals(IssueActivityKind.RETRY, unknownRetry.getKind());
  }

  @Test
  void testReviewerAgentSameAsExecutorAssigneeRejected() {
    String agent = createTestAgent();
    Project project = projectService.createProject("Self Review Proj", "Desc", true, 3);

    // 1. 创建 Issue 时 assigneeAgent 与 reviewerAgent 同名直接拒绝
    AiValidationException exCreate =
        assertThrows(
            AiValidationException.class,
            () ->
                issueService.createIssue(
                    project.getId(), "Self Review", "Desc", agent, agent, IssueStatus.TODO));
    assertEquals(
        "assigneeAgentName and reviewerAgentName must not be the same agent",
        exCreate.getMessage());

    // 2. 正常 Issue 在执行完成后，非指定 reviewerAgent 尝试启动审查 Run 被拒绝
    String reviewer = createTestAgent();
    Issue issue =
        issueService.createIssue(
            project.getId(), "Normal Task", "Desc", agent, reviewer, IssueStatus.TODO);
    UUID issueId = issue.getId();

    IssueRun execRun =
        issueRunService.startExecutorRun(issueId, agent, Instant.now().plusSeconds(3600), 10);
    issueRunService.completeExecutorRun(execRun.getId(), "exec:done", "Done", "Passed");

    AiValidationException exStart =
        assertThrows(
            AiValidationException.class,
            () ->
                issueRunService.startReviewerRun(
                    issueId, agent, Instant.now().plusSeconds(3600), 10));
    assertEquals(
        "Cannot start reviewer run: agentName mismatch with issue reviewer", exStart.getMessage());
  }
}
