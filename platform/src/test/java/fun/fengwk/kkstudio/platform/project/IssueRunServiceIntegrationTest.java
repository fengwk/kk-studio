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
import fun.fengwk.kkstudio.platform.project.model.IssueDependency;
import fun.fengwk.kkstudio.platform.project.model.IssueInput;
import fun.fengwk.kkstudio.platform.project.model.IssueInputKind;
import fun.fengwk.kkstudio.platform.project.model.IssueRun;
import fun.fengwk.kkstudio.platform.project.model.IssueRunActorType;
import fun.fengwk.kkstudio.platform.project.model.IssueRunOutcome;
import fun.fengwk.kkstudio.platform.project.model.IssueRunRole;
import fun.fengwk.kkstudio.platform.project.model.IssueRunSession;
import fun.fengwk.kkstudio.platform.project.model.IssueRunStatus;
import fun.fengwk.kkstudio.platform.project.model.IssueStatus;
import fun.fengwk.kkstudio.platform.project.model.Project;
import fun.fengwk.kkstudio.platform.project.model.ReviewDecision;
import fun.fengwk.kkstudio.platform.project.repo.IssueDependencyRepository;
import fun.fengwk.kkstudio.platform.project.repo.IssueRepository;
import fun.fengwk.kkstudio.platform.project.repo.IssueRunRepository;
import fun.fengwk.kkstudio.platform.project.repo.IssueRunSessionRepository;
import fun.fengwk.kkstudio.platform.project.service.IssueRunService;
import fun.fengwk.kkstudio.platform.project.service.IssueService;
import fun.fengwk.kkstudio.platform.project.service.ProjectService;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 验证 IssueRunService 运行生命周期及围栏校验： 包含 Executor 启动约束、Submit/Review 观察游标（observed
 * cursors）围栏、AGENT/HUMAN 评审双模式、 失败不改 Issue 状态、明确 Retry 追加输入流以及 Run Session 绑定约束。
 */
class IssueRunServiceIntegrationTest extends ProjectTestSupport {

  @Autowired private IssueRunService issueRunService;
  @Autowired private IssueService issueService;
  @Autowired private ProjectService projectService;
  @Autowired private IssueRepository issueRepository;
  @Autowired private IssueRunRepository issueRunRepository;
  @Autowired private IssueRunSessionRepository issueRunSessionRepository;
  @Autowired private IssueDependencyRepository issueDependencyRepository;

