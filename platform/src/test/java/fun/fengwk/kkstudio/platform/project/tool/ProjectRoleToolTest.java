package fun.fengwk.kkstudio.platform.project.tool;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.common.result.TextResultContent;
import fun.fengwk.kkstudio.harness.contributor.api.BranchView;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionContext;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionListener;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionRequest;
import fun.fengwk.kkstudio.harness.contributor.api.ToolOutcome;
import fun.fengwk.kkstudio.harness.tool.ToolCall;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import fun.fengwk.kkstudio.platform.error.AiResourceNotFoundException;
import fun.fengwk.kkstudio.platform.error.AiValidationException;
import fun.fengwk.kkstudio.platform.error.AiVersionConflictException;
import fun.fengwk.kkstudio.platform.project.model.Issue;
import fun.fengwk.kkstudio.platform.project.model.IssueActivity;
import fun.fengwk.kkstudio.platform.project.model.IssueActivityActorType;
import fun.fengwk.kkstudio.platform.project.model.IssueActivityKind;
import fun.fengwk.kkstudio.platform.project.model.IssueAgentSession;
import fun.fengwk.kkstudio.platform.project.model.IssueDependency;
import fun.fengwk.kkstudio.platform.project.model.IssueEvidence;
import fun.fengwk.kkstudio.platform.project.model.IssueEvidenceOrigin;
import fun.fengwk.kkstudio.platform.project.model.IssueRun;
import fun.fengwk.kkstudio.platform.project.model.IssueRunOutcome;
import fun.fengwk.kkstudio.platform.project.model.IssueRunRole;
import fun.fengwk.kkstudio.platform.project.model.IssueRunStatus;
import fun.fengwk.kkstudio.platform.project.model.IssueStatus;
import fun.fengwk.kkstudio.platform.project.model.Project;
import fun.fengwk.kkstudio.platform.project.model.ReviewDecision;
import fun.fengwk.kkstudio.platform.project.service.IssueEvidenceService;
import fun.fengwk.kkstudio.platform.project.service.IssueRunService;
import fun.fengwk.kkstudio.platform.project.service.IssueService;
import fun.fengwk.kkstudio.platform.project.service.ProjectService;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link ProjectRoleTool} 单元测试。
 *
 * <p>测试意图：
 *
 * <ul>
 *   <li>验证 3 个真实工具（issue_read, issue_request_input, issue_review）的 JSON Schema
 *       严格性：未知字段拦截、必填字段检查与枚举/类型校验；
 *   <li>验证 Executor(2)、Reviewer(3) 角色工具正向 dispatch 链路与参数传递；
 *   <li>验证 issue_review 工具的 terminalActionId 必须由 tool:{invocationId} 自动生成且绑定被审查提交；
 *   <li>验证权限与角色控制：无属主、角色不匹配（如 Executor 尝试调用 issue_review）、缺失上下文或跨 Issue 访问均被严格拒绝；
 *   <li>验证业务参数校验（空白问题/理由、负数游标、超范围 activity_limit、非法 UUID/枚举、畸形 JSON）；
 *   <li>验证安全脱敏：非法 UUID 与 Enum 原值不回显，业务异常去标识，未捕获异常不保留内部 cause；
 *   <li>验证 ToolExecutionListener 的回调严格互斥且恰好触发一次；
 *   <li>验证 Tool.type()、descriptor() 与 historyRenderer() 完整可用。
 * </ul>
 */
class ProjectRoleToolTest {

  private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000010");
  private static final UUID ISSUE_ID = UUID.fromString("00000000-0000-0000-0000-000000000020");
  private static final UUID DEP_ISSUE_ID = UUID.fromString("00000000-0000-0000-0000-000000000030");
  private static final UUID RUN_ID = UUID.fromString("00000000-0000-0000-0000-000000000040");
  private static final UUID REVIEW_RUN_ID = UUID.fromString("00000000-0000-0000-0000-000000000045");
  private static final UUID THREAD_ID = UUID.fromString("00000000-0000-0000-0000-000000000050");
  private static final UUID INVOCATION_ID = UUID.fromString("00000000-0000-0000-0000-000000000060");
  private static final UUID SESSION_ID = UUID.fromString("00000000-0000-0000-0000-000000000070");
  private static final UUID EVIDENCE_BLOB_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000080");
  private static final String CALL_ID = "call-123";

  private ProjectService projectService;
  private IssueService issueService;
  private IssueRunService issueRunService;
  private IssueEvidenceService issueEvidenceService;
  private ProjectRoleToolService toolService;
  private ProjectThreadOwnerResolver ownerResolver;

  private ProjectThreadOwnerContext executorOwner;
  private ProjectThreadOwnerContext reviewerOwner;
  private ToolExecutionContext executionContext;

  @BeforeEach
  void setUp() {
    projectService = mock(ProjectService.class);
    issueService = mock(IssueService.class);
    issueRunService = mock(IssueRunService.class);
    issueEvidenceService = mock(IssueEvidenceService.class);
    ownerResolver = mock(ProjectThreadOwnerResolver.class);

    toolService =
        new ProjectRoleToolService(
            projectService, issueService, issueRunService, issueEvidenceService);

    executorOwner =
        new ProjectThreadOwnerContext(
            ProjectRole.EXECUTOR, PROJECT_ID, ISSUE_ID, RUN_ID, "coder-agent");
    reviewerOwner =
        new ProjectThreadOwnerContext(
            ProjectRole.REVIEWER, PROJECT_ID, ISSUE_ID, REVIEW_RUN_ID, "reviewer-agent");

    BranchView branchView = mock(BranchView.class);
    executionContext =
        new ToolExecutionContext(INVOCATION_ID, THREAD_ID, Instant.now(), branchView);
  }

  private ProjectRoleTool createTool(ProjectRoleToolType type) {
    return new ProjectRoleTool(type, ownerResolver, toolService);
  }

  private ToolExecutionRequest createRequest(
      ToolDescriptor descriptor, String argumentsJson, ToolExecutionContext context) {
    ToolCall call = new ToolCall(CALL_ID, descriptor.name(), argumentsJson);
    return new ToolExecutionRequest(descriptor, call, Duration.ofSeconds(30), context);
  }

  private static class TestListener implements ToolExecutionListener {
    ToolOutcome outcome;
    Throwable error;
    int count = 0;

    @Override
    public void onPartial(ToolResult partial) {}

    @Override
    public void onComplete(ToolOutcome outcome) {
      this.outcome = outcome;
      count++;
    }

    @Override
    public void onError(Throwable error) {
      this.error = error;
      count++;
    }
  }

  // --- 1. Schema 严格性验证 ---

  @Test
  void schemaValidation_rejectsUnknownPropertiesAcrossAll3Tools() {
    // 验证全部 3 个工具从最小合法参数出发，均因 additionalProperties=false 严格拒绝未知字段
    for (ProjectRoleToolType type : ProjectRoleToolType.values()) {
      ToolDescriptor desc = type.descriptor();
      String validArguments = minimumValidArguments(type);
      assertDoesNotThrow(
          () ->
              new ToolExecutionRequest(
                  desc, new ToolCall(CALL_ID, desc.name(), validArguments), Duration.ofSeconds(30)),
          "Tool " + type.modelName() + " minimum valid arguments must be accepted");
      String unknownArgs =
          validArguments.equals("{}")
              ? "{\"extra_unknown_field\":\"malicious\"}"
              : validArguments.substring(0, validArguments.length() - 1)
                  + ",\"extra_unknown_field\":\"malicious\"}";
      assertThrows(
          IllegalArgumentException.class,
          () ->
              new ToolExecutionRequest(
                  desc, new ToolCall(CALL_ID, desc.name(), unknownArgs), Duration.ofSeconds(30)),
          "Tool " + type.modelName() + " must reject unknown properties");
    }
  }

