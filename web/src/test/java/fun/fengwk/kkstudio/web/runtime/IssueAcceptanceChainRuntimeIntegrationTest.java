package fun.fengwk.kkstudio.web.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.Driver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import fun.fengwk.kkstudio.platform.error.AiValidationException;
import fun.fengwk.kkstudio.platform.project.model.Issue;
import fun.fengwk.kkstudio.platform.project.model.IssueActivity;
import fun.fengwk.kkstudio.platform.project.model.IssueActivityKind;
import fun.fengwk.kkstudio.platform.project.model.IssueAgentSession;
import fun.fengwk.kkstudio.platform.project.model.IssueRun;
import fun.fengwk.kkstudio.platform.project.model.IssueRunOutcome;
import fun.fengwk.kkstudio.platform.project.model.IssueRunRole;
import fun.fengwk.kkstudio.platform.project.model.IssueRunStatus;
import fun.fengwk.kkstudio.platform.project.model.IssueStatus;
import fun.fengwk.kkstudio.platform.project.model.Project;
import fun.fengwk.kkstudio.platform.project.model.ReviewDecision;
import fun.fengwk.kkstudio.platform.project.service.IssueRunService;
import fun.fengwk.kkstudio.platform.project.service.IssueService;
import fun.fengwk.kkstudio.platform.project.service.ProjectService;
import fun.fengwk.kkstudio.web.WebTestApplication;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Issue 双 Agent 验收链的端到端集成测试：真实 PostgreSQL + 真实 HarnessRuntime + 真实 Issue Controller。
 *
 * <p>测试意图：验收链的每一环都必须是产品代码的真实路径——{@code IssueService.createIssue} 写入 {@code
 * project_issue_work}，{@code IssueControllerDispatcher} claim 后由 {@code IssueReconciler} 推进
 * Run/状态，Harness Session 与工作 Branch 由 {@code ProjectHarnessSessionBootstrapService} 引导， turn 由真实
 * Model/Tool Processor 执行，审查决定由真实 {@code issue_review} 工具落到 {@code IssueRunService}。因此测试绝不预置
 * Run/Activity/Session 状态来“凑”出终态，只断言真实推进产生的 durable 事实。
 *
 * <p>唯一被替换的是外部付费依赖：{@link ScriptedModelGateway} 用脚本化响应替代 Provider 网络调用；工具面仍是真实角色工具
 * （executor/reviewer 各自由 {@code ProjectThreadOwnerResolver} 解析），环境类外部工具不参与本链。
 *
 * <p>所有等待都是有界轮询（50ms 间隔 + 明确超时 + 诊断信息），不依赖固定 sleep 断言成功路径；唯一的有界静止窗口用于证明 BLOCKED 不会被自动流程重新唤醒。
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    classes = WebTestApplication.class)
@Import(IssueAcceptanceChainRuntimeIntegrationTest.ScriptedGatewayConfiguration.class)
class IssueAcceptanceChainRuntimeIntegrationTest {

  /** 每次等待的上界：链路本身只需数秒，超时说明状态机没有收敛。 */
  private static final Duration CHAIN_TIMEOUT = Duration.ofSeconds(120);

  private static final Duration POLL_INTERVAL = Duration.ofMillis(50);

  /** BLOCKED 静止观察窗口：远大于 dispatcher（200ms）与 reconciler（100ms）周期，用于观察“无人恢复就不推进”。 */
  private static final Duration QUIET_WINDOW = Duration.ofMillis(1500);

  private static final int HUMAN_REJECTION_THRESHOLD = 2;

  private static final String FIRST_EXECUTOR_SUMMARY = "Executor attempt 1 completed and verified.";
  private static final String SECOND_EXECUTOR_SUMMARY =
      "Executor attempt 2 completed and verified.";

  private static final String REVIEW_TOOL_NAME = ScriptedModelGateway.REVIEW_TOOL_NAME;

  private static final AtomicLong TEST_FIXTURE_SEQUENCE = new AtomicLong();

  @SuppressWarnings("resource")
  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"))
          .withDatabaseName("kk_studio_issue_acceptance_test");