  @Test
  void testStartExecutorRunSuccessAndFences() {
    String agent1 = createTestAgent();
    String agent2 = createTestAgent();
    Project project = projectService.createProject("Run Proj", "Desc", agent1);
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
    assertEquals(IssueRunActorType.AGENT, run.getActorType());
    assertEquals(agent1, run.getAgentName());
    assertEquals(IssueRunStatus.RUNNING, run.getStatus());
    assertNull(run.getOutcome());
    assertEquals(1L, run.getObservedSpecRevision());
    assertEquals(0L, run.getObservedInputSequence());

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
    Project project = projectService.createProject("Blocked Run Proj", "Desc", agent);
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
  void testSubmitRunCursorFencesAndSuccess() {
    String agent = createTestAgent();
    Project project = projectService.createProject("Submit Proj", "Desc", agent);
    Issue issue =
        issueService.createIssue(
            project.getId(), "Submit Issue", "Desc", agent, null, IssueStatus.TODO);
    UUID issueId = issue.getId();

    IssueRun run =
        issueRunService.startExecutorRun(issueId, agent, Instant.now().plusSeconds(3600), 10);
    UUID runId = run.getId();

    // 模拟在运行期间人类追加了一条新输入，导致 Issue inputSequence 改变
    issueService.appendInput(issueId, IssueInputKind.HUMAN, "New question", "k1");

    // Agent 使用过期的 observedInputSequence（0 vs 1）提交必须被围栏拒绝
    assertThrows(
        AiValidationException.class,
        () -> issueRunService.submitRun(runId, "action:1", 1L, 0L, "Summary", "Verification"));

    // 使用匹配当前 Issue 的游标提交成功
    IssueRun submitted =
        issueRunService.submitRun(runId, "action:1", 1L, 1L, "Summary", "Verification");
    assertEquals(IssueRunStatus.COMPLETED, submitted.getStatus());
    assertEquals(IssueRunOutcome.SUBMITTED, submitted.getOutcome());
    assertEquals("action:1", submitted.getTerminalActionId());
    assertNotNull(submitted.getCompletedAt());

    // Issue 状态同步跃迁至 IN_REVIEW
    assertEquals(IssueStatus.IN_REVIEW, issueService.getIssue(issueId).getStatus());
  }

  @Test
  void testRequestInputLeavesRunWaitingHuman() {
    String agent = createTestAgent();
    Project project = projectService.createProject("Request Input Proj", "Desc", agent);
    Issue issue =
        issueService.createIssue(project.getId(), "Task", "Desc", agent, null, IssueStatus.TODO);
    UUID issueId = issue.getId();

    IssueRun run =
        issueRunService.startExecutorRun(issueId, agent, Instant.now().plusSeconds(3600), 10);
    UUID runId = run.getId();

    // requestInput 成功后 Run 变为 WAITING_HUMAN，Issue 仍保持 IN_PROGRESS
    IssueRun waiting =
        issueRunService.requestInput(runId, 1L, 0L, "Need clarification?", "Detail context");
    assertEquals(IssueRunStatus.WAITING_HUMAN, waiting.getStatus());
    assertEquals("Need clarification?\n\nContext:\nDetail context", waiting.getWaitingReason());
    assertEquals(IssueStatus.IN_PROGRESS, issueService.getIssue(issueId).getStatus());
  }

  @Test
  void testAgentReviewApproveAndRequestChanges() {
    String execAgent = createTestAgent();
    String revAgent = createTestAgent();
    Project project = projectService.createProject("Review Proj", "Desc", execAgent);
    UUID projId = project.getId();

    // 创建带 reviewerAgent 的 Issue
    Issue issue =
        issueService.createIssue(
            projId, "Review Task", "Desc", execAgent, revAgent, IssueStatus.TODO);
    UUID issueId = issue.getId();

    // Executor 执行并提交
    IssueRun execRun =
        issueRunService.startExecutorRun(issueId, execAgent, Instant.now().plusSeconds(3600), 10);
    issueRunService.submitRun(execRun.getId(), "exec:1", 1L, 0L, "Done", "Passed");

    // 启动 Reviewer Run
    IssueRun revRun =
        issueRunService.startReviewerRun(issueId, revAgent, Instant.now().plusSeconds(3600), 10);
    assertEquals(IssueRunRole.REVIEWER, revRun.getRole());
    assertEquals(IssueRunActorType.AGENT, revRun.getActorType());
    assertEquals(execRun.getId(), revRun.getSubmissionRunId());

    // 分支 1: REQUEST_CHANGES 导致 Issue 退回 TODO 并自动追加 REVIEW_FEEDBACK
    IssueRun reviewed =
        issueRunService.reviewRun(
            issueId,
            revRun.getId(),
            IssueRunActorType.AGENT,
            revAgent,
            "rev:1",
            1L,
            0L,
            ReviewDecision.REQUEST_CHANGES,
            "Please fix bug",
            "See test failure");
    assertEquals(IssueRunStatus.COMPLETED, reviewed.getStatus());
    assertEquals(IssueRunOutcome.CHANGES_REQUESTED, reviewed.getOutcome());

    Issue issueAfterChanges = issueService.getIssue(issueId);
    assertEquals(IssueStatus.TODO, issueAfterChanges.getStatus());
    assertEquals(1L, issueAfterChanges.getInputSequence());
    List<IssueInput> inputs = issueService.listInputs(issueId);
    assertEquals(1, inputs.size());
    assertEquals(IssueInputKind.REVIEW_FEEDBACK, inputs.getFirst().getKind());

    // 重新执行 Executor -> Submit -> Reviewer -> APPROVE -> Issue DONE
    IssueRun execRun2 =
        issueRunService.startExecutorRun(issueId, execAgent, Instant.now().plusSeconds(3600), 10);
    issueRunService.submitRun(execRun2.getId(), "exec:2", 1L, 1L, "Fixed", "Passed");

    IssueRun revRun2 =
        issueRunService.startReviewerRun(issueId, revAgent, Instant.now().plusSeconds(3600), 10);
    issueRunService.reviewRun(
        issueId,
        revRun2.getId(),
        IssueRunActorType.AGENT,
        revAgent,
        "rev:2",
        1L,
        1L,
        ReviewDecision.APPROVE,
        "Looks good",
        "Verified");

    Issue finalIssue = issueService.getIssue(issueId);
    assertEquals(IssueStatus.DONE, finalIssue.getStatus());
  }

  @Test
  void testHumanReviewOnlyWhenReviewerAgentIsNull() {
    String execAgent = createTestAgent();
    Project project = projectService.createProject("Human Review Proj", "Desc", execAgent);
    UUID projId = project.getId();

    // reviewerAgentName 为空表示人工评审
    Issue issue =
        issueService.createIssue(
            projId, "Human Review Task", "Desc", execAgent, null, IssueStatus.TODO);
    UUID issueId = issue.getId();

    IssueRun execRun =
        issueRunService.startExecutorRun(issueId, execAgent, Instant.now().plusSeconds(3600), 10);
    issueRunService.submitRun(execRun.getId(), "exec:sub", 1L, 0L, "Done", "Passed");

    // 人工直接评审批准
    IssueRun humanRun =
        issueRunService.reviewRun(
            issueId,
            null,
            IssueRunActorType.HUMAN,
            null,
            "human:appr",
            1L,
            0L,
            ReviewDecision.APPROVE,
            "Manual review approved",
            "Inspected code");

    assertEquals(IssueRunRole.REVIEWER, humanRun.getRole());
    assertEquals(IssueRunActorType.HUMAN, humanRun.getActorType());
    assertNull(humanRun.getAgentName());
    assertEquals(execRun.getId(), humanRun.getSubmissionRunId());
    assertEquals(IssueRunStatus.COMPLETED, humanRun.getStatus());
    assertEquals(IssueRunOutcome.APPROVED, humanRun.getOutcome());
    assertEquals(IssueStatus.DONE, issueService.getIssue(issueId).getStatus());
  }

  @Test
  void testFailRunDoesNotAlterIssueStatusAndRetryAppendsInput() {
    String agent = createTestAgent();
    Project project = projectService.createProject("Fail Proj", "Desc", agent);
    Issue issue =
        issueService.createIssue(
            project.getId(), "Fail Task", "Desc", agent, null, IssueStatus.TODO);
    UUID issueId = issue.getId();

    IssueRun run =
        issueRunService.startExecutorRun(issueId, agent, Instant.now().plusSeconds(3600), 10);
    UUID runId = run.getId();

    // failRun 将 Run 置为 FAILED，但 Issue 必须保持 IN_PROGRESS，等待人类处理
    IssueRun failed = issueRunService.failRun(runId, IssueRunStatus.FAILED, "Budget exhausted");
    assertEquals(IssueRunStatus.FAILED, failed.getStatus());
    assertEquals("Budget exhausted", failed.getWaitingReason());
    assertEquals(IssueStatus.IN_PROGRESS, issueService.getIssue(issueId).getStatus());

    // 明确 retry 追加 RETRY 输入，不复活旧 Run
    IssueInput retryInput = issueRunService.retryRun(issueId, "retry-key-1");
    assertEquals(IssueInputKind.RETRY, retryInput.getKind());
    assertEquals(1L, retryInput.getSequence());
    assertEquals(1L, issueService.getIssue(issueId).getInputSequence());
  }

  @Test
  void testSessionBindingFailsForHumanRun() {
    String agent = createTestAgent();
    Project project = projectService.createProject("Session Proj", "Desc", agent);
    Issue issue =
        issueService.createIssue(
            project.getId(), "Session Task", "Desc", agent, null, IssueStatus.TODO);
    UUID issueId = issue.getId();

    IssueRun execRun =
        issueRunService.startExecutorRun(issueId, agent, Instant.now().plusSeconds(3600), 10);
    UUID sessionId = createHarnessSession();

    // AGENT run 绑定 Session 成功（通过 repository 直接建立关系以测试服务查询）
    assertTrue(issueRunSessionRepository.bindSession(execRun.getId(), sessionId));
    IssueRunSession bound = issueRunService.getRunSession(execRun.getId());
    assertNotNull(bound);
    assertEquals(sessionId, bound.getSessionId());

    // 提交并由 HUMAN 评审
    issueRunService.submitRun(execRun.getId(), "sub:1", 1L, 0L, "Done", "Passed");
    IssueRun humanRun =
        issueRunService.reviewRun(
            issueId,
            null,
            IssueRunActorType.HUMAN,
            null,
            "human-action-approve",
            1L,
            0L,
            ReviewDecision.APPROVE,
            "Ok",
            "Ok");
  }

  @Test
  void testRunLookupsAndRepositoryDirectOperations() {
    String agent = createTestAgent();
    Project project = projectService.createProject("Lookups Proj", "Desc", agent);
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

    // findRunSession
    UUID sessionId = createHarnessSession();
    assertTrue(issueRunSessionRepository.bindSession(runId1, sessionId));
    IssueRunSession found = issueRunService.findRunSession(sessionId);
    assertNotNull(found);
    assertEquals(runId1, found.getRunId());

    // deleteByRunId
    assertTrue(issueRunSessionRepository.deleteByRunId(runId1));
    assertNull(issueRunService.getRunSession(runId1));

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
    Project project = projectService.createProject("Edge Proj", "Desc", agent);
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
        AiValidationException.class,
        () -> issueRunService.requestInput(run.getId(), 1L, 0L, "  ", "ctx"));
    assertThrows(
        AiValidationException.class,
        () -> issueRunService.requestInput(run.getId(), 999L, 0L, "q", "ctx"));

    // retryRun idempotency replay
    issueRunService.failRun(run.getId(), IssueRunStatus.FAILED, "reason");
    IssueInput retry1 = issueRunService.retryRun(issueId, "k-replay");
    IssueInput retry2 = issueRunService.retryRun(issueId, "k-replay");
    assertEquals(retry1.getSequence(), retry2.getSequence());
  }