  @Test
  void schemaValidation_requiresMandatoryFields() {
    // 验证 issue_request_input 必填 question，issue_review 必填 decision 与 reason
    assertSchemaRejected(ProjectRoleToolType.ISSUE_REQUEST_INPUT, "{}");
    assertSchemaRejected(ProjectRoleToolType.ISSUE_REQUEST_INPUT, "{\"context\":\"details\"}");
    assertSchemaRejected(ProjectRoleToolType.ISSUE_REVIEW, "{}");
    assertSchemaRejected(ProjectRoleToolType.ISSUE_REVIEW, "{\"decision\":\"APPROVE\"}");
    assertSchemaRejected(ProjectRoleToolType.ISSUE_REVIEW, "{\"reason\":\"ok\"}");
  }

  @Test
  void schemaValidation_rejectsInvalidDecisionEnum() {
    // 验证 issue_review 的 decision 仅允许 APPROVE 或 REQUEST_CHANGES
    assertSchemaRejected(
        ProjectRoleToolType.ISSUE_REVIEW, "{\"decision\":\"DENY\",\"reason\":\"ok\"}");
    assertSchemaRejected(
        ProjectRoleToolType.ISSUE_REVIEW, "{\"decision\":\"REJECT\",\"reason\":\"ok\"}");
  }

  @Test
  void schemaValidation_rejectsWrongDataTypes() {
    // 验证 Schema 拒绝非预期的字段数据类型
    assertSchemaRejected(ProjectRoleToolType.ISSUE_READ, "{\"issue_id\":123}");
    assertSchemaRejected(ProjectRoleToolType.ISSUE_READ, "{\"activity_limit\":\"many\"}");
    assertSchemaRejected(ProjectRoleToolType.ISSUE_REQUEST_INPUT, "{\"question\":123}");
    assertSchemaRejected(ProjectRoleToolType.ISSUE_REVIEW, "{\"decision\":123,\"reason\":\"ok\"}");
  }

  // --- 2. 角色权限与范围控制 ---

  @Test
  void execute_executorCanExecuteIssueReadAndRequestInput() {
    // 验证 Executor 角色有权调用 issue_read 与 issue_request_input
    when(ownerResolver.resolve(THREAD_ID)).thenReturn(Optional.of(executorOwner));

    IssueRun run =
        IssueRun.builder()
            .id(RUN_ID)
            .issueId(ISSUE_ID)
            .role(IssueRunRole.EXECUTOR)
            .agentName("coder-agent")
            .status(IssueRunStatus.RUNNING)
            .build();
    when(issueRunService.getRun(RUN_ID)).thenReturn(run);
    when(issueService.getIssue(ISSUE_ID))
        .thenReturn(
            Issue.builder()
                .id(ISSUE_ID)
                .projectId(PROJECT_ID)
                .status(IssueStatus.IN_PROGRESS)
                .build());
    when(projectService.getProject(PROJECT_ID))
        .thenReturn(Project.builder().id(PROJECT_ID).title("Test Project").build());
    when(issueService.listActivitiesPage(ISSUE_ID, 0L, 51)).thenReturn(List.of());
    when(issueService.countRejections(ISSUE_ID)).thenReturn(0L);
    when(issueService.listDependencies(ISSUE_ID)).thenReturn(List.of());

    ProjectRoleTool readTool = createTool(ProjectRoleToolType.ISSUE_READ);
    TestListener readListener = new TestListener();
    readTool.execute(createRequest(readTool.descriptor(), "{}", executionContext), readListener);
    assertNotNull(readListener.outcome);
    assertFalse(readListener.outcome.result().error());

    ProjectRoleTool inputTool = createTool(ProjectRoleToolType.ISSUE_REQUEST_INPUT);
    when(issueRunService.requestInput(RUN_ID, "Need help", null))
        .thenReturn(
            IssueRun.builder()
                .id(RUN_ID)
                .issueId(ISSUE_ID)
                .role(IssueRunRole.EXECUTOR)
                .agentName("coder-agent")
                .status(IssueRunStatus.WAITING_HUMAN)
                .waitingReason("Need help")
                .build());
    TestListener inputListener = new TestListener();
    inputTool.execute(
        createRequest(inputTool.descriptor(), "{\"question\":\"Need help\"}", executionContext),
        inputListener);
    assertNotNull(inputListener.outcome);
    assertFalse(inputListener.outcome.result().error());
  }

  @Test
  void execute_executorDeniedIssueReview() {
    // 验证 Executor 角色被严格拒绝调用 issue_review 工具
    when(ownerResolver.resolve(THREAD_ID)).thenReturn(Optional.of(executorOwner));

    ProjectRoleTool reviewTool = createTool(ProjectRoleToolType.ISSUE_REVIEW);
    String args = "{\"decision\":\"APPROVE\",\"reason\":\"Looks good\"}";
    ToolExecutionRequest request = createRequest(reviewTool.descriptor(), args, executionContext);
    TestListener listener = new TestListener();
    reviewTool.execute(request, listener);

    assertNotNull(listener.outcome);
    ToolResult result = listener.outcome.result();
    assertTrue(result.error());
    String text = ((TextResultContent) result.contents().get(0)).text();
    assertEquals("Tool not permitted for current role or unowned session", text);
  }

  @Test
  void execute_reviewerCanExecuteAll3Tools() {
    // 验证 Reviewer 角色拥有全部 3 个工具的执行权限
    when(ownerResolver.resolve(THREAD_ID)).thenReturn(Optional.of(reviewerOwner));

    IssueRun run =
        IssueRun.builder()
            .id(REVIEW_RUN_ID)
            .issueId(ISSUE_ID)
            .role(IssueRunRole.REVIEWER)
            .agentName("reviewer-agent")
            .submissionRunId(RUN_ID)
            .status(IssueRunStatus.RUNNING)
            .build();
    when(issueRunService.getRun(REVIEW_RUN_ID)).thenReturn(run);
    when(issueRunService.getRun(RUN_ID))
        .thenReturn(
            IssueRun.builder()
                .id(RUN_ID)
                .issueId(ISSUE_ID)
                .role(IssueRunRole.EXECUTOR)
                .agentName("coder-agent")
                .status(IssueRunStatus.COMPLETED)
                .outcome(IssueRunOutcome.SUBMITTED)
                .build());
    when(issueService.getIssue(ISSUE_ID))
        .thenReturn(
            Issue.builder()
                .id(ISSUE_ID)
                .projectId(PROJECT_ID)
                .status(IssueStatus.IN_REVIEW)
                .build());
    when(projectService.getProject(PROJECT_ID))
        .thenReturn(Project.builder().id(PROJECT_ID).title("Test Project").build());
    when(issueService.listActivitiesPage(ISSUE_ID, 0L, 51)).thenReturn(List.of());
    when(issueService.countRejections(ISSUE_ID)).thenReturn(0L);
    when(issueService.listDependencies(ISSUE_ID)).thenReturn(List.of());

    // 1. issue_read
    ProjectRoleTool readTool = createTool(ProjectRoleToolType.ISSUE_READ);
    TestListener readListener = new TestListener();
    readTool.execute(createRequest(readTool.descriptor(), "{}", executionContext), readListener);
    assertFalse(readListener.outcome.result().error());

    // 2. issue_request_input
    ProjectRoleTool inputTool = createTool(ProjectRoleToolType.ISSUE_REQUEST_INPUT);
    when(issueRunService.requestInput(REVIEW_RUN_ID, "Clarify design", null))
        .thenReturn(
            IssueRun.builder()
                .id(REVIEW_RUN_ID)
                .issueId(ISSUE_ID)
                .role(IssueRunRole.REVIEWER)
                .agentName("reviewer-agent")
                .status(IssueRunStatus.WAITING_HUMAN)
                .build());
    TestListener inputListener = new TestListener();
    inputTool.execute(
        createRequest(
            inputTool.descriptor(), "{\"question\":\"Clarify design\"}", executionContext),
        inputListener);
    assertFalse(inputListener.outcome.result().error());

    // 3. issue_review
    ProjectRoleTool reviewTool = createTool(ProjectRoleToolType.ISSUE_REVIEW);
    when(issueRunService.reviewByAgent(
            REVIEW_RUN_ID,
            "reviewer-agent",
            "tool:" + INVOCATION_ID,
            ReviewDecision.APPROVE,
            "Looks good"))
        .thenReturn(
            IssueRun.builder()
                .id(REVIEW_RUN_ID)
                .issueId(ISSUE_ID)
                .role(IssueRunRole.REVIEWER)
                .agentName("reviewer-agent")
                .status(IssueRunStatus.COMPLETED)
                .outcome(IssueRunOutcome.APPROVED)
                .build());
    TestListener reviewListener = new TestListener();
    reviewTool.execute(
        createRequest(
            reviewTool.descriptor(),
            "{\"decision\":\"APPROVE\",\"reason\":\"Looks good\"}",
            executionContext),
        reviewListener);
    assertFalse(reviewListener.outcome.result().error());
  }