  static {
    POSTGRES.start();
    // 上下文创建期装配 bean 会读取 system_setting 默认行：先应用唯一 V1 baseline（Harness 7 表 + system_setting 默认行 +
    // Project/Issue 表），否则上下文启动失败。
    try (Connection connection = newConnection()) {
      resetSchema(connection);
    } catch (SQLException error) {
      throw new IllegalStateException("cannot apply baseline schema", error);
    }
  }

  @DynamicPropertySource
  static void overrideDataSource(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.driver-class-name", Driver.class::getName);
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    // 打开进程内 worker：Harness dispatcher 与 Issue Controller dispatcher 由该开关统一控制。
    registry.add("kk-studio.harness.runtime.workers-enabled", () -> "true");
    // 收紧调度与重试节奏，使链路在秒级收敛；这些仍是产品属性，不改变状态机语义。
    registry.add("kk-studio.harness.dispatcher.poll-interval", () -> "200ms");
    registry.add("kk-studio.project.controller.poll-interval", () -> "100ms");
    registry.add("kk-studio.project.controller.active-delay", () -> "100ms");
    registry.add("kk-studio.project.controller.rejection-delay", () -> "100ms");
    registry.add("kk-studio.project.controller.retry-delay", () -> "300ms");
  }

  @Autowired private ProjectService projectService;
  @Autowired private IssueService issueService;
  @Autowired private IssueRunService issueRunService;
  @Autowired private ScriptedModelGateway scriptedModelGateway;
  @Autowired private JdbcTemplate jdbc;

  @BeforeEach
  void resetScriptedModel() {
    // Context 是跨测试方法复用的：每个测试必须从空脚本开始，避免上一个测试残留的决定错位后续 Run。
    scriptedModelGateway.reset(List.of());
  }

