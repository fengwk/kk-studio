package fun.fengwk.kkstudio.platform.project.tool;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
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
import fun.fengwk.kkstudio.platform.error.AiVersionConflictException;
import fun.fengwk.kkstudio.platform.project.model.Issue;
import fun.fengwk.kkstudio.platform.project.model.IssueDependency;
import fun.fengwk.kkstudio.platform.project.model.IssueInput;
import fun.fengwk.kkstudio.platform.project.model.IssueInputKind;
import fun.fengwk.kkstudio.platform.project.model.IssueRun;
import fun.fengwk.kkstudio.platform.project.model.IssueRunActorType;
import fun.fengwk.kkstudio.platform.project.model.IssueRunOutcome;
import fun.fengwk.kkstudio.platform.project.model.IssueRunRole;
import fun.fengwk.kkstudio.platform.project.model.IssueRunStatus;
import fun.fengwk.kkstudio.platform.project.model.IssueStatus;
import fun.fengwk.kkstudio.platform.project.model.Project;
import fun.fengwk.kkstudio.platform.project.model.ReviewDecision;
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
 *   <li>验证 12 个真实工具的 JSON Schema 严格性：未知字段拦截、必填字段检查与枚举校验；
 *   <li>验证 Coordinator(9)、Executor(2)、Reviewer(1) 全部 12 个工具正向 dispatch 链路与参数传递；
 *   <li>验证 submit、request_input、review 工具的 terminalActionId 必须由 tool:{invocationId} 生成；
 *   <li>验证权限与范围控制：无属主、角色不匹配、缺失上下文或跨项目访问均被严格拒绝；
 *   <li>验证安全脱敏：非法 UUID 与 Enum 原值不回显，业务异常去标识，未捕获异常不保留内部 cause；
 *   <li>验证 ToolExecutionListener 的回调严格互斥且恰好触发一次。
 * </ul>
 */
class ProjectRoleToolTest {

  private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000010");
  private static final UUID ISSUE_ID = UUID.fromString("00000000-0000-0000-0000-000000000020");
  private static final UUID DEP_ISSUE_ID = UUID.fromString("00000000-0000-0000-0000-000000000030");
  private static final UUID RUN_ID = UUID.fromString("00000000-0000-0000-0000-000000000040");
  private static final UUID THREAD_ID = UUID.fromString("00000000-0000-0000-0000-000000000050");
  private static final UUID INVOCATION_ID = UUID.fromString("00000000-0000-0000-0000-000000000060");
  private static final String CALL_ID = "call-123";

  private ProjectService projectService;
  private IssueService issueService;
  private IssueRunService issueRunService;
  private ProjectRoleToolService toolService;
  private ProjectThreadOwnerResolver ownerResolver;

  private ProjectThreadOwnerContext coordinatorOwner;
  private ProjectThreadOwnerContext executorOwner;
  private ProjectThreadOwnerContext reviewerOwner;
  private ToolExecutionContext executionContext;