  @Test
  void execute_missingInvocationContext_returnsErrorToolResult() {
    // 验证缺失 invocation context 时返回通用错误
    ProjectRoleTool tool = createTool(ProjectRoleToolType.ISSUE_READ);
    ToolExecutionRequest request = createRequest(tool.descriptor(), "{}", null);

    TestListener listener = new TestListener();
    tool.execute(request, listener);

    ToolResult result = listener.outcome.result();
    assertTrue(result.error());
    assertEquals(
        "Missing invocation context", ((TextResultContent) result.contents().get(0)).text());
  }

  @Test
  void execute_missingThreadIdInContext_returnsErrorToolResult() {
    // 验证 context 中 threadId 为 null 时返回 Missing invocation context
    ProjectRoleTool tool = createTool(ProjectRoleToolType.ISSUE_READ);
    ToolExecutionContext invalidContext = mock(ToolExecutionContext.class);
    when(invalidContext.threadId()).thenReturn(null);
    ToolExecutionRequest request = createRequest(tool.descriptor(), "{}", invalidContext);

    TestListener listener = new TestListener();
    tool.execute(request, listener);

    ToolResult result = listener.outcome.result();
    assertTrue(result.error());
    assertEquals(
        "Missing invocation context", ((TextResultContent) result.contents().get(0)).text());
  }

  @Test
  void execute_unownedThread_returnsErrorToolResult() {
    // 验证未解析到属主的线程返回权限与未认领会话错误
    ProjectRoleTool tool = createTool(ProjectRoleToolType.ISSUE_READ);
    when(ownerResolver.resolve(THREAD_ID)).thenReturn(Optional.empty());

    ToolExecutionRequest request = createRequest(tool.descriptor(), "{}", executionContext);
    TestListener listener = new TestListener();
    tool.execute(request, listener);

    ToolResult result = listener.outcome.result();
    assertTrue(result.error());
    assertEquals(
        "Tool not permitted for current role or unowned session",
        ((TextResultContent) result.contents().get(0)).text());
  }

  // --- 3. 正向执行与 Payload 结构验证 ---

  @Test
  void execute_issueRead_executor_success_returnsPayloadAndDesensitizes() {
    // 验证 issue_read 能够正确读取项目摘要、Issue 详情、依赖（包含 target 详情）、本 Run 摘要与分页 Activity
    ProjectRoleTool tool = createTool(ProjectRoleToolType.ISSUE_READ);
    when(ownerResolver.resolve(THREAD_ID)).thenReturn(Optional.of(executorOwner));

    IssueRun run =
        IssueRun.builder()
            .id(RUN_ID)
            .issueId(ISSUE_ID)
            .ordinal(1L)
            .role(IssueRunRole.EXECUTOR)
            .agentName("coder-agent")
            .status(IssueRunStatus.RUNNING)
            .observedActivitySequence(2L)
            .continuationCount(0)
            .maxContinuations(5)
            .terminalActionId("tool:secret-terminal-action-id")
            .createdAt(Instant.now())
            .build();
    when(issueRunService.getRun(RUN_ID)).thenReturn(run);

    Issue issue =
        Issue.builder()
            .id(ISSUE_ID)
            .projectId(PROJECT_ID)
            .number(1L)
            .title("Implement Feature")
            .description("Detailed requirement description")
            .status(IssueStatus.IN_PROGRESS)
            .assigneeAgentName("coder-agent")
            .reviewerAgentName("reviewer-agent")
            .version(2L)
            .createdAt(Instant.now())
            .updatedAt(Instant.now())
            .build();
    when(issueService.getIssue(ISSUE_ID)).thenReturn(issue);

    Project project =
        Project.builder()
            .id(PROJECT_ID)
            .title("Test Project")
            .description("Project Description")
            .yoloEnabled(true)
            .maxReviewRejections(3)
            .nextIssueNumber(2L)
            .version(1L)
            .build();
    when(projectService.getProject(PROJECT_ID)).thenReturn(project);

    Issue targetDep =
        Issue.builder()
            .id(DEP_ISSUE_ID)
            .projectId(PROJECT_ID)
            .number(99L)
            .title("Prerequisite Task")
            .status(IssueStatus.DONE)
            .build();
    when(issueService.getIssue(DEP_ISSUE_ID)).thenReturn(targetDep);
    when(issueService.listDependencies(ISSUE_ID))
        .thenReturn(
            List.of(
                IssueDependency.builder()
                    .issueId(ISSUE_ID)
                    .dependsOnIssueId(DEP_ISSUE_ID)
                    .projectId(PROJECT_ID)
                    .build()));

    IssueActivity activity1 =
        IssueActivity.builder()
            .issueId(ISSUE_ID)
            .sequence(1L)
            .kind(IssueActivityKind.SPEC_CHANGE)
            .actorType(IssueActivityActorType.HUMAN)
            .body("Initial requirements")
            .idempotencyKey("secret-idempotency-key")
            .createdAt(Instant.now())
            .build();
    when(issueService.listActivitiesPage(ISSUE_ID, 0L, 51)).thenReturn(List.of(activity1));
    when(issueService.countRejections(ISSUE_ID)).thenReturn(0L);

    IssueAgentSession agentSession =
        IssueAgentSession.builder()
            .id(UUID.randomUUID())
            .issueId(ISSUE_ID)
            .agentName("coder-agent")
            .sessionId(SESSION_ID)
            .threadId(THREAD_ID)
            .build();
    when(issueRunService.getAgentSession(ISSUE_ID, "coder-agent")).thenReturn(agentSession);

    // 已发布证据以规范 URI 暴露：它是资源标识而不是读取凭据，因此只投影元数据，不暴露 blob 内部字段之外的敏感值
    when(issueEvidenceService.listEvidence(ISSUE_ID))
        .thenReturn(
            List.of(
                IssueEvidence.builder()
                    .issueId(ISSUE_ID)
                    .blobId(EVIDENCE_BLOB_ID)
                    .origin(IssueEvidenceOrigin.EXECUTOR)
                    .runId(RUN_ID)
                    .createdAt(Instant.now())
                    .build()));

    String args = String.format("{\"issue_id\":\"%s\"}", ISSUE_ID);
    ToolExecutionRequest request = createRequest(tool.descriptor(), args, executionContext);
    TestListener listener = new TestListener();
    tool.execute(request, listener);

    ToolResult result = listener.outcome.result();
    assertFalse(result.error());
    String text = ((TextResultContent) result.contents().get(0)).text();

    assertTrue(text.contains("Implement Feature"));
    assertTrue(text.contains("Test Project"));
    assertTrue(text.contains("Prerequisite Task"));
    assertTrue(text.contains("Initial requirements"));
    assertTrue(text.contains("\"satisfied\" : true"));
    assertTrue(
        text.contains("kkstudio:/resources/" + EVIDENCE_BLOB_ID),
        "published evidence must be exposed as a canonical resource uri");
    assertTrue(text.contains("\"origin\" : \"EXECUTOR\""));
    assertFalse(text.contains("secret-idempotency-key"), "idempotencyKey must not be exposed");
    assertFalse(text.contains("secret-terminal-action-id"), "terminalActionId must not be exposed");
  }