  /**
   * 双 Agent 审查链：TODO 执行提交 -&gt; 审查打回 -&gt; 复用工作 Branch 重新执行 -&gt; 复审批准 -&gt; DONE。
   *
   * <p>断言两类事实：Issue/Run 状态机推进，以及执行者与审查者各自拥有唯一且互不相同的 Session 与工作 Branch（多次 Run 复用同一条 Branch）。
   */
  @Test
  void agentReviewChainRejectsOnReusedBranchAndApprovesToDone() {
    String executorAgent = createAgent();
    String reviewerAgent = createAgent();
    Project project =
        projectService.createProject("Agent acceptance chain", "Issue 双 Agent 验收链", false, 3);
    scriptedModelGateway.reset(
        List.of(
            ScriptedModelGateway.ReviewScript.requestChanges(
                "Rejected because the acceptance evidence is missing."),
            ScriptedModelGateway.ReviewScript.approve("Approved after the follow-up execution.")));
    Issue issue =
        issueService.createIssue(
            project.getId(),
            "Agent review chain",
            "Deliver the change and submit it for review.",
            executorAgent,
            reviewerAgent,
            IssueStatus.TODO);

    // 阶段 1：真实派发 + 真实 Runtime 执行 -> 第一次执行 Run 提交。
    // 断言等待的是 durable 的“第 n 次提交”事实，而不是会被后续阶段覆盖的瞬时状态：链路推进很快，状态观察天然有竞态。
    awaitTrue(
        () -> submittedExecutorRuns(issue.getId()).size() >= 1,
        CHAIN_TIMEOUT,
        () -> diagnose("first submission", issue.getId()));
    IssueRun firstExecutorRun = submittedExecutorRuns(issue.getId()).getFirst();
    assertExecutorSubmitted(firstExecutorRun, FIRST_EXECUTOR_SUMMARY);
    IssueAgentSession executorSession = requireAgentSession(issue.getId(), executorAgent);

    // 阶段 2：审查 Run 通过真实 issue_review 工具打回，且决定绑定被审查的那次提交 Run。
    awaitTrue(
        () -> reviewerOutcome(issue.getId(), 0) == IssueRunOutcome.CHANGES_REQUESTED,
        CHAIN_TIMEOUT,
        () -> diagnose("first rejection", issue.getId()));
    IssueRun firstReviewerRun = reviewerRuns(issue.getId()).getFirst();
    assertEquals(IssueRunStatus.COMPLETED, firstReviewerRun.getStatus());
    assertEquals(firstExecutorRun.getId(), firstReviewerRun.getSubmissionRunId());
    assertEquals(reviewerAgent, firstReviewerRun.getAgentName());
    List<IssueActivity> firstDecisions =
        reviewDecisionsForSubmission(issue.getId(), firstExecutorRun.getId());
    assertEquals(1, firstDecisions.size());
    assertEquals(ReviewDecision.REQUEST_CHANGES, firstDecisions.getFirst().getDecision());

    // 阶段 3：重新执行必须复用同一 Session 与工作 Branch，而不是新建归属或新 Branch。
    awaitTrue(
        () -> submittedExecutorRuns(issue.getId()).size() >= 2,
        CHAIN_TIMEOUT,
        () -> diagnose("second submission", issue.getId()));
    IssueRun secondExecutorRun = submittedExecutorRuns(issue.getId()).get(1);
    assertExecutorSubmitted(secondExecutorRun, SECOND_EXECUTOR_SUMMARY);
    assertTrue(
        secondExecutorRun.getOrdinal() > firstExecutorRun.getOrdinal(),
        "second submission must be a new run ordinal");
    IssueAgentSession reusedExecutorSession = requireAgentSession(issue.getId(), executorAgent);
    assertEquals(executorSession.getSessionId(), reusedExecutorSession.getSessionId());
    assertEquals(executorSession.getThreadId(), reusedExecutorSession.getThreadId());

    // 阶段 4：复审批准 -> DONE。
    awaitIssueStatus(issue.getId(), IssueStatus.DONE, "approval");
    assertEquals(2, reviewerRuns(issue.getId()).size());
    IssueRun secondReviewerRun = reviewerRuns(issue.getId()).get(1);
    assertEquals(IssueRunStatus.COMPLETED, secondReviewerRun.getStatus());
    assertEquals(IssueRunOutcome.APPROVED, secondReviewerRun.getOutcome());
    assertEquals(secondExecutorRun.getId(), secondReviewerRun.getSubmissionRunId());

    // 归属事实：两个 Agent 各恰好一条稳定归属，Session 与工作 Branch 互不相同且各只有一个 Branch。
    IssueAgentSession reviewerSession = requireAgentSession(issue.getId(), reviewerAgent);
    assertNotEquals(executorSession.getSessionId(), reviewerSession.getSessionId());
    assertNotEquals(executorSession.getThreadId(), reviewerSession.getThreadId());
    assertEquals(2, agentSessionCount(issue.getId()));
    assertEquals(1, sessionThreadCount(executorSession.getSessionId()));
    assertEquals(1, sessionThreadCount(reviewerSession.getSessionId()));
    assertEquals(2, runCount(issue.getId(), IssueRunRole.EXECUTOR));
    assertEquals(2, runCount(issue.getId(), IssueRunRole.REVIEWER));

    // 决定事实：两次审查决定各自稳定幂等键，绝不互相覆盖；窗口内只有一次打回。
    List<IssueActivity> decisions = activities(issue.getId(), IssueActivityKind.REVIEW_DECISION);
    assertEquals(2, decisions.size());
    assertEquals(2, decisions.stream().map(IssueActivity::getIdempotencyKey).distinct().count());
    assertTrue(decisions.stream().allMatch(activity -> activity.getDecision() != null));
    assertEquals(1L, issueService.countRejections(issue.getId()));

    // materialized final：模型最终输出必须经 durable Entry 落库，并成为 Run 结果；issue_review 的调用与结果同样可读。
    assertTrue(
        messageEntriesLike(
                executorSession.getSessionId(), "ASSISTANT", like(FIRST_EXECUTOR_SUMMARY))
            >= 1);
    assertTrue(
        messageEntriesLike(
                executorSession.getSessionId(), "ASSISTANT", like(SECOND_EXECUTOR_SUMMARY))
            >= 1);
    assertTrue(toolCallEntries(reviewerSession.getSessionId(), "ASSISTANT") >= 2);
    // ToolResult 由 Tool batch apply 在审查决定落地之后才写入历史（决定与状态迁移在工具执行时同事务提交），
    // 因此结果历史必须用有界等待观察，不能与 DONE 判定同时断言。
    awaitTrue(
        () ->
            toolCallEntries(reviewerSession.getSessionId(), "TOOL") >= 2
                && messageEntriesLike(
                        reviewerSession.getSessionId(), "TOOL", like("CHANGES_REQUESTED"))
                    >= 1
                && messageEntriesLike(reviewerSession.getSessionId(), "TOOL", like("APPROVED"))
                    >= 1,
        CHAIN_TIMEOUT,
        () -> diagnose("issue_review history materialization", issue.getId()));
    assertFalse(
        scriptedModelGateway.reviewScriptExhausted(),
        "chain must not request more reviewer runs than scripted");
  }

