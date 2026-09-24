package fun.fengwk.kkstudio.platform.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import fun.fengwk.kkstudio.platform.error.AiValidationException;
import fun.fengwk.kkstudio.platform.project.model.Issue;
import fun.fengwk.kkstudio.platform.project.model.IssueActivity;
import fun.fengwk.kkstudio.platform.project.model.IssueActivityActorType;
import fun.fengwk.kkstudio.platform.project.model.IssueActivityKind;
import fun.fengwk.kkstudio.platform.project.model.IssueRun;
import fun.fengwk.kkstudio.platform.project.model.IssueRunStatus;
import fun.fengwk.kkstudio.platform.project.model.IssueStatus;
import fun.fengwk.kkstudio.platform.project.model.Project;
import fun.fengwk.kkstudio.platform.project.model.ReviewDecision;
import fun.fengwk.kkstudio.platform.project.repo.IssueActivityRepository;
import fun.fengwk.kkstudio.platform.project.repo.IssueRepository;
import fun.fengwk.kkstudio.platform.project.repo.IssueRunRepository;
import fun.fengwk.kkstudio.platform.project.service.IssueRunService;
import fun.fengwk.kkstudio.platform.project.service.IssueService;
import fun.fengwk.kkstudio.platform.project.service.IssueWorkStore;
import fun.fengwk.kkstudio.platform.project.service.ProjectService;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

/**
 * 验证 IssueRun 边界保护、字段脱敏、无副作用断言及终态 Exact Replay 冲突判定： 1. 终态 actionId 冲突矩阵（异 runId, 异 role, 异
 * reviewer, 异 decision, 异 summary, 异 verification）全量拒绝且不回显 actionId / payload； 2. 16KiB / 64KiB /
 * 1MiB 确切边界及超限（+1）与 surrogate 非法字符拒绝，失败前后断言 Issue/Run/Activity/Work 严格无副作用； 3. 真实 JSON 封装下
 * result::text 65536 字节确切边界与 JsonNode 语义等价 Replay。
 */
class IssueRunBoundaryAndReplayIntegrationTest extends ProjectTestSupport {

  @Autowired private IssueRunService issueRunService;
  @Autowired private IssueService issueService;
  @Autowired private ProjectService projectService;
  @Autowired private IssueRepository issueRepository;
  @Autowired private IssueRunRepository issueRunRepository;
  @Autowired private IssueActivityRepository issueActivityRepository;
  @Autowired private IssueWorkStore issueWorkStore;
  @Autowired private JdbcTemplate jdbc;

  @Test
  void testTerminalActionReplayConflictsAndDesensitization() {
    String executorAgent = createTestAgent();
    String reviewerAgent = createTestAgent();
    Project proj = projectService.createProject("Conflict Project", "Desc", true, 3);
    Issue issue =
        issueService.createIssue(
            proj.getId(), "Issue 1", "Desc", executorAgent, reviewerAgent, IssueStatus.TODO);

    IssueRun run1 =
        issueRunService.startExecutorRun(
            issue.getId(), executorAgent, Instant.now().plusSeconds(3600), 0);

    String submitActionId = "secret-action-id-999";
    String secretSummary = "super-secret-summary-data";
    String secretVerification = "confidential-verification-proof";

    // 正常 Complete 成功
    IssueRun submitted =
        issueRunService.completeExecutorRun(
            run1.getId(), submitActionId, secretSummary, secretVerification);
    assertNotNull(submitted);

    // 冲突 1: 不同的 runId 尝试复用 submitActionId
    Issue issue2 =
        issueService.createIssue(
            proj.getId(), "Issue 2", "Desc", executorAgent, reviewerAgent, IssueStatus.TODO);
    IssueRun run2 =
        issueRunService.startExecutorRun(
            issue2.getId(), executorAgent, Instant.now().plusSeconds(3600), 0);

    AiValidationException ex1 =
        assertThrows(
            AiValidationException.class,
            () ->
                issueRunService.completeExecutorRun(
                    run2.getId(), submitActionId, secretSummary, secretVerification));
    assertEquals("Terminal action ID conflict", ex1.getMessage());
    assertFalse(ex1.getMessage().contains(submitActionId));
    assertFalse(ex1.getMessage().contains(secretSummary));

    // 冲突 2: 相同的 runId，但不同的 summary 内容
    AiValidationException ex2 =
        assertThrows(
            AiValidationException.class,
            () ->
                issueRunService.completeExecutorRun(
                    run1.getId(), submitActionId, "tampered summary", secretVerification));
    assertEquals("Terminal action ID conflict", ex2.getMessage());
    assertFalse(ex2.getMessage().contains("tampered summary"));

    // 冲突 3: 相同的 runId，但不同的 verification 内容
    AiValidationException ex3 =
        assertThrows(
            AiValidationException.class,
            () ->
                issueRunService.completeExecutorRun(
                    run1.getId(), submitActionId, secretSummary, "tampered verify"));
    assertEquals("Terminal action ID conflict", ex3.getMessage());

    // 冲突 4: 拿 submitActionId 去调用 reviewByAgent（异 role）
    AiValidationException ex4 =
        assertThrows(
            AiValidationException.class,
            () ->
                issueRunService.reviewByAgent(
                    run1.getId(),
                    reviewerAgent,
                    submitActionId,
                    ReviewDecision.APPROVE,
                    secretSummary));
    assertEquals("Terminal action ID conflict", ex4.getMessage());
  }