  @Test
  void execute_issueRead_reviewerWithSubmissionRun_includesSubmissionRun() {
    // 验证 Reviewer 角色的 issue_read 会额外返回被审查提交 Run 的详情
    ProjectRoleTool tool = createTool(ProjectRoleToolType.ISSUE_READ);
    when(ownerResolver.resolve(THREAD_ID)).thenReturn(Optional.of(reviewerOwner));

    IssueRun reviewerRun =
        IssueRun.builder()
            .id(REVIEW_RUN_ID)
            .issueId(ISSUE_ID)
            .ordinal(2L)
            .role(IssueRunRole.REVIEWER)
            .agentName("reviewer-agent")
            .submissionRunId(RUN_ID)
            .status(IssueRunStatus.RUNNING)
            .createdAt(Instant.now())
            .build();
    when(issueRunService.getRun(REVIEW_RUN_ID)).thenReturn(reviewerRun);

    IssueRun executorSubmission =
        IssueRun.builder()
            .id(RUN_ID)
            .issueId(ISSUE_ID)
            .ordinal(1L)
            .role(IssueRunRole.EXECUTOR)
            .agentName("coder-agent")
            .status(IssueRunStatus.COMPLETED)
            .outcome(IssueRunOutcome.SUBMITTED)
            .result("Execution completed and verified")
            .createdAt(Instant.now())
            .completedAt(Instant.now())
            .build();
    when(issueRunService.getRun(RUN_ID)).thenReturn(executorSubmission);

    Issue issue =
        Issue.builder()
            .id(ISSUE_ID)
            .projectId(PROJECT_ID)
            .number(1L)
            .title("Feature")
            .status(IssueStatus.IN_REVIEW)
            .build();
    when(issueService.getIssue(ISSUE_ID)).thenReturn(issue);
    when(projectService.getProject(PROJECT_ID))
        .thenReturn(Project.builder().id(PROJECT_ID).title("Test Project").build());
    when(issueService.listActivitiesPage(ISSUE_ID, 0L, 51)).thenReturn(List.of());
    when(issueService.countRejections(ISSUE_ID)).thenReturn(1L);
    when(issueService.listDependencies(ISSUE_ID)).thenReturn(List.of());

    ToolExecutionRequest request = createRequest(tool.descriptor(), "{}", executionContext);
    TestListener listener = new TestListener();
    tool.execute(request, listener);

    assertFalse(listener.outcome.result().error());
    String text = ((TextResultContent) listener.outcome.result().contents().get(0)).text();
    assertTrue(text.contains("\"submission_run\""));
    assertTrue(text.contains("Execution completed and verified"));
    assertTrue(text.contains("\"rejection_count\" : 1"));
  }

  @Test
  void execute_issueRead_withPagination_passesSequencesAndLimit() {
    // 验证 issue_read 支持分页参数 activity_after_sequence 与 activity_limit 且正向返回分页结构
    ProjectRoleTool tool = createTool(ProjectRoleToolType.ISSUE_READ);
    when(ownerResolver.resolve(THREAD_ID)).thenReturn(Optional.of(executorOwner));

    IssueRun run =
        IssueRun.builder()
            .id(RUN_ID)
            .issueId(ISSUE_ID)
            .role(IssueRunRole.EXECUTOR)
            .agentName("coder-agent")
            .status(IssueRunStatus.RUNNING)
            .build();
    when(issueRunService.getRun(RUN_ID)).thenReturn(run);
    when(issueService.getIssue(ISSUE_ID))
        .thenReturn(
            Issue.builder()
                .id(ISSUE_ID)
                .projectId(PROJECT_ID)
                .status(IssueStatus.IN_PROGRESS)
                .build());
    when(projectService.getProject(PROJECT_ID))
        .thenReturn(Project.builder().id(PROJECT_ID).build());

    IssueActivity act10 =
        IssueActivity.builder()
            .issueId(ISSUE_ID)
            .sequence(11L)
            .kind(IssueActivityKind.COMMENT)
            .actorType(IssueActivityActorType.HUMAN)
            .body("Comment 11")
            .build();
    IssueActivity act11 =
        IssueActivity.builder()
            .issueId(ISSUE_ID)
            .sequence(12L)
            .kind(IssueActivityKind.COMMENT)
            .actorType(IssueActivityActorType.HUMAN)
            .body("Comment 12")
            .build();
    when(issueService.listActivitiesPage(ISSUE_ID, 10L, 3))
        .thenReturn(List.of(act10, act11)); // 请求 limit=2，pageSize+1=3
    when(issueService.countRejections(ISSUE_ID)).thenReturn(0L);
    when(issueService.listDependencies(ISSUE_ID)).thenReturn(List.of());

    String args = "{\"activity_after_sequence\":10,\"activity_limit\":2}";
    ToolExecutionRequest request = createRequest(tool.descriptor(), args, executionContext);
    TestListener listener = new TestListener();
    tool.execute(request, listener);

    assertFalse(listener.outcome.result().error());
    verify(issueService).listActivitiesPage(ISSUE_ID, 10L, 3);
    String text = ((TextResultContent) listener.outcome.result().contents().get(0)).text();
    assertTrue(text.contains("\"after_sequence\" : 10"));
    assertTrue(text.contains("\"next_after_sequence\" : 12"));
    assertTrue(text.contains("\"has_more\" : false"));
  }

  @Test
  void execute_issueRequestInput_success_updatesRunToWaitingHuman() {
    // 验证 issue_request_input 参数正确传递并将 Run 推进至 WAITING_HUMAN
    ProjectRoleTool tool = createTool(ProjectRoleToolType.ISSUE_REQUEST_INPUT);
    when(ownerResolver.resolve(THREAD_ID)).thenReturn(Optional.of(executorOwner));

    IssueRun activeRun =
        IssueRun.builder()
            .id(RUN_ID)
            .issueId(ISSUE_ID)
            .role(IssueRunRole.EXECUTOR)
            .agentName("coder-agent")
            .status(IssueRunStatus.RUNNING)
            .build();
    when(issueRunService.getRun(RUN_ID)).thenReturn(activeRun);

    IssueRun waitingRun =
        IssueRun.builder()
            .id(RUN_ID)
            .issueId(ISSUE_ID)
            .role(IssueRunRole.EXECUTOR)
            .agentName("coder-agent")
            .status(IssueRunStatus.WAITING_HUMAN)
            .waitingReason("Need API key for third-party service")
            .build();
    when(issueRunService.requestInput(
            RUN_ID, "Need API key for third-party service", "Running integration tests"))
        .thenReturn(waitingRun);

    String args =
        "{\"question\":\"Need API key for third-party service\","
            + "\"context\":\"Running integration tests\"}";
    ToolExecutionRequest request = createRequest(tool.descriptor(), args, executionContext);
    TestListener listener = new TestListener();
    tool.execute(request, listener);

    assertFalse(listener.outcome.result().error());
    verify(issueRunService)
        .requestInput(RUN_ID, "Need API key for third-party service", "Running integration tests");

    String text = ((TextResultContent) listener.outcome.result().contents().get(0)).text();
    assertTrue(text.contains("\"status\" : \"WAITING_HUMAN\""));
    assertTrue(text.contains("Need API key for third-party service"));
  }