  /**
   * 人工审查边界：阈值 N-1 回 TODO、N 阻塞、人工恢复重置窗口（不追溯历史打回）、重复决定键幂等且矛盾决定被拒。
   *
   * <p>Issue 不设审查 Agent，因此审查决定全部由人做出、执行仍由真实 Runtime 驱动；这样既覆盖人工恢复与阈值语义， 又不与 Agent 审查 Run 抢状态。
   */
  @Test
  void humanReviewThresholdRecoveryAndDecisionKeyReplayStayNonRetroactive() {
    String executorAgent = createAgent();
    Project project =
        projectService.createProject(
            "Human acceptance chain", "阈值与人工恢复验收链", false, HUMAN_REJECTION_THRESHOLD);
    Issue issue =
        issueService.createIssue(
            project.getId(),
            "Human review chain",
            "Deliver the change; humans decide on the review.",
            executorAgent,
            null,
            IssueStatus.TODO);

    // 阶段 1：真实执行 Run 提交 -> 等待人工审查。
    awaitIssueStatus(issue.getId(), IssueStatus.IN_REVIEW, "first submission");
    IssueAgentSession executorSession = requireAgentSession(issue.getId(), executorAgent);

    // 阶段 2：人工打回 #1（N-1 < 阈值）-> TODO。
    issueRunService.reviewByHuman(
        issue.getId(), ReviewDecision.REQUEST_CHANGES, "Human rejection one", "human-decision-1");
    assertEquals(IssueStatus.TODO, issueService.getIssue(issue.getId()).getStatus());
    assertEquals(1L, issueService.countRejections(issue.getId()));

    // 阶段 3：重复决定键：同键同决定重放不追加决定，同键异决定必须显式拒绝。
    int decisionsBeforeReplay = activities(issue.getId(), IssueActivityKind.REVIEW_DECISION).size();
    issueRunService.reviewByHuman(
        issue.getId(), ReviewDecision.REQUEST_CHANGES, "Human rejection one", "human-decision-1");
    assertEquals(
        decisionsBeforeReplay,
        activities(issue.getId(), IssueActivityKind.REVIEW_DECISION).size(),
        "replaying the same decision key must not append another decision");
    AiValidationException conflict =
        assertThrows(
            AiValidationException.class,
            () ->
                issueRunService.reviewByHuman(
                    issue.getId(), ReviewDecision.APPROVE, "Conflict", "human-decision-1"));
    assertEquals("Review decision conflict", conflict.getMessage());

    // 阶段 4：人工打回 #2（达到阈值 N）-> BLOCKED，且没有人工恢复就不被自动流程重新唤醒。
    awaitIssueStatus(issue.getId(), IssueStatus.IN_REVIEW, "second submission");
    issueRunService.reviewByHuman(
        issue.getId(), ReviewDecision.REQUEST_CHANGES, "Human rejection two", "human-decision-2");
    awaitIssueStatus(issue.getId(), IssueStatus.BLOCKED, "threshold reached");
    assertEquals(HUMAN_REJECTION_THRESHOLD, issueService.countRejections(issue.getId()));
    assertRemainsBlockedWithoutAutoDispatch(issue.getId());

    // 阶段 5：人工恢复 -> TODO 且审查窗口重置；历史打回事实保留（只追加，不追溯、不改写）。
    int decisionsBeforeRecovery =
        activities(issue.getId(), IssueActivityKind.REVIEW_DECISION).size();
    assertEquals(2, decisionsBeforeRecovery);
    Issue blocked = issueService.getIssue(issue.getId());
    issueService.recoverIssue(
        issue.getId(), blocked.getVersion(), false, "Human recovered after two rejections");
    assertEquals(IssueStatus.TODO, issueService.getIssue(issue.getId()).getStatus());
    assertEquals(
        0L,
        issueService.countRejections(issue.getId()),
        "recovery must reset the rejection window instead of retroactively blocking the next cycle");
    assertEquals(
        decisionsBeforeRecovery,
        activities(issue.getId(), IssueActivityKind.REVIEW_DECISION).size(),
        "recovery is a new fact: historical rejection decisions must stay, not be rewritten");

    // 阶段 6：恢复后的打回按新窗口计数：1 < N -> TODO（若历史打回被追溯，这里会直接阻塞）。
    awaitIssueStatus(issue.getId(), IssueStatus.IN_REVIEW, "third submission");
    issueRunService.reviewByHuman(
        issue.getId(), ReviewDecision.REQUEST_CHANGES, "Human rejection three", "human-decision-3");
    assertEquals(IssueStatus.TODO, issueService.getIssue(issue.getId()).getStatus());
    assertEquals(1L, issueService.countRejections(issue.getId()));

    // 阶段 7：最终人工批准 -> DONE。
    awaitIssueStatus(issue.getId(), IssueStatus.IN_REVIEW, "fourth submission");
    issueRunService.reviewByHuman(
        issue.getId(), ReviewDecision.APPROVE, "Human approval", "human-decision-4");
    awaitIssueStatus(issue.getId(), IssueStatus.DONE, "approval");

    // 执行 Branch 全程复用：4 次执行 Run 始终绑定同一 Session 与同一 Branch。
    IssueAgentSession finalExecutorSession = requireAgentSession(issue.getId(), executorAgent);
    assertEquals(executorSession.getSessionId(), finalExecutorSession.getSessionId());
    assertEquals(executorSession.getThreadId(), finalExecutorSession.getThreadId());
    assertEquals(1, agentSessionCount(issue.getId()));
    assertEquals(1, sessionThreadCount(executorSession.getSessionId()));
    assertEquals(4, runCount(issue.getId(), IssueRunRole.EXECUTOR));
    assertEquals(0, runCount(issue.getId(), IssueRunRole.REVIEWER));
  }