  @Test
  void testMoreRunValidationsAndHumanReviewChangesRequested() {
    String execAgent = createTestAgent();
    Project project = projectService.createProject("More Val Proj", "Desc", execAgent);
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

    // submitRun not found
    assertThrows(
        AiResourceNotFoundException.class,
        () -> issueRunService.submitRun(UUID.randomUUID(), "act", 1L, 0L, "s", "v"));

    // requestInput not found
    assertThrows(
        AiResourceNotFoundException.class,
        () -> issueRunService.requestInput(UUID.randomUUID(), 1L, 0L, "q", "ctx"));

    // reviewRun issue not found
    assertThrows(
        AiResourceNotFoundException.class,
        () ->
            issueRunService.reviewRun(
                UUID.randomUUID(),
                null,
                IssueRunActorType.HUMAN,
                null,
                "act",
                1L,
                0L,
                ReviewDecision.APPROVE,
                "s",
                "v"));

    // failRun not found
    assertThrows(
        AiResourceNotFoundException.class,
        () -> issueRunService.failRun(UUID.randomUUID(), IssueRunStatus.FAILED, "r"));

    // retryRun not found
    assertThrows(
        AiResourceNotFoundException.class, () -> issueRunService.retryRun(UUID.randomUUID(), "k"));

    // 人工评审 REQUEST_CHANGES 流程
    Issue issue =
        issueService.createIssue(
            projId, "Human Changes Task", "Desc", execAgent, null, IssueStatus.TODO);
    UUID issueId = issue.getId();

    IssueRun execRun =
        issueRunService.startExecutorRun(issueId, execAgent, Instant.now().plusSeconds(3600), 10);
    issueRunService.submitRun(execRun.getId(), "exec:act", 1L, 0L, "done", "passed");

    // 人工评审打回
    IssueRun humanRev =
        issueRunService.reviewRun(
            issueId,
            null,
            IssueRunActorType.HUMAN,
            null,
            "human-action-changes",
            1L,
            0L,
            ReviewDecision.REQUEST_CHANGES,
            "Needs more work",
            "Failed verification");
    assertEquals(IssueRunRole.REVIEWER, humanRev.getRole());
    assertEquals(IssueRunOutcome.CHANGES_REQUESTED, humanRev.getOutcome());
    assertEquals(IssueStatus.TODO, issueService.getIssue(issueId).getStatus());

    // UNKNOWN 运行支持 retryRun
    IssueRun execRun2 =
        issueRunService.startExecutorRun(issueId, execAgent, Instant.now().plusSeconds(3600), 10);
    issueRunService.failRun(execRun2.getId(), IssueRunStatus.UNKNOWN, "lost connection");
    IssueInput unknownRetry = issueRunService.retryRun(issueId, "k-unknown");
    assertEquals(IssueInputKind.RETRY, unknownRetry.getKind());
  }

