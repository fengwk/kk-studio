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
import fun.fengwk.kkstudio.platform.project.model.IssueInput;
import fun.fengwk.kkstudio.platform.project.model.IssueInputKind;
import fun.fengwk.kkstudio.platform.project.model.IssueRun;
import fun.fengwk.kkstudio.platform.project.model.IssueRunActorType;
import fun.fengwk.kkstudio.platform.project.model.IssueRunStatus;
import fun.fengwk.kkstudio.platform.project.model.IssueStatus;
import fun.fengwk.kkstudio.platform.project.model.Project;
import fun.fengwk.kkstudio.platform.project.model.ReviewDecision;
import fun.fengwk.kkstudio.platform.project.repo.IssueInputRepository;
import fun.fengwk.kkstudio.platform.project.repo.IssueRepository;
import fun.fengwk.kkstudio.platform.project.repo.IssueRunRepository;
import fun.fengwk.kkstudio.platform.project.service.IssueControllerWorkStore;
import fun.fengwk.kkstudio.platform.project.service.IssueRunService;
import fun.fengwk.kkstudio.platform.project.service.IssueService;
import fun.fengwk.kkstudio.platform.project.service.ProjectService;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

/**
 * 验证 IssueRun 边界保护、字段脱敏、无副作用断言及终态 Exact Replay 冲突判定： 1. 终态 actionId 冲突矩阵（异 runId, 异 role, 异 actor,
 * 异 name, 异 decision, 异 cursors, 异 summary, 异 verification）全量拒绝且不回显 actionId / payload； 2. 16KiB /
 * 64KiB / 1MiB 确切边界及超限（+1）与 surrogate 非法字符拒绝，失败前后断言 Issue/Run/Input/Work 严格无副作用； 3. 真实 JSON 封装下
 * result::text 65536 字节确切边界与 JsonNode 语义等价 Replay。
 */
class IssueRunBoundaryAndReplayIntegrationTest extends ProjectTestSupport {

  @Autowired private IssueRunService issueRunService;
  @Autowired private IssueService issueService;
  @Autowired private ProjectService projectService;
  @Autowired private IssueRepository issueRepository;
  @Autowired private IssueRunRepository issueRunRepository;
  @Autowired private IssueInputRepository issueInputRepository;
  @Autowired private IssueControllerWorkStore controllerWorkStore;
  @Autowired private JdbcTemplate jdbc;

  @Test
  void testTerminalActionReplayConflictsAndDesensitization() {
    String executorAgent = createTestAgent();
    String reviewerAgent = createTestAgent();
    Project proj = projectService.createProject("Conflict Project", "Desc", executorAgent);
    Issue issue =
        issueService.createIssue(
            proj.getId(), "Issue 1", "Desc", executorAgent, reviewerAgent, IssueStatus.TODO);

    IssueRun run1 =
        issueRunService.startExecutorRun(
            issue.getId(), executorAgent, Instant.now().plusSeconds(3600), 0);

    String submitActionId = "secret-action-id-999";
    String secretSummary = "super-secret-summary-data";
    String secretVerification = "confidential-verification-proof";

    // 正常 Submit 成功
    IssueRun submitted =
        issueRunService.submitRun(
            run1.getId(),
            submitActionId,
            run1.getObservedSpecRevision(),
            run1.getObservedInputSequence(),
            secretSummary,
            secretVerification);
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
                issueRunService.submitRun(
                    run2.getId(),
                    submitActionId,
                    run2.getObservedSpecRevision(),
                    run2.getObservedInputSequence(),
                    secretSummary,
                    secretVerification));
    assertEquals("Terminal action ID conflict", ex1.getMessage());
    assertFalse(ex1.getMessage().contains(submitActionId));
    assertFalse(ex1.getMessage().contains(secretSummary));

    // 冲突 2: 相同的 runId，但不同的 specRevision
    AiValidationException ex2 =
        assertThrows(
            AiValidationException.class,
            () ->
                issueRunService.submitRun(
                    run1.getId(),
                    submitActionId,
                    999L,
                    run1.getObservedInputSequence(),
                    secretSummary,
                    secretVerification));
    assertEquals("Terminal action ID conflict", ex2.getMessage());

    // 冲突 3: 相同的 runId，但不同的 inputSequence
    AiValidationException ex3 =
        assertThrows(
            AiValidationException.class,
            () ->
                issueRunService.submitRun(
                    run1.getId(),
                    submitActionId,
                    run1.getObservedSpecRevision(),
                    999L,
                    secretSummary,
                    secretVerification));
    assertEquals("Terminal action ID conflict", ex3.getMessage());

    // 冲突 4: 相同的 runId，但不同的 summary 内容
    AiValidationException ex4 =
        assertThrows(
            AiValidationException.class,
            () ->
                issueRunService.submitRun(
                    run1.getId(),
                    submitActionId,
                    run1.getObservedSpecRevision(),
                    run1.getObservedInputSequence(),
                    "tampered summary",
                    secretVerification));
    assertEquals("Terminal action ID conflict", ex4.getMessage());
    assertFalse(ex4.getMessage().contains("tampered summary"));