  @Test
  void testReviewRunReplayConflictsAndDesensitization() {
    String executorAgent = createTestAgent();
    String reviewerAgent = createTestAgent();
    Project proj = projectService.createProject("Review Conflicts", "Desc", true, 3);
    Issue issue =
        issueService.createIssue(
            proj.getId(), "Review Issue", "Desc", executorAgent, reviewerAgent, IssueStatus.TODO);

    IssueRun execRun =
        issueRunService.startExecutorRun(
            issue.getId(), executorAgent, Instant.now().plusSeconds(3600), 0);
    issueRunService.completeExecutorRun(
        execRun.getId(), "submit-" + UUID.randomUUID(), "Summary", "Verify");

    IssueRun revRun =
        issueRunService.startReviewerRun(
            issue.getId(), reviewerAgent, Instant.now().plusSeconds(3600), 0);

    String reviewActionId = "review-action-id-secret-888";
    issueRunService.reviewByAgent(
        revRun.getId(), reviewerAgent, reviewActionId, ReviewDecision.APPROVE, "Approved Summary");

    // 冲突 1: 尝试改变 reviewerAgentName
    String otherAgent = createTestAgent();
    AiValidationException ex1 =
        assertThrows(
            AiValidationException.class,
            () ->
                issueRunService.reviewByAgent(
                    revRun.getId(),
                    otherAgent,
                    reviewActionId,
                    ReviewDecision.APPROVE,
                    "Approved Summary"));
    assertEquals("Terminal action ID conflict", ex1.getMessage());

    // 冲突 2: 尝试改变 decision（APPROVE -> CHANGES_REQUESTED）
    AiValidationException ex2 =
        assertThrows(
            AiValidationException.class,
            () ->
                issueRunService.reviewByAgent(
                    revRun.getId(),
                    reviewerAgent,
                    reviewActionId,
                    ReviewDecision.REQUEST_CHANGES,
                    "Approved Summary"));
    assertEquals("Terminal action ID conflict", ex2.getMessage());

    // 冲突 3: 尝试改变 reason
    AiValidationException ex3 =
        assertThrows(
            AiValidationException.class,
            () ->
                issueRunService.reviewByAgent(
                    revRun.getId(),
                    reviewerAgent,
                    reviewActionId,
                    ReviewDecision.APPROVE,
                    "Different Reason"));
    assertEquals("Terminal action ID conflict", ex3.getMessage());
  }