  private void assertExecutorSubmitted(IssueRun run, String expectedSummary) {
    assertNotNull(run, "executor run must exist");
    assertEquals(IssueRunRole.EXECUTOR, run.getRole());
    assertEquals(IssueRunStatus.COMPLETED, run.getStatus());
    assertEquals(IssueRunOutcome.SUBMITTED, run.getOutcome());
    assertNotNull(run.getResult(), "run result must be materialized");
    assertTrue(
        run.getResult().contains(expectedSummary),
        "run result must carry the final assistant summary, actual=" + run.getResult());
  }

  private IssueAgentSession requireAgentSession(UUID issueId, String agentName) {
    IssueAgentSession agentSession = issueRunService.getAgentSession(issueId, agentName);
    assertNotNull(agentSession, "agent session must be bound for agent " + agentName);
    return agentSession;
  }

  private List<IssueActivity> activities(UUID issueId, IssueActivityKind kind) {
    return issueService.listActivities(issueId).stream()
        .filter(activity -> activity.getKind() == kind)
        .toList();
  }

  /** 已提交的执行 Run，按 ordinal 升序：用 ordinal 定位具体 Run，避免“最新 Run”在快速链路里被后续阶段覆盖。 */
  private List<IssueRun> submittedExecutorRuns(UUID issueId) {
    return runs(issueId, IssueRunRole.EXECUTOR).stream()
        .filter(run -> run.getOutcome() == IssueRunOutcome.SUBMITTED)
        .toList();
  }

  private List<IssueRun> reviewerRuns(UUID issueId) {
    return runs(issueId, IssueRunRole.REVIEWER);
  }