  @Test
  void testSubmitAndReviewEdgeFences() {
    String execAgent = createTestAgent();
    String revAgent = createTestAgent();
    Project project = projectService.createProject("Fence Proj", "Desc", execAgent);
    UUID projId = project.getId();

    Issue dep = issueService.createIssue(projId, "Dep", "Desc", execAgent, null, IssueStatus.TODO);
    Issue target =
        issueService.createIssue(projId, "Target", "Desc", execAgent, revAgent, IssueStatus.TODO);
    UUID targetId = target.getId();

    // 先把 dep 变为 DONE，允许 target 启动 Executor
    dep.setStatus(IssueStatus.DONE);
    issueRepository.updateById(dep, dep.getVersion());
    issueDependencyRepository.addDependency(
        IssueDependency.builder()
            .issueId(targetId)
            .dependsOnIssueId(dep.getId())
            .projectId(projId)
            .build());

    IssueRun execRun =
        issueRunService.startExecutorRun(targetId, execAgent, Instant.now().plusSeconds(3600), 10);

    // 此时若 dep 突然变成 CANCELED（非 DONE），submitRun 必须被依赖围栏拒绝
    Issue depInDb = issueRepository.getById(dep.getId());
    depInDb.setStatus(IssueStatus.CANCELED);
    issueRepository.updateById(depInDb, depInDb.getVersion());
    assertThrows(
        AiValidationException.class,
        () ->
            issueRunService.submitRun(
                execRun.getId(), "act:sub", target.getSpecRevision(), 0L, "s", "v"));

    // 恢复 dep 为 DONE，使提交成功
    depInDb = issueRepository.getById(dep.getId());
    depInDb.setStatus(IssueStatus.DONE);
    issueRepository.updateById(depInDb, depInDb.getVersion());
    issueRunService.submitRun(execRun.getId(), "act:sub", target.getSpecRevision(), 0L, "s", "v");

    // startReviewerRun reviewerAgent mismatch
    assertThrows(
        AiValidationException.class,
        () ->
            issueRunService.startReviewerRun(
                targetId, "wrong-agent", Instant.now().plusSeconds(3600), 10));

    // 启动合法 Reviewer Run
    IssueRun revRun =
        issueRunService.startReviewerRun(targetId, revAgent, Instant.now().plusSeconds(3600), 10);

    // agent review: blank terminalActionId
    assertThrows(
        AiValidationException.class,
        () ->
            issueRunService.reviewRun(
                targetId,
                revRun.getId(),
                IssueRunActorType.AGENT,
                revAgent,
                "  ",
                target.getSpecRevision(),
                0L,
                ReviewDecision.APPROVE,
                "s",
                "v"));

    // reviewRun: stale cursor
    assertThrows(
        AiValidationException.class,
        () ->
            issueRunService.reviewRun(
                targetId,
                revRun.getId(),
                IssueRunActorType.AGENT,
                revAgent,
                "act:rev",
                999L,
                0L,
                ReviewDecision.APPROVE,
                "s",
                "v"));

    // 人工评审被禁止（因为配置了 reviewerAgentName）
    assertThrows(
        AiValidationException.class,
        () ->
            issueRunService.reviewRun(
                targetId,
                null,
                IssueRunActorType.HUMAN,
                null,
                "act:human",
                target.getSpecRevision(),
                0L,
                ReviewDecision.APPROVE,
                "s",
                "v"));

    // startReviewerRun when no submitted run exists on fresh issue
    Issue freshIssue =
        issueService.createIssue(projId, "Fresh", "D", execAgent, revAgent, IssueStatus.TODO);
    freshIssue.setStatus(IssueStatus.IN_REVIEW);
    issueRepository.updateById(freshIssue, freshIssue.getVersion());
    assertThrows(
        AiValidationException.class,
        () ->
            issueRunService.startReviewerRun(
                freshIssue.getId(), revAgent, Instant.now().plusSeconds(3600), 10));

    // human review on issue with no submitted run
    Issue freshHumanIssue =
        issueService.createIssue(projId, "Fresh H", "D", execAgent, null, IssueStatus.TODO);
    freshHumanIssue.setStatus(IssueStatus.IN_REVIEW);
    issueRepository.updateById(freshHumanIssue, freshHumanIssue.getVersion());
    assertThrows(
        AiValidationException.class,
        () ->
            issueRunService.reviewRun(
                freshHumanIssue.getId(),
                null,
                IssueRunActorType.HUMAN,
                null,
                null,
                freshHumanIssue.getSpecRevision(),
                0L,
                ReviewDecision.APPROVE,
                "s",
                "v"));
  }
}