  @Test
  void testNoSideEffectsOnValidationFailure() {
    String agent = createTestAgent();
    Project proj = projectService.createProject("No Side Effects", "Desc", true, 3);
    Issue issue =
        issueService.createIssue(proj.getId(), "Issue", "Desc", agent, null, IssueStatus.TODO);
    IssueRun run =
        issueRunService.startExecutorRun(issue.getId(), agent, Instant.now().plusSeconds(3600), 0);
    long workWakeBeforeInvalid = issueWorkStore.getWork(issue.getId()).getWakeVersion();

    // 1. requestInput 超出 16KiB 边界 (+1 字节)：失败前后 run 状态与 reason 不变
    String oversizedQuestion = "q".repeat(16385);
    assertThrows(
        AiValidationException.class,
        () -> issueRunService.requestInput(run.getId(), oversizedQuestion, null));

    IssueRun runAfterOversizedReq = issueRunService.getRun(run.getId());
    assertEquals(IssueRunStatus.RUNNING, runAfterOversizedReq.getStatus());
    assertNull(runAfterOversizedReq.getWaitingReason());

    // 2. requestInput 包含未配对代理项：前置拒绝，无副作用
    assertThrows(
        AiValidationException.class,
        () -> issueRunService.requestInput(run.getId(), "surrogate\uD800test", null));
    assertEquals(IssueRunStatus.RUNNING, issueRunService.getRun(run.getId()).getStatus());

    // 3. failRun 超出 16KiB 边界 (+1 字节)：失败前后状态不变
    String oversizedFailReason = "f".repeat(16385);
    assertThrows(
        AiValidationException.class,
        () -> issueRunService.failRun(run.getId(), IssueRunStatus.FAILED, oversizedFailReason));
    assertEquals(IssueRunStatus.RUNNING, issueRunService.getRun(run.getId()).getStatus());

    // 4. cancelIssue 超出 16KiB 边界 (+1 字节)：前置拒绝，Issue 与 Run 保持活跃
    String oversizedCancelReason = "c".repeat(16385);
    assertThrows(
        AiValidationException.class,
        () -> issueService.cancelIssue(issue.getId(), 0L, oversizedCancelReason));
    assertEquals(IssueStatus.IN_PROGRESS, issueService.getIssue(issue.getId()).getStatus());
    assertEquals(IssueRunStatus.RUNNING, issueRunService.getRun(run.getId()).getStatus());

    // 5. cancelIssue 包含未配对代理项：前置拒绝，Issue 保持活跃
    assertThrows(
        AiValidationException.class,
        () -> issueService.cancelIssue(issue.getId(), 0L, "bad\uDC00surrogate"));
    assertEquals(IssueStatus.IN_PROGRESS, issueService.getIssue(issue.getId()).getStatus());
    assertEquals(workWakeBeforeInvalid, issueWorkStore.getWork(issue.getId()).getWakeVersion());

    // 6. appendActivity 1MiB (1048576 字节) 边界：恰好 1MiB 成功，1048577 字节前置拒绝
    String exact1MiB = "x".repeat(1048576);
    IssueActivity okActivity =
        issueService.appendActivity(
            IssueActivity.builder()
                .issueId(issue.getId())
                .kind(IssueActivityKind.HUMAN_INPUT)
                .actorType(IssueActivityActorType.HUMAN)
                .body(exact1MiB)
                .idempotencyKey("key-1mib")
                .build());
    assertNotNull(okActivity);
    long wakeAfterExact = issueWorkStore.getWork(issue.getId()).getWakeVersion();

    String oversized1MiB = "x".repeat(1048577);
    assertThrows(
        AiValidationException.class,
        () ->
            issueService.appendActivity(
                IssueActivity.builder()
                    .issueId(issue.getId())
                    .kind(IssueActivityKind.HUMAN_INPUT)
                    .actorType(IssueActivityActorType.HUMAN)
                    .body(oversized1MiB)
                    .idempotencyKey("key-bad")
                    .build()));
    assertEquals(wakeAfterExact, issueWorkStore.getWork(issue.getId()).getWakeVersion());

    // 7. createIssue 64KiB 边界：恰好 65536 字节成功，65537 字节前置拒绝且无任何 issue 落库
    String exact64KiB = "d".repeat(65536);
    Issue okIssue =
        issueService.createIssue(
            proj.getId(), "Desc 64K", exact64KiB, agent, null, IssueStatus.TODO);
    assertNotNull(okIssue);
    int issueCountAfterExactDescription = issueService.listIssues(proj.getId(), true).size();

    String oversized64KiB = "d".repeat(65537);
    assertThrows(
        AiValidationException.class,
        () ->
            issueService.createIssue(
                proj.getId(), "Desc Oversized", oversized64KiB, agent, null, IssueStatus.TODO));

    assertThrows(
        AiValidationException.class,
        () ->
            issueService.createIssue(
                proj.getId(), "Surrogate", "desc\uD800err", agent, null, IssueStatus.TODO));
    assertEquals(
        issueCountAfterExactDescription, issueService.listIssues(proj.getId(), true).size());
  }