  @Test
  void execute_issueReview_approve_success_bindsTerminalActionId() {
    // 验证 issue_review APPROVE 自动绑定 tool:{invocationId}，调用 reviewByAgent 并返回 run/issue
    ProjectRoleTool tool = createTool(ProjectRoleToolType.ISSUE_REVIEW);
    when(ownerResolver.resolve(THREAD_ID)).thenReturn(Optional.of(reviewerOwner));

    IssueRun reviewerRun =
        IssueRun.builder()
            .id(REVIEW_RUN_ID)
            .issueId(ISSUE_ID)
            .role(IssueRunRole.REVIEWER)
            .agentName("reviewer-agent")
            .submissionRunId(RUN_ID)
            .status(IssueRunStatus.RUNNING)
            .build();
    when(issueRunService.getRun(REVIEW_RUN_ID)).thenReturn(reviewerRun);

    IssueRun completedRun =
        IssueRun.builder()
            .id(REVIEW_RUN_ID)
            .issueId(ISSUE_ID)
            .role(IssueRunRole.REVIEWER)
            .agentName("reviewer-agent")
            .status(IssueRunStatus.COMPLETED)
            .outcome(IssueRunOutcome.APPROVED)
            .terminalActionId("tool:" + INVOCATION_ID)
            .build();
    when(issueRunService.reviewByAgent(
            REVIEW_RUN_ID,
            "reviewer-agent",
            "tool:" + INVOCATION_ID,
            ReviewDecision.APPROVE,
            "Looks good and tests pass"))
        .thenReturn(completedRun);

    Issue freshIssue =
        Issue.builder()
            .id(ISSUE_ID)
            .projectId(PROJECT_ID)
            .number(1L)
            .title("Feature")
            .status(IssueStatus.DONE)
            .build();
    when(issueService.getIssue(ISSUE_ID)).thenReturn(freshIssue);
    when(projectService.getProject(PROJECT_ID))
        .thenReturn(Project.builder().id(PROJECT_ID).maxReviewRejections(3).build());
    when(issueService.countRejections(ISSUE_ID)).thenReturn(0L);

    String args = "{\"decision\":\"APPROVE\",\"reason\":\"Looks good and tests pass\"}";
    ToolExecutionRequest request = createRequest(tool.descriptor(), args, executionContext);
    TestListener listener = new TestListener();
    tool.execute(request, listener);

    assertFalse(listener.outcome.result().error());
    verify(issueRunService)
        .reviewByAgent(
            REVIEW_RUN_ID,
            "reviewer-agent",
            "tool:" + INVOCATION_ID,
            ReviewDecision.APPROVE,
            "Looks good and tests pass");

    String text = ((TextResultContent) listener.outcome.result().contents().get(0)).text();
    assertTrue(text.contains("\"outcome\" : \"APPROVED\""));
    assertTrue(text.contains("\"status\" : \"DONE\""));
  }

  @Test
  void execute_issueReview_requestChanges_success() {
    // 验证 issue_review REQUEST_CHANGES 正向执行与参数传递
    ProjectRoleTool tool = createTool(ProjectRoleToolType.ISSUE_REVIEW);
    when(ownerResolver.resolve(THREAD_ID)).thenReturn(Optional.of(reviewerOwner));

    IssueRun reviewerRun =
        IssueRun.builder()
            .id(REVIEW_RUN_ID)
            .issueId(ISSUE_ID)
            .role(IssueRunRole.REVIEWER)
            .agentName("reviewer-agent")
            .submissionRunId(RUN_ID)
            .status(IssueRunStatus.RUNNING)
            .build();
    when(issueRunService.getRun(REVIEW_RUN_ID)).thenReturn(reviewerRun);

    IssueRun rejectedRun =
        IssueRun.builder()
            .id(REVIEW_RUN_ID)
            .issueId(ISSUE_ID)
            .role(IssueRunRole.REVIEWER)
            .agentName("reviewer-agent")
            .status(IssueRunStatus.COMPLETED)
            .outcome(IssueRunOutcome.CHANGES_REQUESTED)
            .terminalActionId("tool:" + INVOCATION_ID)
            .build();
    when(issueRunService.reviewByAgent(
            REVIEW_RUN_ID,
            "reviewer-agent",
            "tool:" + INVOCATION_ID,
            ReviewDecision.REQUEST_CHANGES,
            "Missing edge case tests"))
        .thenReturn(rejectedRun);

    Issue freshIssue =
        Issue.builder()
            .id(ISSUE_ID)
            .projectId(PROJECT_ID)
            .number(1L)
            .title("Feature")
            .status(IssueStatus.TODO)
            .build();
    when(issueService.getIssue(ISSUE_ID)).thenReturn(freshIssue);
    when(projectService.getProject(PROJECT_ID))
        .thenReturn(Project.builder().id(PROJECT_ID).maxReviewRejections(3).build());
    when(issueService.countRejections(ISSUE_ID)).thenReturn(1L);

    String args = "{\"decision\":\"REQUEST_CHANGES\",\"reason\":\"Missing edge case tests\"}";
    ToolExecutionRequest request = createRequest(tool.descriptor(), args, executionContext);
    TestListener listener = new TestListener();
    tool.execute(request, listener);

    assertFalse(listener.outcome.result().error());
    verify(issueRunService)
        .reviewByAgent(
            REVIEW_RUN_ID,
            "reviewer-agent",
            "tool:" + INVOCATION_ID,
            ReviewDecision.REQUEST_CHANGES,
            "Missing edge case tests");
  }

  // --- 4. 参数校验与业务规则拒绝 ---

  @Test
  void execute_issueRead_mismatchedIssueId_returnsError() {
    // 验证 issue_read 传入与当前上下文不同的 issue_id 时被明确拒绝且不发生跨 issue 读取
    ProjectRoleTool tool = createTool(ProjectRoleToolType.ISSUE_READ);
    when(ownerResolver.resolve(THREAD_ID)).thenReturn(Optional.of(executorOwner));

    IssueRun run =
        IssueRun.builder()
            .id(RUN_ID)
            .issueId(ISSUE_ID)
            .role(IssueRunRole.EXECUTOR)
            .agentName("coder-agent")
            .status(IssueRunStatus.RUNNING)
            .build();
    when(issueRunService.getRun(RUN_ID)).thenReturn(run);

    UUID mismatchedIssueId = UUID.fromString("00000000-0000-0000-0000-000000000999");
    String args = String.format("{\"issue_id\":\"%s\"}", mismatchedIssueId);
    ToolExecutionRequest request = createRequest(tool.descriptor(), args, executionContext);
    TestListener listener = new TestListener();
    tool.execute(request, listener);

    ToolResult result = listener.outcome.result();
    assertTrue(result.error());
    assertEquals(
        "issue_id must match the current issue context",
        ((TextResultContent) result.contents().get(0)).text());
  }