  @BeforeEach
  void setUp() {
    projectService = mock(ProjectService.class);
    issueService = mock(IssueService.class);
    issueRunService = mock(IssueRunService.class);
    ownerResolver = mock(ProjectThreadOwnerResolver.class);

    toolService = new ProjectRoleToolService(projectService, issueService, issueRunService);

    coordinatorOwner =
        new ProjectThreadOwnerContext(
            ProjectRole.COORDINATOR, PROJECT_ID, null, null, "coordinator-agent");
    executorOwner =
        new ProjectThreadOwnerContext(
            ProjectRole.EXECUTOR, PROJECT_ID, ISSUE_ID, RUN_ID, "coder-agent");
    reviewerOwner =
        new ProjectThreadOwnerContext(
            ProjectRole.REVIEWER, PROJECT_ID, ISSUE_ID, RUN_ID, "reviewer-agent");

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
  void schemaValidation_rejectsUnknownFieldsAcrossAll12Tools() {
    // 验证所有工具都从各自最小合法参数出发，仅因 additionalProperties=false 拒绝未知字段
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
  void schemaValidation_requiresCursorsAndRejectsTypesAndEnums() {
    // 验证三个 Run 工具均强制显式回传双 cursor，且 schema 在执行前拒绝错误类型和枚举
    assertSchemaRejected(
        ProjectRoleToolType.ISSUE_SUBMIT, "{\"observed_input_sequence\":0,\"summary\":\"done\"}");
    assertSchemaRejected(
        ProjectRoleToolType.ISSUE_SUBMIT, "{\"observed_spec_revision\":0,\"summary\":\"done\"}");
    assertSchemaRejected(
        ProjectRoleToolType.ISSUE_REQUEST_INPUT,
        "{\"observed_input_sequence\":0,\"question\":\"help\"}");
    assertSchemaRejected(
        ProjectRoleToolType.ISSUE_REQUEST_INPUT,
        "{\"observed_spec_revision\":0,\"question\":\"help\"}");
    assertSchemaRejected(
        ProjectRoleToolType.ISSUE_REVIEW,
        "{\"observed_input_sequence\":0,\"decision\":\"APPROVE\",\"summary\":\"ok\"}");
    assertSchemaRejected(
        ProjectRoleToolType.ISSUE_REVIEW,
        "{\"observed_spec_revision\":0,\"decision\":\"APPROVE\",\"summary\":\"ok\"}");
    assertSchemaRejected(ProjectRoleToolType.ISSUE_READ, "{\"issue_id\":123}");
    assertSchemaRejected(
        ProjectRoleToolType.ISSUE_REVIEW,
        "{\"observed_spec_revision\":0,\"observed_input_sequence\":0,\"decision\":\"DENY\",\"summary\":\"ok\"}");
  }

  // --- 2. Coordinator 9 项工具正向测试 ---

  @Test
  void execute_projectRead_success() {
    // 验证 project_read 能够正确投影项目及排序后的 issues
    ProjectRoleTool tool = createTool(ProjectRoleToolType.PROJECT_READ);
    when(ownerResolver.resolve(THREAD_ID)).thenReturn(Optional.of(coordinatorOwner));

    Project project =
        Project.builder()
            .id(PROJECT_ID)
            .title("Test Project")
            .description("Desc")
            .coordinatorAgentName("coordinator-agent")
            .nextIssueNumber(2)
            .version(1L)
            .build();
    when(projectService.getProject(PROJECT_ID)).thenReturn(project);

    Issue issue =
        Issue.builder()
            .id(ISSUE_ID)
            .projectId(PROJECT_ID)
            .number(1L)
            .title("Task")
            .status(IssueStatus.TODO)
            .version(0L)
            .specRevision(1L)
            .inputSequence(0L)
            .build();
    when(issueService.listIssues(PROJECT_ID, false)).thenReturn(List.of(issue));
    when(issueService.isBlocked(ISSUE_ID)).thenReturn(false);

    ToolExecutionRequest request = createRequest(tool.descriptor(), "{}", executionContext);
    TestListener listener = new TestListener();
    tool.execute(request, listener);

    assertNotNull(listener.outcome);
    ToolResult result = listener.outcome.result();
    assertFalse(result.error());
    assertEquals(CALL_ID, result.toolCallId());
    String text = ((TextResultContent) result.contents().get(0)).text();
    assertTrue(text.contains("Test Project"));
    assertTrue(text.contains("Task"));
  }

  @Test
  void execute_issueRead_successWithDependenciesInputsRuns() {
    // 验证 issue_read 能够读取 issue 详情、依赖（包含 target 详情）、输入及执行记录，且过滤掉敏感字段
    ProjectRoleTool tool = createTool(ProjectRoleToolType.ISSUE_READ);
    when(ownerResolver.resolve(THREAD_ID)).thenReturn(Optional.of(coordinatorOwner));

    Issue issue =
        Issue.builder()
            .id(ISSUE_ID)
            .projectId(PROJECT_ID)
            .number(1L)
            .title("Issue 1")
            .description("Body")
            .status(IssueStatus.IN_PROGRESS)
            .version(2L)
            .specRevision(1L)
            .inputSequence(1L)
            .build();
    when(issueService.getIssue(ISSUE_ID)).thenReturn(issue);
    when(issueService.isBlocked(ISSUE_ID)).thenReturn(false);

    Issue targetDep =
        Issue.builder()
            .id(DEP_ISSUE_ID)
            .projectId(PROJECT_ID)
            .number(99L)
            .title("Dep Issue")
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

    IssueInput input =
        IssueInput.builder()
            .issueId(ISSUE_ID)
            .sequence(1L)
            .kind(IssueInputKind.HUMAN)
            .body("User prompt")
            .idempotencyKey("secret-idempotency-key")
            .createdAt(Instant.now())
            .build();
    when(issueService.listInputs(ISSUE_ID)).thenReturn(List.of(input));

    IssueRun run =
        IssueRun.builder()
            .id(RUN_ID)
            .issueId(ISSUE_ID)
            .ordinal(1L)
            .role(IssueRunRole.EXECUTOR)
            .actorType(IssueRunActorType.AGENT)
            .agentName("coder-agent")
            .status(IssueRunStatus.RUNNING)
            .observedSpecRevision(1L)
            .observedInputSequence(1L)
            .terminalActionId("tool:secret-terminal-action-id")
            .build();
    when(issueRunService.listRuns(ISSUE_ID)).thenReturn(List.of(run));

    String args = String.format("{\"issue_id\":\"%s\"}", ISSUE_ID);
    ToolExecutionRequest request = createRequest(tool.descriptor(), args, executionContext);
    TestListener listener = new TestListener();
    tool.execute(request, listener);

    ToolResult result = listener.outcome.result();
    assertFalse(result.error());
    String text = ((TextResultContent) result.contents().get(0)).text();

    assertTrue(text.contains("Issue 1"));
    assertTrue(text.contains("Dep Issue"));
    assertTrue(text.contains("User prompt"));
    assertFalse(text.contains("secret-idempotency-key"), "idempotencyKey must not be exposed");
    assertFalse(text.contains("secret-terminal-action-id"), "terminalActionId must not be exposed");
  }

  @Test
  void execute_issueList_success() {
    // 验证 issue_list 支持按 status 过滤
    ProjectRoleTool tool = createTool(ProjectRoleToolType.ISSUE_LIST);
    when(ownerResolver.resolve(THREAD_ID)).thenReturn(Optional.of(coordinatorOwner));

    Issue issue1 =
        Issue.builder()
            .id(ISSUE_ID)
            .projectId(PROJECT_ID)
            .number(1L)
            .status(IssueStatus.TODO)
            .build();
    Issue issue2 =
        Issue.builder()
            .id(DEP_ISSUE_ID)
            .projectId(PROJECT_ID)
            .number(2L)
            .status(IssueStatus.BACKLOG)
            .build();
    when(issueService.listIssues(PROJECT_ID, false)).thenReturn(List.of(issue1, issue2));

    ToolExecutionRequest request =
        createRequest(tool.descriptor(), "{\"status\":\"TODO\"}", executionContext);
    TestListener listener = new TestListener();
    tool.execute(request, listener);

    String text = ((TextResultContent) listener.outcome.result().contents().get(0)).text();
    assertTrue(text.contains(ISSUE_ID.toString()));
    assertFalse(text.contains(DEP_ISSUE_ID.toString()));
  }

  @Test
  void execute_issueCreate_success() {
    // 验证 issue_create 参数解析与创建成功
    ProjectRoleTool tool = createTool(ProjectRoleToolType.ISSUE_CREATE);
    when(ownerResolver.resolve(THREAD_ID)).thenReturn(Optional.of(coordinatorOwner));

    Issue created =
        Issue.builder()
            .id(ISSUE_ID)
            .projectId(PROJECT_ID)
            .number(1L)
            .title("New Issue")
            .description("Body")
            .status(IssueStatus.TODO)
            .assigneeAgentName("coder")
            .reviewerAgentName("reviewer")
            .version(0L)
            .specRevision(1L)
            .inputSequence(0L)
            .build();
    when(issueService.createIssue(
            eq(PROJECT_ID),
            eq("New Issue"),
            eq("Body"),
            eq("coder"),
            eq("reviewer"),
            eq(IssueStatus.TODO)))
        .thenReturn(created);

    String args =
        "{\"title\":\"New Issue\",\"description\":\"Body\",\"assignee_agent_name\":\"coder\",\"reviewer_agent_name\":\"reviewer\",\"initial_status\":\"TODO\"}";
    ToolExecutionRequest request = createRequest(tool.descriptor(), args, executionContext);
    TestListener listener = new TestListener();
    tool.execute(request, listener);

    assertFalse(listener.outcome.result().error());
    verify(issueService)
        .createIssue(PROJECT_ID, "New Issue", "Body", "coder", "reviewer", IssueStatus.TODO);
  }

  @Test
  void execute_issueUpdate_partialAndClearAssignee() {
    // 验证 issue_update 支持 partial update 以及使用空字符串清空 assignee
    ProjectRoleTool tool = createTool(ProjectRoleToolType.ISSUE_UPDATE);
    when(ownerResolver.resolve(THREAD_ID)).thenReturn(Optional.of(coordinatorOwner));

    Issue existing =
        Issue.builder()
            .id(ISSUE_ID)
            .projectId(PROJECT_ID)
            .number(1L)
            .title("Old Title")
            .description("Old Desc")
            .assigneeAgentName("old-coder")
            .reviewerAgentName("old-reviewer")
            .status(IssueStatus.TODO)
            .version(1L)
            .build();
    when(issueService.getIssue(ISSUE_ID)).thenReturn(existing);

    Issue updated =
        Issue.builder()
            .id(ISSUE_ID)
            .projectId(PROJECT_ID)
            .number(1L)
            .title("Old Title")
            .description("Old Desc")
            .assigneeAgentName(null) // 已清空
            .reviewerAgentName("old-reviewer")
            .status(IssueStatus.TODO)
            .version(2L)
            .build();
    when(issueService.updateIssue(
            eq(ISSUE_ID), eq(1L), eq("Old Title"), eq("Old Desc"), eq(null), eq("old-reviewer")))
        .thenReturn(updated);

    String args =
        String.format(
            "{\"issue_id\":\"%s\",\"expected_version\":1,\"assignee_agent_name\":\"\"}", ISSUE_ID);
    ToolExecutionRequest request = createRequest(tool.descriptor(), args, executionContext);
    TestListener listener = new TestListener();
    tool.execute(request, listener);

    assertFalse(listener.outcome.result().error());
    verify(issueService).updateIssue(ISSUE_ID, 1L, "Old Title", "Old Desc", null, "old-reviewer");
  }

  @Test
  void execute_issueUpdate_noMutableFields_fails() {
    // 验证 issue_update 必须提供至少一个更新字段
    ProjectRoleTool tool = createTool(ProjectRoleToolType.ISSUE_UPDATE);
    when(ownerResolver.resolve(THREAD_ID)).thenReturn(Optional.of(coordinatorOwner));

    String args = String.format("{\"issue_id\":\"%s\",\"expected_version\":1}", ISSUE_ID);
    ToolExecutionRequest request = createRequest(tool.descriptor(), args, executionContext);
    TestListener listener = new TestListener();
    tool.execute(request, listener);

    assertTrue(listener.outcome.result().error());
    String text = ((TextResultContent) listener.outcome.result().contents().get(0)).text();
    assertTrue(text.contains("At least one mutable field must be provided"));
  }

  @Test
  void execute_issueUpdate_titleAndReviewer_success() {
    // 验证 issue_update 正常更新 title 与 reviewer
    ProjectRoleTool tool = createTool(ProjectRoleToolType.ISSUE_UPDATE);
    when(ownerResolver.resolve(THREAD_ID)).thenReturn(Optional.of(coordinatorOwner));

    Issue existing =
        Issue.builder()
            .id(ISSUE_ID)
            .projectId(PROJECT_ID)
            .number(1L)
            .title("Old Title")
            .description("Old Desc")
            .assigneeAgentName("coder")
            .reviewerAgentName("old-reviewer")
            .status(IssueStatus.TODO)
            .version(1L)
            .build();
    when(issueService.getIssue(ISSUE_ID)).thenReturn(existing);

    Issue updated =
        Issue.builder()
            .id(ISSUE_ID)
            .projectId(PROJECT_ID)
            .number(1L)
            .title("New Title")
            .description("New Desc")
            .assigneeAgentName("coder")
            .reviewerAgentName(null)
            .status(IssueStatus.TODO)
            .version(2L)
            .build();
    when(issueService.updateIssue(
            eq(ISSUE_ID), eq(1L), eq("New Title"), eq("New Desc"), eq("coder"), eq(null)))
        .thenReturn(updated);

    String args =
        String.format(
            "{\"issue_id\":\"%s\",\"expected_version\":1,\"title\":\"New Title\",\"description\":\"New Desc\",\"reviewer_agent_name\":\"\"}",
            ISSUE_ID);
    ToolExecutionRequest request = createRequest(tool.descriptor(), args, executionContext);
    TestListener listener = new TestListener();
    tool.execute(request, listener);

    assertFalse(listener.outcome.result().error());
    verify(issueService).updateIssue(ISSUE_ID, 1L, "New Title", "New Desc", "coder", null);
  }

  @Test
  void execute_issueRead_whenTargetDepNotFound_returnsInconsistentOwnershipError() {
    // 验证 issue_read 依赖项的目标 issue 不存在时返回脱敏的数据所有权不一致错误
    ProjectRoleTool tool = createTool(ProjectRoleToolType.ISSUE_READ);
    when(ownerResolver.resolve(THREAD_ID)).thenReturn(Optional.of(coordinatorOwner));

    Issue issue =
        Issue.builder()
            .id(ISSUE_ID)
            .projectId(PROJECT_ID)
            .number(1L)
            .title("Issue 1")
            .description("Body")
            .status(IssueStatus.IN_PROGRESS)
            .build();
    when(issueService.getIssue(ISSUE_ID)).thenReturn(issue);
    when(issueService.isBlocked(ISSUE_ID)).thenReturn(false);
    when(issueService.getIssue(DEP_ISSUE_ID)).thenReturn(null); // 不存在的目标依赖
    when(issueService.listDependencies(ISSUE_ID))
        .thenReturn(
            List.of(
                IssueDependency.builder()
                    .issueId(ISSUE_ID)
                    .dependsOnIssueId(DEP_ISSUE_ID)
                    .projectId(PROJECT_ID)
                    .build()));

    String args = String.format("{\"issue_id\":\"%s\"}", ISSUE_ID);
    ToolExecutionRequest request = createRequest(tool.descriptor(), args, executionContext);
    TestListener listener = new TestListener();
    tool.execute(request, listener);

    assertNotNull(listener.outcome);
    ToolResult result = listener.outcome.result();
    assertTrue(result.error());
    String text = ((TextResultContent) result.contents().get(0)).text();
    assertEquals("Project thread ownership is inconsistent", text);
  }

  @Test
  void execute_issueRead_crossProjectDependencyDoesNotLeakTarget() {
    // 验证畸形跨项目 dependency snapshot 被固定一致性错误阻断，foreign title 不会泄漏
    ProjectRoleTool tool = createTool(ProjectRoleToolType.ISSUE_READ);
    when(ownerResolver.resolve(THREAD_ID)).thenReturn(Optional.of(coordinatorOwner));
    Issue issue =
        Issue.builder()
            .id(ISSUE_ID)
            .projectId(PROJECT_ID)
            .number(1L)
            .status(IssueStatus.TODO)
            .build();
    when(issueService.getIssue(ISSUE_ID)).thenReturn(issue);
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
    tool.execute(
        createRequest(
            tool.descriptor(), String.format("{\"issue_id\":\"%s\"}", ISSUE_ID), executionContext),
        listener);

    String text = ((TextResultContent) listener.outcome.result().contents().get(0)).text();
    assertTrue(listener.outcome.result().error());
    assertEquals("Project thread ownership is inconsistent", text);
    assertFalse(text.contains("FOREIGN_SECRET_TITLE"));
  }

  @Test
  void execute_issueAddAndRemoveDependency_success() {
    // 验证 issue_add_dependency 与 issue_remove_dependency 的参数校验与执行
    ProjectRoleTool addTool = createTool(ProjectRoleToolType.ISSUE_ADD_DEPENDENCY);
    ProjectRoleTool removeTool = createTool(ProjectRoleToolType.ISSUE_REMOVE_DEPENDENCY);
    when(ownerResolver.resolve(THREAD_ID)).thenReturn(Optional.of(coordinatorOwner));

    Issue issue = Issue.builder().id(ISSUE_ID).projectId(PROJECT_ID).number(1L).build();
    Issue dep = Issue.builder().id(DEP_ISSUE_ID).projectId(PROJECT_ID).number(2L).build();
    when(issueService.getIssue(ISSUE_ID)).thenReturn(issue);
    when(issueService.getIssue(DEP_ISSUE_ID)).thenReturn(dep);

    String args =
        String.format(
            "{\"issue_id\":\"%s\",\"depends_on_issue_id\":\"%s\",\"expected_version\":0}",
            ISSUE_ID, DEP_ISSUE_ID);

    ToolExecutionRequest addReq = createRequest(addTool.descriptor(), args, executionContext);
    TestListener addListener = new TestListener();
    addTool.execute(addReq, addListener);
    assertFalse(addListener.outcome.result().error());
    verify(issueService).addDependency(ISSUE_ID, DEP_ISSUE_ID, 0L);

    ToolExecutionRequest removeReq = createRequest(removeTool.descriptor(), args, executionContext);
    TestListener removeListener = new TestListener();
    removeTool.execute(removeReq, removeListener);
    assertFalse(removeListener.outcome.result().error());
    verify(issueService).removeDependency(ISSUE_ID, DEP_ISSUE_ID, 0L);
  }

  @Test
  void execute_issueSetStatusAndCancel_success() {
    // 验证 issue_set_status 与 issue_cancel 正向执行
    ProjectRoleTool statusTool = createTool(ProjectRoleToolType.ISSUE_SET_STATUS);
    ProjectRoleTool cancelTool = createTool(ProjectRoleToolType.ISSUE_CANCEL);
    when(ownerResolver.resolve(THREAD_ID)).thenReturn(Optional.of(coordinatorOwner));

    Issue issue = Issue.builder().id(ISSUE_ID).projectId(PROJECT_ID).number(1L).build();
    when(issueService.getIssue(ISSUE_ID)).thenReturn(issue);
    when(issueService.setStatus(ISSUE_ID, 0L, IssueStatus.TODO)).thenReturn(issue);
    when(issueService.cancelIssue(ISSUE_ID, 0L, "Cancelled by test")).thenReturn(issue);

    String statusArgs =
        String.format("{\"issue_id\":\"%s\",\"status\":\"TODO\",\"expected_version\":0}", ISSUE_ID);
    ToolExecutionRequest statusReq =
        createRequest(statusTool.descriptor(), statusArgs, executionContext);
    TestListener statusListener = new TestListener();
    statusTool.execute(statusReq, statusListener);
    assertFalse(statusListener.outcome.result().error());
    verify(issueService).setStatus(ISSUE_ID, 0L, IssueStatus.TODO);

    String cancelArgs =
        String.format(
            "{\"issue_id\":\"%s\",\"expected_version\":0,\"reason\":\"Cancelled by test\"}",
            ISSUE_ID);
    ToolExecutionRequest cancelReq =
        createRequest(cancelTool.descriptor(), cancelArgs, executionContext);
    TestListener cancelListener = new TestListener();
    cancelTool.execute(cancelReq, cancelListener);
    assertFalse(cancelListener.outcome.result().error());
    verify(issueService).cancelIssue(ISSUE_ID, 0L, "Cancelled by test");
  }

  // --- 3. Executor 2 项工具正向测试与 tool:{invocationId} 绑定 ---

  @Test
  void execute_issueSubmit_generatesTerminalActionIdAndReturnsFreshIssue() {
    // 验证 issue_submit 的 terminalActionId 由 context.invocationId 自动派生且返回 fresh issue
    ProjectRoleTool tool = createTool(ProjectRoleToolType.ISSUE_SUBMIT);
    when(ownerResolver.resolve(THREAD_ID)).thenReturn(Optional.of(executorOwner));

    IssueRun completedRun =
        IssueRun.builder()
            .id(RUN_ID)
            .issueId(ISSUE_ID)
            .role(IssueRunRole.EXECUTOR)
            .actorType(IssueRunActorType.AGENT)
            .agentName("coder-agent")
            .status(IssueRunStatus.COMPLETED)
            .outcome(IssueRunOutcome.SUBMITTED)
            .observedSpecRevision(1L)
            .observedInputSequence(2L)
            .terminalActionId("tool:" + INVOCATION_ID)
            .build();
    when(issueRunService.submitRun(
            eq(RUN_ID),
            eq("tool:" + INVOCATION_ID),
            eq(1L),
            eq(2L),
            eq("Summary"),
            eq("Verification evidence")))
        .thenReturn(completedRun);

    Issue freshIssue =
        Issue.builder()
            .id(ISSUE_ID)
            .projectId(PROJECT_ID)
            .number(1L)
            .status(IssueStatus.IN_REVIEW)
            .version(5L)
            .specRevision(1L)
            .inputSequence(2L)
            .build();
    when(issueService.getIssue(ISSUE_ID)).thenReturn(freshIssue);

    String args =
        "{\"observed_spec_revision\":1,\"observed_input_sequence\":2,\"summary\":\"Summary\",\"verification\":\"Verification evidence\"}";
    ToolExecutionRequest request = createRequest(tool.descriptor(), args, executionContext);
    TestListener listener = new TestListener();
    tool.execute(request, listener);

    assertFalse(listener.outcome.result().error());
    verify(issueRunService)
        .submitRun(RUN_ID, "tool:" + INVOCATION_ID, 1L, 2L, "Summary", "Verification evidence");

    String text = ((TextResultContent) listener.outcome.result().contents().get(0)).text();
    assertTrue(text.contains("\"status\" : \"IN_REVIEW\""));
    assertFalse(text.contains("tool:" + INVOCATION_ID), "terminalActionId must be desensitized");
  }

  @Test
  void execute_issueRequestInput_success() {
    // 验证 issue_request_input 参数正确传递并返回 fresh issue
    ProjectRoleTool tool = createTool(ProjectRoleToolType.ISSUE_REQUEST_INPUT);
    when(ownerResolver.resolve(THREAD_ID)).thenReturn(Optional.of(executorOwner));

    IssueRun waitingRun =
        IssueRun.builder()
            .id(RUN_ID)
            .issueId(ISSUE_ID)
            .role(IssueRunRole.EXECUTOR)
            .actorType(IssueRunActorType.AGENT)
            .agentName("coder-agent")
            .status(IssueRunStatus.WAITING_HUMAN)
            .waitingReason("Need API key")
            .build();
    when(issueRunService.requestInput(
            eq(RUN_ID), eq(1L), eq(2L), eq("Need API key"), eq("Context details")))
        .thenReturn(waitingRun);

    Issue freshIssue =
        Issue.builder()
            .id(ISSUE_ID)
            .projectId(PROJECT_ID)
            .number(1L)
            .status(IssueStatus.IN_PROGRESS)
            .build();
    when(issueService.getIssue(ISSUE_ID)).thenReturn(freshIssue);

    String args =
        "{\"observed_spec_revision\":1,\"observed_input_sequence\":2,\"question\":\"Need API key\",\"context\":\"Context details\"}";
    ToolExecutionRequest request = createRequest(tool.descriptor(), args, executionContext);
    TestListener listener = new TestListener();
    tool.execute(request, listener);

    assertFalse(listener.outcome.result().error());
    verify(issueRunService).requestInput(RUN_ID, 1L, 2L, "Need API key", "Context details");
  }

  // --- 4. Reviewer 1 项工具正向测试 ---

  @Test
  void execute_issueReview_generatesTerminalActionIdAndDispatches() {
    // 验证 issue_review 自动派生 reviewerAgentName 与 tool:{invocationId}，且必须返回 fresh issue
    ProjectRoleTool tool = createTool(ProjectRoleToolType.ISSUE_REVIEW);
    when(ownerResolver.resolve(THREAD_ID)).thenReturn(Optional.of(reviewerOwner));

    IssueRun reviewedRun =
        IssueRun.builder()
            .id(RUN_ID)
            .issueId(ISSUE_ID)
            .role(IssueRunRole.REVIEWER)
            .actorType(IssueRunActorType.AGENT)
            .agentName("reviewer-agent")
            .status(IssueRunStatus.COMPLETED)
            .outcome(IssueRunOutcome.APPROVED)
            .terminalActionId("tool:" + INVOCATION_ID)
            .build();
    when(issueRunService.reviewRun(
            eq(ISSUE_ID),
            eq(RUN_ID),
            eq(IssueRunActorType.AGENT),
            eq("reviewer-agent"),
            eq("tool:" + INVOCATION_ID),
            eq(1L),
            eq(2L),
            eq(ReviewDecision.APPROVE),
            eq("Looks good"),
            eq("Tests verified")))
        .thenReturn(reviewedRun);

    Issue freshIssue =
        Issue.builder()
            .id(ISSUE_ID)
            .projectId(PROJECT_ID)
            .number(1L)
            .status(IssueStatus.DONE)
            .build();
    when(issueService.getIssue(ISSUE_ID)).thenReturn(freshIssue);

    String args =
        "{\"observed_spec_revision\":1,\"observed_input_sequence\":2,\"decision\":\"APPROVE\",\"summary\":\"Looks good\",\"verification\":\"Tests verified\"}";
    ToolExecutionRequest request = createRequest(tool.descriptor(), args, executionContext);
    TestListener listener = new TestListener();
    tool.execute(request, listener);

    assertFalse(listener.outcome.result().error());
    verify(issueRunService)
        .reviewRun(
            ISSUE_ID,
            RUN_ID,
            IssueRunActorType.AGENT,
            "reviewer-agent",
            "tool:" + INVOCATION_ID,
            1L,
            2L,
            ReviewDecision.APPROVE,
            "Looks good",
            "Tests verified");
  }

  // --- 5. 权限、作用域隔离与安全脱敏测试 ---

  @Test
  void execute_missingInvocationContext_returnsErrorToolResult() {
    // 验证缺失 invocation context 时返回通用错误
    ProjectRoleTool tool = createTool(ProjectRoleToolType.PROJECT_READ);
    ToolExecutionRequest request = createRequest(tool.descriptor(), "{}", null);

    TestListener listener = new TestListener();
    tool.execute(request, listener);

    ToolResult result = listener.outcome.result();
    assertTrue(result.error());
    assertTrue(
        ((TextResultContent) result.contents().get(0))
            .text()
            .contains("Missing invocation context"));
  }

  @Test
  void execute_unownedThread_returnsErrorToolResult() {
    // 验证未解析到属主的线程返回通用权限错误
    ProjectRoleTool tool = createTool(ProjectRoleToolType.PROJECT_READ);
    when(ownerResolver.resolve(THREAD_ID)).thenReturn(Optional.empty());

    ToolExecutionRequest request = createRequest(tool.descriptor(), "{}", executionContext);
    TestListener listener = new TestListener();
    tool.execute(request, listener);

    ToolResult result = listener.outcome.result();
    assertTrue(result.error());
    assertTrue(
        ((TextResultContent) result.contents().get(0))
            .text()
            .contains("Tool not permitted for current role or unowned session"));
  }

  @Test
  void execute_wrongRole_returnsErrorToolResult() {
    // 验证 Coordinator 尝试调用 Executor 工具时被权限拦截
    ProjectRoleTool tool = createTool(ProjectRoleToolType.ISSUE_SUBMIT);
    when(ownerResolver.resolve(THREAD_ID)).thenReturn(Optional.of(coordinatorOwner));

    String args =
        "{\"observed_spec_revision\":0,\"observed_input_sequence\":0,\"summary\":\"done\"}";
    ToolExecutionRequest request = createRequest(tool.descriptor(), args, executionContext);
    TestListener listener = new TestListener();
    tool.execute(request, listener);

    ToolResult result = listener.outcome.result();
    assertTrue(result.error());
    assertTrue(
        ((TextResultContent) result.contents().get(0))
            .text()
            .contains("Tool not permitted for current role or unowned session"));
  }

  @Test
  void execute_crossProjectAccess_returnsGenericNotFound() {
    // 验证跨项目访问 issue 时返回通用 Resource Not Found，坚决不泄漏跨项目信息
    ProjectRoleTool tool = createTool(ProjectRoleToolType.ISSUE_READ);
    when(ownerResolver.resolve(THREAD_ID)).thenReturn(Optional.of(coordinatorOwner));

    UUID otherProjectId = UUID.fromString("00000000-0000-0000-0000-000000000999");
    Issue foreignIssue = Issue.builder().id(ISSUE_ID).projectId(otherProjectId).number(1L).build();
    when(issueService.getIssue(ISSUE_ID)).thenReturn(foreignIssue);

    String args = String.format("{\"issue_id\":\"%s\"}", ISSUE_ID);
    ToolExecutionRequest request = createRequest(tool.descriptor(), args, executionContext);
    TestListener listener = new TestListener();
    tool.execute(request, listener);

    ToolResult result = listener.outcome.result();
    assertTrue(result.error());
    assertEquals("issue not found", ((TextResultContent) result.contents().get(0)).text());
  }

  @Test
  void execute_invalidUuid_doesNotEchoRawValue() {
    // 验证传入非法 UUID 格式时，返回通用错误且绝不回显非法输入内容
    ProjectRoleTool tool = createTool(ProjectRoleToolType.ISSUE_READ);
    when(ownerResolver.resolve(THREAD_ID)).thenReturn(Optional.of(coordinatorOwner));

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

  @Test
  void execute_unexpectedException_callsOnErrorWithoutCause() {
    // 验证遇到意外运行时异常时，调用 listener.onError 且异常不包含原 Throwable cause
    ProjectRoleTool tool = createTool(ProjectRoleToolType.PROJECT_READ);
    when(ownerResolver.resolve(THREAD_ID)).thenReturn(Optional.of(coordinatorOwner));
    when(projectService.getProject(PROJECT_ID))
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
    // 验证底层状态异常即使含标识也不会由工具结果回显
    ProjectRoleTool tool = createTool(ProjectRoleToolType.PROJECT_READ);
    when(ownerResolver.resolve(THREAD_ID)).thenReturn(Optional.of(coordinatorOwner));
    when(projectService.getProject(PROJECT_ID))
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
    // 验证回调仅触发一次
    ProjectRoleTool tool = createTool(ProjectRoleToolType.PROJECT_READ);
    when(ownerResolver.resolve(THREAD_ID)).thenReturn(Optional.of(coordinatorOwner));
    when(projectService.getProject(PROJECT_ID))
        .thenReturn(
            Project.builder().id(PROJECT_ID).title("P").coordinatorAgentName("agent").build());
    when(issueService.listIssues(PROJECT_ID, false)).thenReturn(List.of());

    ToolExecutionRequest request = createRequest(tool.descriptor(), "{}", executionContext);
    TestListener listener = new TestListener();

    tool.execute(request, listener);

    assertEquals(1, listener.count);
    assertNotNull(listener.outcome);
    assertNull(listener.error);
  }

  @Test
  void execute_versionConflictException_isSanitizedWithoutId() {
    // 验证版本冲突异常返回脱敏消息，不包含实体 ID
    ProjectRoleTool tool = createTool(ProjectRoleToolType.ISSUE_SET_STATUS);
    when(ownerResolver.resolve(THREAD_ID)).thenReturn(Optional.of(coordinatorOwner));

    Issue issue = Issue.builder().id(ISSUE_ID).projectId(PROJECT_ID).number(1L).build();
    when(issueService.getIssue(ISSUE_ID)).thenReturn(issue);
    when(issueService.setStatus(ISSUE_ID, 0L, IssueStatus.TODO))
        .thenThrow(new AiVersionConflictException("issue", ISSUE_ID.toString(), "0", "1"));

    String args =
        String.format("{\"issue_id\":\"%s\",\"status\":\"TODO\",\"expected_version\":0}", ISSUE_ID);
    ToolExecutionRequest request = createRequest(tool.descriptor(), args, executionContext);
    TestListener listener = new TestListener();
    tool.execute(request, listener);

    ToolResult result = listener.outcome.result();
    assertTrue(result.error());
    String errorMsg = ((TextResultContent) result.contents().get(0)).text();
    assertEquals("issue version conflict: expected=0 actual=1", errorMsg);
    assertFalse(errorMsg.contains(ISSUE_ID.toString()));
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
      case PROJECT_READ, ISSUE_LIST -> "{}";
      case ISSUE_READ -> String.format("{\"issue_id\":\"%s\"}", ISSUE_ID);
      case ISSUE_CREATE -> "{\"title\":\"title\"}";
      case ISSUE_UPDATE -> String.format(
          "{\"issue_id\":\"%s\",\"expected_version\":0,\"title\":\"title\"}", ISSUE_ID);
      case ISSUE_ADD_DEPENDENCY, ISSUE_REMOVE_DEPENDENCY -> String.format(
          "{\"issue_id\":\"%s\",\"depends_on_issue_id\":\"%s\",\"expected_version\":0}",
          ISSUE_ID, DEP_ISSUE_ID);
      case ISSUE_SET_STATUS -> String.format(
          "{\"issue_id\":\"%s\",\"status\":\"TODO\",\"expected_version\":0}", ISSUE_ID);
      case ISSUE_CANCEL -> String.format("{\"issue_id\":\"%s\",\"expected_version\":0}", ISSUE_ID);
      case ISSUE_SUBMIT -> "{\"observed_spec_revision\":0,\"observed_input_sequence\":0,\"summary\":\"done\"}";
      case ISSUE_REQUEST_INPUT -> "{\"observed_spec_revision\":0,\"observed_input_sequence\":0,\"question\":\"help\"}";
      case ISSUE_REVIEW -> "{\"observed_spec_revision\":0,\"observed_input_sequence\":0,\"decision\":\"APPROVE\",\"summary\":\"ok\"}";
    };
  }
}