  @Test
  void testExactWaitingReasonBoundariesPersist() {
    String agent = createTestAgent();
    Project project = projectService.createProject("Reason Boundary", "Desc", true, 3);
    String exactReason = "r".repeat(16384);

    Issue requestIssue =
        issueService.createIssue(project.getId(), "Request", "Desc", agent, null, IssueStatus.TODO);
    IssueRun requestRun =
        issueRunService.startExecutorRun(
            requestIssue.getId(), agent, Instant.now().plusSeconds(3600), 0);
    IssueRun waiting = issueRunService.requestInput(requestRun.getId(), exactReason, null);
    assertEquals(IssueRunStatus.WAITING_HUMAN, waiting.getStatus());
    assertEquals(16384, waiting.getWaitingReason().getBytes(StandardCharsets.UTF_8).length);

    Issue failIssue =
        issueService.createIssue(project.getId(), "Fail", "Desc", agent, null, IssueStatus.TODO);
    IssueRun failRun =
        issueRunService.startExecutorRun(
            failIssue.getId(), agent, Instant.now().plusSeconds(3600), 0);
    IssueRun failed = issueRunService.failRun(failRun.getId(), IssueRunStatus.FAILED, exactReason);
    assertEquals(IssueRunStatus.FAILED, failed.getStatus());
    assertEquals(16384, failed.getWaitingReason().getBytes(StandardCharsets.UTF_8).length);

    Issue cancelIssue =
        issueService.createIssue(project.getId(), "Cancel", "Desc", agent, null, IssueStatus.TODO);
    IssueRun cancelRun =
        issueRunService.startExecutorRun(
            cancelIssue.getId(), agent, Instant.now().plusSeconds(3600), 0);
    issueService.cancelIssue(
        cancelIssue.getId(), issueService.getIssue(cancelIssue.getId()).getVersion(), exactReason);
    IssueRun cancelled = issueRunService.getRun(cancelRun.getId());
    assertEquals(IssueRunStatus.CANCELLED, cancelled.getStatus());
    assertEquals(16384, cancelled.getWaitingReason().getBytes(StandardCharsets.UTF_8).length);
  }

  @Test
  void testResultSerializedTextBoundaryAndJsonEquivalenceReplay() {
    String agent = createTestAgent();
    Project proj = projectService.createProject("Result Boundary", "Desc", true, 3);
    Issue issue =
        issueService.createIssue(proj.getId(), "Issue", "Desc", agent, null, IssueStatus.TODO);
    IssueRun run =
        issueRunService.startExecutorRun(issue.getId(), agent, Instant.now().plusSeconds(3600), 0);

    // PostgreSQL JSONB 文本封装为 {"summary": "<summary>", "verification": ""}。
    int jsonbWrapperBytes =
        "{\"summary\": \"\", \"verification\": \"\"}".getBytes(StandardCharsets.UTF_8).length;
    int exactSummaryLen = 65536 - jsonbWrapperBytes;
    String exactSummary = "s".repeat(exactSummaryLen);
    String actionId = "exact-result-action-" + UUID.randomUUID();

    // 1. 精确 65536 字节 JSON 结果成功写入并在 DB 中保存为合法的 JSONB
    IssueRun submitted =
        issueRunService.completeExecutorRun(run.getId(), actionId, exactSummary, "");
    assertNotNull(submitted);
    assertNotNull(submitted.getResult());
    Integer storedResultBytes =
        jdbc.queryForObject(
            "select octet_length(result::text) from project_issue_run where id = ?",
            Integer.class,
            run.getId());
    assertEquals(65536, storedResultBytes);

    // 2. 超出 65536 字节被拒绝
    Issue issue2 =
        issueService.createIssue(proj.getId(), "Issue 2", "Desc", agent, null, IssueStatus.TODO);
    IssueRun run2 =
        issueRunService.startExecutorRun(issue2.getId(), agent, Instant.now().plusSeconds(3600), 0);
    String oversizedSummary = "s".repeat(exactSummaryLen + 1);

    AiValidationException ex =
        assertThrows(
            AiValidationException.class,
            () ->
                issueRunService.completeExecutorRun(
                    run2.getId(), "action-oversized", oversizedSummary, ""));
    assertEquals("result exceeds maximum allowed UTF-8 size of 65536 bytes", ex.getMessage());
    assertEquals(IssueRunStatus.RUNNING, issueRunService.getRun(run2.getId()).getStatus());

    // 3. Exact Replay 验证：相同的 payload 重放得到同一 Run 实例
    IssueRun replayed =
        issueRunService.completeExecutorRun(run.getId(), actionId, exactSummary, "");
    assertEquals(submitted.getId(), replayed.getId());
    assertEquals(submitted.getResult(), replayed.getResult());
    assertTrue(submitted.getResult().contains(": "));
  }
}