  @Test
  void execute_issueRead_negativeActivityAfterSequence_returnsError() {
    // 验证 activity_after_sequence 为负数时被拒绝
    ProjectRoleTool tool = createTool(ProjectRoleToolType.ISSUE_READ);
    when(ownerResolver.resolve(THREAD_ID)).thenReturn(Optional.of(executorOwner));

    IssueRun run =
        IssueRun.builder()
            .id(RUN_ID)
            .issueId(ISSUE_ID)
            .role(IssueRunRole.EXECUTOR)
            .agentName("coder-agent")
            .status(IssueRunStatus.RUNNING)
            .build();
    when(issueRunService.getRun(RUN_ID)).thenReturn(run);

    String args = "{\"activity_after_sequence\":-1}";
    ToolExecutionRequest request = createRequest(tool.descriptor(), args, executionContext);
    TestListener listener = new TestListener();
    tool.execute(request, listener);

    ToolResult result = listener.outcome.result();
    assertTrue(result.error());
    assertEquals(
        "activity_after_sequence must not be negative",
        ((TextResultContent) result.contents().get(0)).text());
  }

  @Test
  void execute_issueRead_activityLimitOutOfRange_returnsError() {
    // 验证 activity_limit 小于 1 或大于 200 时被明确拒绝
    ProjectRoleTool tool = createTool(ProjectRoleToolType.ISSUE_READ);
    when(ownerResolver.resolve(THREAD_ID)).thenReturn(Optional.of(executorOwner));

    IssueRun run =
        IssueRun.builder()
            .id(RUN_ID)
            .issueId(ISSUE_ID)
            .role(IssueRunRole.EXECUTOR)
            .agentName("coder-agent")
            .status(IssueRunStatus.RUNNING)
            .build();
    when(issueRunService.getRun(RUN_ID)).thenReturn(run);

    // limit = 0
    TestListener listenerZero = new TestListener();
    tool.execute(
        createRequest(tool.descriptor(), "{\"activity_limit\":0}", executionContext), listenerZero);
    assertTrue(listenerZero.outcome.result().error());
    assertEquals(
        "activity_limit must be between 1 and 200",
        ((TextResultContent) listenerZero.outcome.result().contents().get(0)).text());

    // limit = 201
    TestListener listenerExceeded = new TestListener();
    tool.execute(
        createRequest(tool.descriptor(), "{\"activity_limit\":201}", executionContext),
        listenerExceeded);
    assertTrue(listenerExceeded.outcome.result().error());
    assertEquals(
        "activity_limit must be between 1 and 200",
        ((TextResultContent) listenerExceeded.outcome.result().contents().get(0)).text());
  }

  @Test
  void execute_issueRequestInput_blankQuestion_returnsError() {
    // 验证 issue_request_input 提问正文为空白字符串时被拦截
    ProjectRoleTool tool = createTool(ProjectRoleToolType.ISSUE_REQUEST_INPUT);
    when(ownerResolver.resolve(THREAD_ID)).thenReturn(Optional.of(executorOwner));

    String args = "{\"question\":\"   \"}";
    ToolExecutionRequest request = createRequest(tool.descriptor(), args, executionContext);
    TestListener listener = new TestListener();
    tool.execute(request, listener);

    ToolResult result = listener.outcome.result();
    assertTrue(result.error());
    assertEquals(
        "question must not be blank", ((TextResultContent) result.contents().get(0)).text());
  }

  @Test
  void execute_issueReview_blankReason_returnsError() {
    // 验证 issue_review 审查理由为空白字符串时被拦截
    ProjectRoleTool tool = createTool(ProjectRoleToolType.ISSUE_REVIEW);
    when(ownerResolver.resolve(THREAD_ID)).thenReturn(Optional.of(reviewerOwner));

    String args = "{\"decision\":\"APPROVE\",\"reason\":\"   \"}";
    ToolExecutionRequest request = createRequest(tool.descriptor(), args, executionContext);
    TestListener listener = new TestListener();
    tool.execute(request, listener);

    ToolResult result = listener.outcome.result();
    assertTrue(result.error());
    assertEquals("reason must not be blank", ((TextResultContent) result.contents().get(0)).text());
  }

  @Test
  void execute_issueReview_invalidDecision_returnsError() {
    // 验证非法的审查决定枚举值被拦截且错误信息明确
    ProjectRoleTool tool = createTool(ProjectRoleToolType.ISSUE_REVIEW);
    when(ownerResolver.resolve(THREAD_ID)).thenReturn(Optional.of(reviewerOwner));

    ToolCall call = mock(ToolCall.class);
    when(call.id()).thenReturn(CALL_ID);
    when(call.argumentsJson()).thenReturn("{\"decision\":\"INVALID\",\"reason\":\"ok\"}");

    ToolExecutionRequest request = mock(ToolExecutionRequest.class);
    when(request.context()).thenReturn(executionContext);
    when(request.call()).thenReturn(call);

    TestListener listener = new TestListener();
    tool.execute(request, listener);

    ToolResult result = listener.outcome.result();
    assertTrue(result.error());
    assertEquals(
        "Invalid decision: only APPROVE or REQUEST_CHANGES is allowed",
        ((TextResultContent) result.contents().get(0)).text());
  }

  @Test
  void execute_issueReview_reviewerRunMissingSubmission_returnsError() {
    // 验证 Reviewer Run 未绑定被审查提交 Run 时拒绝执行审查动作
    ProjectRoleTool tool = createTool(ProjectRoleToolType.ISSUE_REVIEW);
    when(ownerResolver.resolve(THREAD_ID)).thenReturn(Optional.of(reviewerOwner));

    IssueRun unboundReviewerRun =
        IssueRun.builder()
            .id(REVIEW_RUN_ID)
            .issueId(ISSUE_ID)
            .role(IssueRunRole.REVIEWER)
            .agentName("reviewer-agent")
            .submissionRunId(null) // 未绑定提交
            .status(IssueRunStatus.RUNNING)
            .build();
    when(issueRunService.getRun(REVIEW_RUN_ID)).thenReturn(unboundReviewerRun);

    String args = "{\"decision\":\"APPROVE\",\"reason\":\"Looks good\"}";
    ToolExecutionRequest request = createRequest(tool.descriptor(), args, executionContext);
    TestListener listener = new TestListener();
    tool.execute(request, listener);

    ToolResult result = listener.outcome.result();
    assertTrue(result.error());
    assertEquals(
        "issue_review requires the current REVIEWER run bound to a submission",
        ((TextResultContent) result.contents().get(0)).text());
  }

  @Test
  void execute_malformedJsonArguments_returnsError() {
    // 验证畸形 JSON 参数被拦截并返回 Invalid JSON arguments
    ProjectRoleTool tool = createTool(ProjectRoleToolType.ISSUE_READ);
    when(ownerResolver.resolve(THREAD_ID)).thenReturn(Optional.of(executorOwner));

    ToolCall call = mock(ToolCall.class);
    when(call.id()).thenReturn(CALL_ID);
    when(call.argumentsJson()).thenReturn("{invalid-json-body");

    ToolExecutionRequest request = mock(ToolExecutionRequest.class);
    when(request.context()).thenReturn(executionContext);
    when(request.call()).thenReturn(call);

    TestListener listener = new TestListener();
    tool.execute(request, listener);

    ToolResult result = listener.outcome.result();
    assertTrue(result.error());
    assertEquals("Invalid JSON arguments", ((TextResultContent) result.contents().get(0)).text());
  }