  private IssueRunOutcome reviewerOutcome(UUID issueId, int index) {
    List<IssueRun> reviewerRuns = reviewerRuns(issueId);
    return index < reviewerRuns.size() ? reviewerRuns.get(index).getOutcome() : null;
  }

  private List<IssueRun> runs(UUID issueId, IssueRunRole role) {
    return issueRunService.listRuns(issueId).stream().filter(run -> run.getRole() == role).toList();
  }

  /** 绑定到某次提交的执行 Run 的审查决定：用 submissionRunId 关联，避免统计到其它周期的决定。 */
  private List<IssueActivity> reviewDecisionsForSubmission(UUID issueId, UUID submissionRunId) {
    return activities(issueId, IssueActivityKind.REVIEW_DECISION).stream()
        .filter(activity -> submissionRunId.equals(activity.getSubmissionRunId()))
        .toList();
  }

  private int runCount(UUID issueId, IssueRunRole role) {
    return runs(issueId, role).size();
  }

  private int agentSessionCount(UUID issueId) {
    return count("select count(*) from project_issue_agent_session where issue_id = ?", issueId);
  }

  private int sessionThreadCount(UUID sessionId) {
    return count("select count(*) from harness_thread where session_id = ?", sessionId);
  }

  /** durable Entry 断言：模型最终文本与 issue_review 的调用/结果必须真的落在 Harness 历史里，而不只存在于内存或 Run 结果。 */
  private int messageEntriesLike(UUID sessionId, String role, String payloadLike) {
    return count(
        "select count(*) from harness_entry where session_id = ?"
            + " and payload->'message'->>'role' = ? and payload::text like ?",
        sessionId,
        role,
        payloadLike);
  }

  private static String like(String fragment) {
    return "%" + fragment + "%";
  }

  /** 按 JSON 结构断言消息内容里出现的工具名：{@code payload::text} 的 jsonb 规范形态带空格，直接拼字符串会漏配。 */
  private int toolCallEntries(UUID sessionId, String role) {
    return count(
        "select count(*) from harness_entry where session_id = ?"
            + " and payload->'message'->>'role' = ?"
            + " and exists (select 1 from jsonb_array_elements(payload->'message'->'contents') item"
            + " where item->>'toolName' = ?)",
        sessionId,
        role,
        REVIEW_TOOL_NAME);
  }

  private int count(String sql, Object... args) {
    Integer value = jdbc.queryForObject(sql, Integer.class, args);
    assertNotNull(value, "count query must return a value");
    return value;
  }

  private void awaitIssueStatus(UUID issueId, IssueStatus expected, String stage) {
    awaitTrue(
        () -> issueService.getIssue(issueId).getStatus() == expected,
        CHAIN_TIMEOUT,
        () -> diagnose(stage, issueId));
  }

  /** 超时诊断：给出状态、最新 Run、打回计数与脚本消耗，便于区分“链路没推进”和“契约不符”。 */
  private String diagnose(String stage, UUID issueId) {
    return "stage ["
        + stage
        + "] expected issue status change but observed "
        + issueService.getIssue(issueId).getStatus()
        + "; latestRun="
        + describeRun(issueRunService.getLatestRun(issueId))
        + " runs="
        + issueRunService.listRuns(issueId).stream()
            .map(IssueAcceptanceChainRuntimeIntegrationTest::describeRun)
            .toList()
        + " rejections="
        + issueService.countRejections(issueId)
        + " scriptedRequests="
        + scriptedModelGateway.requestCount()
        + " scriptExhausted="
        + scriptedModelGateway.reviewScriptExhausted();
  }

  /**
   * BLOCKED 静止观察：人工阻塞态必须停止自动推进，只有人恢复才继续。
   *
   * <p>用有界窗口（远大于调度周期）而不是零等待，才能观察到“没人恢复就不动”这一事实；窗口内出现任何新 Run 都判失败。
   */
  private void assertRemainsBlockedWithoutAutoDispatch(UUID issueId) {
    long runsBefore = issueRunService.listRuns(issueId).size();
    long deadline = System.nanoTime() + QUIET_WINDOW.toNanos();
    while (System.nanoTime() < deadline) {
      assertEquals(IssueStatus.BLOCKED, issueService.getIssue(issueId).getStatus());
      assertNull(issueRunService.getActiveRun(issueId), "blocked issue must not own an active run");
      sleep(POLL_INTERVAL);
    }
    assertEquals(
        runsBefore,
        issueRunService.listRuns(issueId).size(),
        "blocked issue must not be restarted by the automatic controller");
  }