    // 冲突 5: 相同的 runId，但不同的 verification 内容
    AiValidationException ex5 =
        assertThrows(
            AiValidationException.class,
            () ->
                issueRunService.submitRun(
                    run1.getId(),
                    submitActionId,
                    run1.getObservedSpecRevision(),
                    run1.getObservedInputSequence(),
                    secretSummary,
                    "tampered verify"));
    assertEquals("Terminal action ID conflict", ex5.getMessage());

    // 冲突 6: 拿 submitActionId 去调用 reviewRun（异 role / 行为者类型）
    AiValidationException ex6 =
        assertThrows(
            AiValidationException.class,
            () ->
                issueRunService.reviewRun(
                    issue.getId(),
                    run1.getId(),
                    IssueRunActorType.AGENT,
                    reviewerAgent,
                    submitActionId,
                    run1.getObservedSpecRevision(),
                    run1.getObservedInputSequence(),
                    ReviewDecision.APPROVE,
                    secretSummary,
                    secretVerification));
    assertEquals("Terminal action ID conflict", ex6.getMessage());
  }

  @Test
  void testReviewRunReplayConflictsAndDesensitization() {
    String executorAgent = createTestAgent();
    String reviewerAgent = createTestAgent();
    Project proj = projectService.createProject("Review Conflicts", "Desc", executorAgent);
    Issue issue =
        issueService.createIssue(
            proj.getId(), "Review Issue", "Desc", executorAgent, reviewerAgent, IssueStatus.TODO);

    IssueRun execRun =
        issueRunService.startExecutorRun(
            issue.getId(), executorAgent, Instant.now().plusSeconds(3600), 0);
    issueRunService.submitRun(
        execRun.getId(),
        "submit-" + UUID.randomUUID(),
        execRun.getObservedSpecRevision(),
        execRun.getObservedInputSequence(),
        "Summary",
        "Verify");

    IssueRun revRun =
        issueRunService.startReviewerRun(
            issue.getId(), reviewerAgent, Instant.now().plusSeconds(3600), 0);

    String reviewActionId = "review-action-id-secret-888";
    issueRunService.reviewRun(
        issue.getId(),
        revRun.getId(),
        IssueRunActorType.AGENT,
        reviewerAgent,
        reviewActionId,
        revRun.getObservedSpecRevision(),
        revRun.getObservedInputSequence(),
        ReviewDecision.APPROVE,
        "Approved Summary",
        "Approved Verify");

    // 冲突 1: 尝试改变 reviewerAgentName
    String otherAgent = createTestAgent();
    AiValidationException ex1 =
        assertThrows(
            AiValidationException.class,
            () ->
                issueRunService.reviewRun(
                    issue.getId(),
                    revRun.getId(),
                    IssueRunActorType.AGENT,
                    otherAgent,
                    reviewActionId,
                    revRun.getObservedSpecRevision(),
                    revRun.getObservedInputSequence(),
                    ReviewDecision.APPROVE,
                    "Approved Summary",
                    "Approved Verify"));
    assertEquals("Terminal action ID conflict", ex1.getMessage());

    // 冲突 2: 尝试改变 decision（APPROVE -> CHANGES_REQUESTED）
    AiValidationException ex2 =
        assertThrows(
            AiValidationException.class,
            () ->
                issueRunService.reviewRun(
                    issue.getId(),
                    revRun.getId(),
                    IssueRunActorType.AGENT,
                    reviewerAgent,
                    reviewActionId,
                    revRun.getObservedSpecRevision(),
                    revRun.getObservedInputSequence(),
                    ReviewDecision.REQUEST_CHANGES,
                    "Approved Summary",
                    "Approved Verify"));
    assertEquals("Terminal action ID conflict", ex2.getMessage());

    // 冲突 3: 尝试变为 HUMAN actorType
    AiValidationException ex3 =
        assertThrows(
            AiValidationException.class,
            () ->
                issueRunService.reviewRun(
                    issue.getId(),
                    null,
                    IssueRunActorType.HUMAN,
                    null,
                    reviewActionId,
                    revRun.getObservedSpecRevision(),
                    revRun.getObservedInputSequence(),
                    ReviewDecision.APPROVE,
                    "Approved Summary",
                    "Approved Verify"));
    assertEquals("Terminal action ID conflict", ex3.getMessage());

    // 冲突 4: 异 summary / verification
    AiValidationException ex4 =
        assertThrows(
            AiValidationException.class,
            () ->
                issueRunService.reviewRun(
                    issue.getId(),
                    revRun.getId(),
                    IssueRunActorType.AGENT,
                    reviewerAgent,
                    reviewActionId,
                    revRun.getObservedSpecRevision(),
                    revRun.getObservedInputSequence(),
                    ReviewDecision.APPROVE,
                    "Different Summary",
                    "Approved Verify"));
    assertEquals("Terminal action ID conflict", ex4.getMessage());
  }

  @Test
  void testNoSideEffectsOnValidationFailure() {
    String agent = createTestAgent();
    Project proj = projectService.createProject("No Side Effects", "Desc", agent);
    Issue issue =
        issueService.createIssue(proj.getId(), "Issue", "Desc", agent, null, IssueStatus.TODO);
    IssueRun run =
        issueRunService.startExecutorRun(issue.getId(), agent, Instant.now().plusSeconds(3600), 0);
    long workWakeBeforeInvalid = controllerWorkStore.getWork(issue.getId()).getWakeVersion();

    // 1. requestInput 超出 16KiB 边界 (+1 字节)：失败前后 run 状态与 reason 不变
    String oversizedQuestion = "q".repeat(16385);
    assertThrows(
        AiValidationException.class,
        () ->
            issueRunService.requestInput(
                run.getId(),
                run.getObservedSpecRevision(),
                run.getObservedInputSequence(),
                oversizedQuestion,
                null));

    IssueRun runAfterOversizedReq = issueRunService.getRun(run.getId());
    assertEquals(IssueRunStatus.RUNNING, runAfterOversizedReq.getStatus());
    assertNull(runAfterOversizedReq.getWaitingReason());

    // 2. requestInput 包含未配对代理项：前置拒绝，无副作用
    assertThrows(
        AiValidationException.class,
        () ->
            issueRunService.requestInput(
                run.getId(),
                run.getObservedSpecRevision(),
                run.getObservedInputSequence(),
                "surrogate\uD800test",
                null));
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
    assertEquals(
        workWakeBeforeInvalid, controllerWorkStore.getWork(issue.getId()).getWakeVersion());

    // 6. appendInput 1MiB (1048576 字节) 边界：恰好 1MiB 成功，1048577 字节前置拒绝且 inputSequence 不递增
    String exact1MiB = "x".repeat(1048576);
    IssueInput okInput =
        issueService.appendInput(issue.getId(), IssueInputKind.HUMAN, exact1MiB, "key-1mib");
    assertNotNull(okInput);
    assertEquals(1L, issueService.getIssue(issue.getId()).getInputSequence());
    long wakeAfterExactInput = controllerWorkStore.getWork(issue.getId()).getWakeVersion();

    String oversized1MiB = "x".repeat(1048577);
    assertThrows(
        AiValidationException.class,
        () ->
            issueService.appendInput(
                issue.getId(), IssueInputKind.HUMAN, oversized1MiB, "key-bad"));
    assertEquals(1L, issueService.getIssue(issue.getId()).getInputSequence());
    assertEquals(wakeAfterExactInput, controllerWorkStore.getWork(issue.getId()).getWakeVersion());

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
    Project project = projectService.createProject("Reason Boundary", "Desc", agent);
    String exactReason = "r".repeat(16384);

    Issue requestIssue =
        issueService.createIssue(project.getId(), "Request", "Desc", agent, null, IssueStatus.TODO);
    IssueRun requestRun =
        issueRunService.startExecutorRun(
            requestIssue.getId(), agent, Instant.now().plusSeconds(3600), 0);
    IssueRun waiting =
        issueRunService.requestInput(
            requestRun.getId(),
            requestRun.getObservedSpecRevision(),
            requestRun.getObservedInputSequence(),
            exactReason,
            null);
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
    Project proj = projectService.createProject("Result Boundary", "Desc", agent);
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
        issueRunService.submitRun(
            run.getId(),
            actionId,
            run.getObservedSpecRevision(),
            run.getObservedInputSequence(),
            exactSummary,
            "");
    assertNotNull(submitted);
    assertNotNull(submitted.getResult());
    Integer storedResultBytes =
        jdbc.queryForObject(
            "select octet_length(result::text) from issue_run where id = ?",
            Integer.class,
            run.getId());
    assertEquals(65536, storedResultBytes);

    // 2. 超出 65536 字节（例如 summary 长度 65506 字节导致总 JSON 达到 65537 字节）被拒绝
    Issue issue2 =
        issueService.createIssue(proj.getId(), "Issue 2", "Desc", agent, null, IssueStatus.TODO);
    IssueRun run2 =
        issueRunService.startExecutorRun(issue2.getId(), agent, Instant.now().plusSeconds(3600), 0);
    String oversizedSummary = "s".repeat(exactSummaryLen + 1);

    AiValidationException ex =
        assertThrows(
            AiValidationException.class,
            () ->
                issueRunService.submitRun(
                    run2.getId(),
                    "action-oversized",
                    run2.getObservedSpecRevision(),
                    run2.getObservedInputSequence(),
                    oversizedSummary,
                    ""));
    assertEquals("result exceeds maximum allowed UTF-8 size of 65536 bytes", ex.getMessage());
    assertEquals(IssueRunStatus.RUNNING, issueRunService.getRun(run2.getId()).getStatus());

    // 3. Exact Replay 验证：相同的 payload 重放得到同一 Run 实例
    IssueRun replayed =
        issueRunService.submitRun(
            run.getId(),
            actionId,
            run.getObservedSpecRevision(),
            run.getObservedInputSequence(),
            exactSummary,
            "");
    assertEquals(submitted.getId(), replayed.getId());
    assertEquals(submitted.getResult(), replayed.getResult());
    assertTrue(submitted.getResult().contains(": "));
  }
}