  @Test
  void execute_invalidUuidFormat_doesNotEchoRawValue() {
    // 验证传入非法 UUID 格式时返回通用错误且绝不回显输入内容
    ProjectRoleTool tool = createTool(ProjectRoleToolType.ISSUE_READ);
    when(ownerResolver.resolve(THREAD_ID)).thenReturn(Optional.of(executorOwner));

    String maliciousInput = "malicious-injection-string";
    String args = String.format("{\"issue_id\":\"%s\"}", maliciousInput);
    ToolExecutionRequest request = createRequest(tool.descriptor(), args, executionContext);
    TestListener listener = new TestListener();
    tool.execute(request, listener);

    ToolResult result = listener.outcome.result();
    assertTrue(result.error());
    String errorMsg = ((TextResultContent) result.contents().get(0)).text();
    assertEquals("Invalid issue_id format", errorMsg);
    assertFalse(errorMsg.contains(maliciousInput));
  }

  // --- 5. 安全脱敏、一致性与异常映射测试 ---

  @Test
  void execute_aiResourceNotFoundException_sanitized() {
    // 验证 AiResourceNotFoundException 转换为脱敏的 <resource> not found
    ProjectRoleTool tool = createTool(ProjectRoleToolType.ISSUE_READ);
    when(ownerResolver.resolve(THREAD_ID)).thenReturn(Optional.of(executorOwner));

    IssueRun run =
        IssueRun.builder()
            .id(RUN_ID)
            .issueId(ISSUE_ID)
            .role(IssueRunRole.EXECUTOR)
            .agentName("coder-agent")
            .status(IssueRunStatus.RUNNING)
            .build();
    when(issueRunService.getRun(RUN_ID)).thenReturn(run);
    when(issueService.getIssue(ISSUE_ID)).thenThrow(new AiResourceNotFoundException("issue"));

    ToolExecutionRequest request = createRequest(tool.descriptor(), "{}", executionContext);
    TestListener listener = new TestListener();
    tool.execute(request, listener);

    ToolResult result = listener.outcome.result();
    assertTrue(result.error());
    String errorMsg = ((TextResultContent) result.contents().get(0)).text();
    assertEquals("issue not found", errorMsg);
    assertFalse(errorMsg.contains(ISSUE_ID.toString()));
  }

  @Test
  void execute_aiVersionConflictException_sanitized() {
    // 验证 AiVersionConflictException 转换为脱敏的 version conflict 消息
    ProjectRoleTool tool = createTool(ProjectRoleToolType.ISSUE_READ);
    when(ownerResolver.resolve(THREAD_ID)).thenReturn(Optional.of(executorOwner));

    IssueRun run =
        IssueRun.builder()
            .id(RUN_ID)
            .issueId(ISSUE_ID)
            .role(IssueRunRole.EXECUTOR)
            .agentName("coder-agent")
            .status(IssueRunStatus.RUNNING)
            .build();
    when(issueRunService.getRun(RUN_ID)).thenReturn(run);
    when(issueService.getIssue(ISSUE_ID))
        .thenThrow(new AiVersionConflictException("issue", "1", "2"));

    ToolExecutionRequest request = createRequest(tool.descriptor(), "{}", executionContext);
    TestListener listener = new TestListener();
    tool.execute(request, listener);

    ToolResult result = listener.outcome.result();
    assertTrue(result.error());
    assertEquals(
        "issue version conflict: expected=1 actual=2",
        ((TextResultContent) result.contents().get(0)).text());
  }

  @Test
  void execute_aiValidationException_sanitized() {
    // 验证 AiValidationException 返回脱敏消息
    ProjectRoleTool tool = createTool(ProjectRoleToolType.ISSUE_READ);
    when(ownerResolver.resolve(THREAD_ID)).thenReturn(Optional.of(executorOwner));

    IssueRun run =
        IssueRun.builder()
            .id(RUN_ID)
            .issueId(ISSUE_ID)
            .role(IssueRunRole.EXECUTOR)
            .agentName("coder-agent")
            .status(IssueRunStatus.RUNNING)
            .build();
    when(issueRunService.getRun(RUN_ID)).thenReturn(run);
    when(issueService.getIssue(ISSUE_ID))
        .thenThrow(new AiValidationException("issue", "Validation failed for issue status"));

    ToolExecutionRequest request = createRequest(tool.descriptor(), "{}", executionContext);
    TestListener listener = new TestListener();
    tool.execute(request, listener);

    ToolResult result = listener.outcome.result();
    assertTrue(result.error());
    assertEquals(
        "Validation failed for issue status",
        ((TextResultContent) result.contents().get(0)).text());
  }

  @Test
  void execute_inconsistentOwnership_returnsInconsistentError() {
    // 验证数据所有权不一致时返回 Project thread ownership is inconsistent
    ProjectRoleTool tool = createTool(ProjectRoleToolType.ISSUE_READ);
    when(ownerResolver.resolve(THREAD_ID)).thenReturn(Optional.of(executorOwner));

    // Run 的 issueId 与 owner.issueId 不一致
    UUID otherIssueId = UUID.fromString("00000000-0000-0000-0000-000000000999");
    IssueRun mismatchedRun =
        IssueRun.builder()
            .id(RUN_ID)
            .issueId(otherIssueId)
            .role(IssueRunRole.EXECUTOR)
            .agentName("coder-agent")
            .status(IssueRunStatus.RUNNING)
            .build();
    when(issueRunService.getRun(RUN_ID)).thenReturn(mismatchedRun);

    ToolExecutionRequest request = createRequest(tool.descriptor(), "{}", executionContext);
    TestListener listener = new TestListener();
    tool.execute(request, listener);

    ToolResult result = listener.outcome.result();
    assertTrue(result.error());
    assertEquals(
        "Project thread ownership is inconsistent",
        ((TextResultContent) result.contents().get(0)).text());
  }

  @Test
  void execute_dependencyIssueBelongsToForeignProject_returnsInconsistentOwnershipError() {
    // 验证畸形跨项目 dependency 关系被一致性检查阻断，目标标题绝不泄漏
    ProjectRoleTool tool = createTool(ProjectRoleToolType.ISSUE_READ);
    when(ownerResolver.resolve(THREAD_ID)).thenReturn(Optional.of(executorOwner));

    IssueRun run =
        IssueRun.builder()
            .id(RUN_ID)
            .issueId(ISSUE_ID)
            .role(IssueRunRole.EXECUTOR)
            .agentName("coder-agent")
            .status(IssueRunStatus.RUNNING)
            .build();
    when(issueRunService.getRun(RUN_ID)).thenReturn(run);
    when(issueService.getIssue(ISSUE_ID))
        .thenReturn(
            Issue.builder().id(ISSUE_ID).projectId(PROJECT_ID).status(IssueStatus.TODO).build());
    when(projectService.getProject(PROJECT_ID))
        .thenReturn(Project.builder().id(PROJECT_ID).build());

    UUID foreignProject = UUID.fromString("00000000-0000-0000-0000-000000000999");
    when(issueService.getIssue(DEP_ISSUE_ID))
        .thenReturn(
            Issue.builder()
                .id(DEP_ISSUE_ID)
                .projectId(foreignProject)
                .number(9L)
                .title("FOREIGN_SECRET_TITLE")
                .build());
    when(issueService.listDependencies(ISSUE_ID))
        .thenReturn(
            List.of(
                IssueDependency.builder()
                    .issueId(ISSUE_ID)
                    .dependsOnIssueId(DEP_ISSUE_ID)
                    .projectId(PROJECT_ID)
                    .build()));

    TestListener listener = new TestListener();
    tool.execute(createRequest(tool.descriptor(), "{}", executionContext), listener);

    ToolResult result = listener.outcome.result();
    assertTrue(result.error());
    String text = ((TextResultContent) result.contents().get(0)).text();
    assertEquals("Project thread ownership is inconsistent", text);
    assertFalse(text.contains("FOREIGN_SECRET_TITLE"));
  }