  private static String describeRun(IssueRun run) {
    if (run == null) {
      return "null";
    }
    return "{"
        + run.getOrdinal()
        + ","
        + run.getRole()
        + ","
        + run.getStatus()
        + ","
        + run.getOutcome()
        + "}";
  }

  private static void awaitTrue(
      BooleanSupplier condition, Duration timeout, Supplier<String> message) {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (System.nanoTime() < deadline) {
      if (condition.getAsBoolean()) {
        return;
      }
      sleep(POLL_INTERVAL);
    }
    fail(message.get());
  }

  private static void sleep(Duration duration) {
    try {
      TimeUnit.NANOSECONDS.sleep(duration.toNanos());
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted while waiting for the acceptance chain", error);
    }
  }

  /** 插入最小合法 Agent 定义及其 provider/model：链路的模型选择必须能通过严格解析。 */
  private String createAgent() {
    long id = TEST_FIXTURE_SEQUENCE.incrementAndGet() + System.nanoTime() % 1_000_000L;
    String providerName = "acceptance-provider-" + id;
    String modelName = "acceptance-model-" + id;
    String agentName = "acceptance-agent-" + id;
    jdbc.update(
        "insert into agent_provider (name, provider_type, config, connection_generation_id)"
            + " values (?, 'openai', '{}'::jsonb, ?::uuid)",
        providerName,
        UUID.randomUUID());
    jdbc.update(
        "insert into agent_model (provider_name, name, model_id, config) values (?, ?, ?, ?::jsonb)",
        providerName,
        modelName,
        "acceptance-wire-" + id,
        MODEL_CONFIG);
    jdbc.update(
        "insert into agent_definition (name, model_provider_name, model_name, config)"
            + " values (?, ?, ?, '{\"tools\":[],\"skills\":[],\"subagents\":[]}'::jsonb)",
        agentName,
        providerName,
        modelName);
    return agentName;
  }

  /** 模型必须声明工具能力：Project 角色工具会被注入每个 Issue Agent Branch 的模型工具面。 */
  private static final String MODEL_CONFIG =
      """
      {"limit":{"context":128000,"output":8192},\
      "abilities":{"tools":true,"reasoning":true,"inputModalities":["TEXT"]},\
      "variants":[{"id":"default"}],"defaultVariant":"default",\
      "pricing":{"currency":"USD","pricingTier":"standard","serviceTier":"standard",\
      "serviceTierMultiplier":1.0,"version":"acceptance-2026-01-01",\
      "inputPerMillionTokens":0,"outputPerMillionTokens":0,"cacheReadPerMillionTokens":0,\
      "cacheWritePerMillionTokens":0,"cacheWriteLongPerMillionTokens":0,\
      "reasoningPerMillionTokens":0}}
      """;

  private static Connection newConnection() throws SQLException {
    return DriverManager.getConnection(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
  }

  private static void resetSchema(Connection connection) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.execute("drop schema if exists public cascade");
      statement.execute("create schema public");
    }
    // V1 baseline（唯一事实源）提供 Harness 7 表、system_setting 默认行与 Project/Issue 表。
    Flyway.configure()
        .dataSource(new SingleConnectionDataSource(connection, true))
        .locations("classpath:db/migration")
        .validateMigrationNaming(true)
        .load()
        .migrate();
  }

  /** 用脚本化 Model Gateway 替换真实 Provider 调用：测试配置在自动配置之后处理，因此用 @Primary 覆盖注入。 */
  @TestConfiguration(proxyBeanMethods = false)
  static class ScriptedGatewayConfiguration {

    @Bean
    @Primary
    ScriptedModelGateway scriptedModelGateway() {
      return new ScriptedModelGateway(List.of());
    }
  }
}