  @Test
  void execute_targetDependencyNotFound_returnsInconsistentOwnershipError() {
    // 验证依赖目标 Issue 不存在时返回一致性错误
    ProjectRoleTool tool = createTool(ProjectRoleToolType.ISSUE_READ);
    when(ownerResolver.resolve(THREAD_ID)).thenReturn(Optional.of(executorOwner));

    IssueRun run =
        IssueRun.builder()
            .id(RUN_ID)
            .issueId(ISSUE_ID)
            .role(IssueRunRole.EXECUTOR)
            .agentName("coder-agent")
            .status(IssueRunStatus.RUNNING)
            .build();
    when(issueRunService.getRun(RUN_ID)).thenReturn(run);
    when(issueService.getIssue(ISSUE_ID))
        .thenReturn(
            Issue.builder().id(ISSUE_ID).projectId(PROJECT_ID).status(IssueStatus.TODO).build());
    when(projectService.getProject(PROJECT_ID))
        .thenReturn(Project.builder().id(PROJECT_ID).build());

    when(issueService.getIssue(DEP_ISSUE_ID)).thenReturn(null);
    when(issueService.listDependencies(ISSUE_ID))
        .thenReturn(
            List.of(
                IssueDependency.builder()
                    .issueId(ISSUE_ID)
                    .dependsOnIssueId(DEP_ISSUE_ID)
                    .projectId(PROJECT_ID)
                    .build()));

    TestListener listener = new TestListener();
    tool.execute(createRequest(tool.descriptor(), "{}", executionContext), listener);

    ToolResult result = listener.outcome.result();
    assertTrue(result.error());
    assertEquals(
        "Project thread ownership is inconsistent",
        ((TextResultContent) result.contents().get(0)).text());
  }

  @Test
  void execute_unexpectedException_callsOnErrorWithoutCause() {
    // 验证遇到意外运行时异常时调用 listener.onError 且异常不包含原 Throwable cause
    ProjectRoleTool tool = createTool(ProjectRoleToolType.ISSUE_READ);
    when(ownerResolver.resolve(THREAD_ID)).thenReturn(Optional.of(executorOwner));
    when(issueRunService.getRun(RUN_ID))
        .thenThrow(new NullPointerException("Internal NPE with secrets"));

    ToolExecutionRequest request = createRequest(tool.descriptor(), "{}", executionContext);
    TestListener listener = new TestListener();
    tool.execute(request, listener);

    assertNull(listener.outcome);
    assertNotNull(listener.error);
    assertTrue(listener.error instanceof IllegalStateException);
    assertEquals("Project role tool execution failed", listener.error.getMessage());
    assertNull(listener.error.getCause(), "Throwable cause must be null to prevent leakage");
  }

  @Test
  void execute_internalStateError_returnsGenericMessageWithoutIdentifier() {
    // 验证底层非所有权异常即使含标识也不会由工具结果回显
    ProjectRoleTool tool = createTool(ProjectRoleToolType.ISSUE_READ);
    when(ownerResolver.resolve(THREAD_ID)).thenReturn(Optional.of(executorOwner));
    when(issueRunService.getRun(RUN_ID))
        .thenThrow(new IllegalStateException("Failed for " + PROJECT_ID));

    TestListener listener = new TestListener();
    tool.execute(createRequest(tool.descriptor(), "{}", executionContext), listener);

    String message = ((TextResultContent) listener.outcome.result().contents().get(0)).text();
    assertTrue(listener.outcome.result().error());
    assertEquals("Project role tool execution failed", message);
    assertFalse(message.contains(PROJECT_ID.toString()));
  }

  @Test
  void execute_listenerCallback_isStrictlyMutuallyExclusive() {
    // 验证正常执行与异常执行时，回调均仅恰好触发一次
    ProjectRoleTool tool = createTool(ProjectRoleToolType.ISSUE_READ);
    when(ownerResolver.resolve(THREAD_ID)).thenReturn(Optional.of(executorOwner));

    IssueRun run =
        IssueRun.builder()
            .id(RUN_ID)
            .issueId(ISSUE_ID)
            .role(IssueRunRole.EXECUTOR)
            .agentName("coder-agent")
            .status(IssueRunStatus.RUNNING)
            .build();
    when(issueRunService.getRun(RUN_ID)).thenReturn(run);
    when(issueService.getIssue(ISSUE_ID))
        .thenReturn(
            Issue.builder().id(ISSUE_ID).projectId(PROJECT_ID).status(IssueStatus.TODO).build());
    when(projectService.getProject(PROJECT_ID))
        .thenReturn(Project.builder().id(PROJECT_ID).build());
    when(issueService.listActivitiesPage(ISSUE_ID, 0L, 51)).thenReturn(List.of());
    when(issueService.countRejections(ISSUE_ID)).thenReturn(0L);
    when(issueService.listDependencies(ISSUE_ID)).thenReturn(List.of());

    ToolExecutionRequest request = createRequest(tool.descriptor(), "{}", executionContext);
    TestListener listener = new TestListener();
    tool.execute(request, listener);

    assertEquals(1, listener.count);
    assertNotNull(listener.outcome);
    assertNull(listener.error);
  }

  // --- 6. 工具元数据、描述符与渲染器契约 ---

  @Test
  void tool_metadata_typeDescriptorAndHistoryRenderer() {
    // 验证 Tool.type()、descriptor() 与 historyRenderer() 完整可用
    for (ProjectRoleToolType type : ProjectRoleToolType.values()) {
      ProjectRoleTool tool = createTool(type);
      assertEquals(type, tool.type());
      assertEquals(type.descriptor(), tool.descriptor());
      assertTrue(tool.historyRenderer().isPresent());
    }
  }

  @Test
  void toolType_forRoleAndNamesForRole() {
    // 验证 ProjectRoleToolType 辅助方法
    assertEquals(
        List.of(ProjectRoleToolType.ISSUE_READ, ProjectRoleToolType.ISSUE_REQUEST_INPUT),
        ProjectRoleToolType.forRole(ProjectRole.EXECUTOR));
    assertEquals(
        List.of(
            ProjectRoleToolType.ISSUE_READ,
            ProjectRoleToolType.ISSUE_REQUEST_INPUT,
            ProjectRoleToolType.ISSUE_REVIEW),
        ProjectRoleToolType.forRole(ProjectRole.REVIEWER));

    assertEquals(
        List.of("issue_read", "issue_request_input"),
        ProjectRoleToolType.namesForRole(ProjectRole.EXECUTOR));
    assertEquals(
        List.of("issue_read", "issue_request_input", "issue_review"),
        ProjectRoleToolType.namesForRole(ProjectRole.REVIEWER));

    assertEquals(
        Optional.of(ProjectRoleToolType.ISSUE_READ),
        ProjectRoleToolType.findByModelName("issue_read"));
    assertEquals(Optional.empty(), ProjectRoleToolType.findByModelName(null));
    assertEquals(Optional.empty(), ProjectRoleToolType.findByModelName("non_existent"));
  }

  private void assertSchemaRejected(ProjectRoleToolType type, String argumentsJson) {
    ToolDescriptor descriptor = type.descriptor();
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ToolExecutionRequest(
                descriptor,
                new ToolCall(CALL_ID, descriptor.name(), argumentsJson),
                Duration.ofSeconds(30)));
  }

  private String minimumValidArguments(ProjectRoleToolType type) {
    return switch (type) {
      case ISSUE_READ -> "{}";
      case ISSUE_REQUEST_INPUT -> "{\"question\":\"help\"}";
      case ISSUE_REVIEW -> "{\"decision\":\"APPROVE\",\"reason\":\"ok\"}";
    };
  }
}
