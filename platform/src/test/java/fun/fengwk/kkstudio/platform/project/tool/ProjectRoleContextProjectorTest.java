package fun.fengwk.kkstudio.platform.project.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.common.json.JsonValues;
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
import fun.fengwk.kkstudio.platform.project.repo.IssueDependencyRepository;
import fun.fengwk.kkstudio.platform.project.repo.IssueInputRepository;
import fun.fengwk.kkstudio.platform.project.repo.IssueRepository;
import fun.fengwk.kkstudio.platform.project.repo.IssueRunRepository;
import fun.fengwk.kkstudio.platform.project.repo.ProjectRepository;
import fun.fengwk.kkstudio.platform.project.service.IssueService;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link ProjectRoleContextProjector} 单元测试。
 *
 * <p>测试意图：
 *
 * <ul>
 *   <li>验证 Coordinator、Executor、Reviewer 三种角色的动态上下文结构化投影；
 *   <li>验证关键交付围栏：Executor 与 Reviewer 仅能看到 sequence &lt;= run.observedInputSequence 的输入；
 *   <li>验证未交付（sequence &gt; observedInputSequence）的输入绝对不会泄漏到上下文中；
 *   <li>验证敏感字段脱敏：绝不暴露 IssueInput.idempotencyKey 或 IssueRun.terminalActionId；
 *   <li>验证 Reviewer 上下文中包含依赖详情、Executor 提交成果（summary/verification）与历史 Reviewer runs；
 *   <li>验证实体不一致或缺失时抛出通用 ownership inconsistency 异常，坚决不静默降级；
 *   <li>验证未解析属主的线程返回 Optional.empty()。
 * </ul>
 */
class ProjectRoleContextProjectorTest {

  private static final UUID THREAD_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
  private static final UUID ISSUE_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");
  private static final UUID DEP_ISSUE_ID = UUID.fromString("00000000-0000-0000-0000-000000000004");
  private static final UUID RUN_ID = UUID.fromString("00000000-0000-0000-0000-000000000005");
  private static final UUID SUBMISSION_RUN_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000006");
  private static final UUID HISTORIC_REVIEW_RUN_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000007");

  private ProjectThreadOwnerResolver ownerResolver;
  private ProjectRepository projectRepository;
  private IssueRepository issueRepository;
  private IssueDependencyRepository issueDependencyRepository;
  private IssueInputRepository issueInputRepository;
  private IssueRunRepository issueRunRepository;
  private IssueService issueService;
  private ProjectRoleContextProjector projector;

  @BeforeEach
  void setUp() {
    ownerResolver = mock(ProjectThreadOwnerResolver.class);
    projectRepository = mock(ProjectRepository.class);
    issueRepository = mock(IssueRepository.class);
    issueDependencyRepository = mock(IssueDependencyRepository.class);
    issueInputRepository = mock(IssueInputRepository.class);
    issueRunRepository = mock(IssueRunRepository.class);
    issueService = mock(IssueService.class);

    projector =
        new ProjectRoleContextProjector(
            ownerResolver,
            projectRepository,
            issueRepository,
            issueDependencyRepository,
            issueInputRepository,
            issueRunRepository,
            issueService);
  }

  @Test
  void project_missingThreadOrUnowned_returnsEmpty() {
    // 验证 null 或未绑定的线程返回 Optional.empty()
    assertTrue(projector.project(null).isEmpty());

    when(ownerResolver.resolve(THREAD_ID)).thenReturn(Optional.empty());
    assertTrue(projector.project(THREAD_ID).isEmpty());
  }

  @Test
  void project_coordinator_successAndSanitized() {
    // 验证 Coordinator 上下文渲染结构完整且排除敏感标识
    ProjectThreadOwnerContext owner =
        new ProjectThreadOwnerContext(
            ProjectRole.COORDINATOR, PROJECT_ID, null, null, "coordinator-agent");
    when(ownerResolver.resolve(THREAD_ID)).thenReturn(Optional.of(owner));

    Project project =
        Project.builder()
            .id(PROJECT_ID)
            .title("Core Project")
            .description("Test description")
            .coordinatorAgentName("coordinator-agent")
            .nextIssueNumber(2)
            .version(1L)
            .build();
    when(projectRepository.getById(PROJECT_ID)).thenReturn(project);

    Issue issue =
        Issue.builder()
            .id(ISSUE_ID)
            .projectId(PROJECT_ID)
            .number(1L)
            .title("Issue One")
            .status(IssueStatus.TODO)
            .assigneeAgentName("coder")
            .reviewerAgentName("reviewer")
            .version(2L)
            .specRevision(1L)
            .inputSequence(0L)
            .build();
    when(issueRepository.listByProjectIdAndArchived(PROJECT_ID, false)).thenReturn(List.of(issue));
    when(issueService.isBlocked(ISSUE_ID)).thenReturn(false);

    Optional<String> resultOpt = projector.project(THREAD_ID);
    assertTrue(resultOpt.isPresent());
    String context = resultOpt.get();

    assertTrue(context.contains("# Project Coordinator Context"));
    assertTrue(context.contains("Core Project"));
    assertTrue(context.contains("Issue One"));
    assertTrue(context.contains("```json"));
    assertTrue(context.contains("\"coordinator_agent_name\" : \"coordinator-agent\""));
  }

  @Test
  void project_executor_deliveryFenceAndSanitization() {
    // 验证 Executor 上下文严格遵守 delivery fence：sequence > observedInputSequence 的输入被过滤
    ProjectThreadOwnerContext owner =
        new ProjectThreadOwnerContext(
            ProjectRole.EXECUTOR, PROJECT_ID, ISSUE_ID, RUN_ID, "coder-agent");
    when(ownerResolver.resolve(THREAD_ID)).thenReturn(Optional.of(owner));

    Issue issue =
        Issue.builder()
            .id(ISSUE_ID)
            .projectId(PROJECT_ID)
            .number(1L)
            .title("Implement Feature")
            .description("Spec details")
            .status(IssueStatus.IN_PROGRESS)
            .version(1L)
            .specRevision(2L)
            .inputSequence(5L)
            .build();
    when(issueRepository.getById(ISSUE_ID)).thenReturn(issue);

    IssueRun run =
        IssueRun.builder()
            .id(RUN_ID)
            .issueId(ISSUE_ID)
            .ordinal(1L)
            .role(IssueRunRole.EXECUTOR)
            .actorType(IssueRunActorType.AGENT)
            .agentName("coder-agent")
            .status(IssueRunStatus.RUNNING)
            .observedSpecRevision(2L)
            .observedInputSequence(2L) // 关键游标：只能看到 <= 2 的输入
            .continuationCount(0)
            .maxContinuations(3)
            .terminalActionId("tool:secret-terminal-action-id")
            .build();
    when(issueRunRepository.getById(RUN_ID)).thenReturn(run);

    Issue depIssue =
        Issue.builder()
            .id(DEP_ISSUE_ID)
            .projectId(PROJECT_ID)
            .number(99L)
            .title("Prerequisite")
            .status(IssueStatus.DONE)
            .build();
    when(issueDependencyRepository.listByIssueId(ISSUE_ID))
        .thenReturn(
            List.of(
                IssueDependency.builder()
                    .issueId(ISSUE_ID)
                    .dependsOnIssueId(DEP_ISSUE_ID)
                    .projectId(PROJECT_ID)
                    .build()));
    when(issueRepository.getById(DEP_ISSUE_ID)).thenReturn(depIssue);

    IssueInput delivered1 =
        IssueInput.builder()
            .issueId(ISSUE_ID)
            .sequence(1L)
            .kind(IssueInputKind.SYSTEM)
            .body("Delivered 1")
            .idempotencyKey("secret-idempotency-1")
            .createdAt(Instant.now())
            .build();
    IssueInput delivered2 =
        IssueInput.builder()
            .issueId(ISSUE_ID)
            .sequence(2L)
            .kind(IssueInputKind.HUMAN)
            .body("Delivered 2")
            .idempotencyKey("secret-idempotency-2")
            .createdAt(Instant.now())
            .build();
    IssueInput undelivered3 =
        IssueInput.builder()
            .issueId(ISSUE_ID)
            .sequence(3L)
            .kind(IssueInputKind.HUMAN)
            .body("Undelivered Secret Input 3")
            .idempotencyKey("secret-idempotency-3")
            .createdAt(Instant.now())
            .build();

    when(issueInputRepository.listByIssueId(ISSUE_ID))
        .thenReturn(List.of(delivered1, delivered2, undelivered3));

    Optional<String> resultOpt = projector.project(THREAD_ID);
    assertTrue(resultOpt.isPresent());
    String context = resultOpt.get();

    assertTrue(context.contains("# Issue Execution Context: Executor"));
    assertTrue(context.contains("Delivered 1"));
    assertTrue(context.contains("Delivered 2"));
    // 核心断言：未交付输入（sequence=3）严禁泄漏
    assertFalse(context.contains("Undelivered Secret Input 3"));
    // 核心断言：敏感键绝不泄漏
    assertFalse(context.contains("secret-idempotency"));
    assertFalse(context.contains("secret-terminal-action-id"));
    assertTrue(context.contains("observed_spec_revision: 2"));
    assertTrue(context.contains("observed_input_sequence: 2"));
    JsonNode data = contextData(context);
    assertEquals(2L, data.path("issue").path("spec_revision").asLong());
    assertEquals(2L, data.path("issue").path("input_sequence").asLong());
  }

  @Test
  void project_reviewer_submissionAndHistoryAndFence() {
    // 验证 Reviewer 上下文包含待审成果、历史反馈，并遵循 fence 与脱敏约束
    ProjectThreadOwnerContext owner =
        new ProjectThreadOwnerContext(
            ProjectRole.REVIEWER, PROJECT_ID, ISSUE_ID, RUN_ID, "reviewer-agent");
    when(ownerResolver.resolve(THREAD_ID)).thenReturn(Optional.of(owner));

    Issue issue =
        Issue.builder()
            .id(ISSUE_ID)
            .projectId(PROJECT_ID)
            .number(2L)
            .title("Review Feature")
            .description("Spec details")
            .status(IssueStatus.IN_REVIEW)
            .version(3L)
            .specRevision(1L)
            .inputSequence(4L)
            .build();
    when(issueRepository.getById(ISSUE_ID)).thenReturn(issue);

    IssueRun reviewRun =
        IssueRun.builder()
            .id(RUN_ID)
            .issueId(ISSUE_ID)
            .ordinal(2L)
            .role(IssueRunRole.REVIEWER)
            .actorType(IssueRunActorType.AGENT)
            .agentName("reviewer-agent")
            .status(IssueRunStatus.RUNNING)
            .submissionRunId(SUBMISSION_RUN_ID)
            .observedSpecRevision(1L)
            .observedInputSequence(1L)
            .terminalActionId("tool:secret-terminal-action-reviewer")
            .build();
    when(issueRunRepository.getById(RUN_ID)).thenReturn(reviewRun);

    IssueRun submissionRun =
        IssueRun.builder()
            .id(SUBMISSION_RUN_ID)
            .issueId(ISSUE_ID)
            .ordinal(1L)
            .role(IssueRunRole.EXECUTOR)
            .status(IssueRunStatus.COMPLETED)
            .outcome(IssueRunOutcome.SUBMITTED)
            .agentName("coder-agent")
            .result("{\"summary\":\"feature implemented\",\"verification\":\"passed all tests\"}")
            .terminalActionId("tool:secret-terminal-action-submission")
            .build();
    when(issueRunRepository.getById(SUBMISSION_RUN_ID)).thenReturn(submissionRun);

    IssueRun historicReview =
        IssueRun.builder()
            .id(HISTORIC_REVIEW_RUN_ID)
            .issueId(ISSUE_ID)
            .ordinal(0L)
            .role(IssueRunRole.REVIEWER)
            .status(IssueRunStatus.COMPLETED)
            .outcome(IssueRunOutcome.CHANGES_REQUESTED)
            .result("{\"summary\":\"needs more tests\"}")
            .build();
    when(issueRunRepository.listByIssueId(ISSUE_ID))
        .thenReturn(List.of(historicReview, submissionRun, reviewRun));

    Issue depIssue =
        Issue.builder()
            .id(DEP_ISSUE_ID)
            .projectId(PROJECT_ID)
            .number(99L)
            .title("Prerequisite")
            .status(IssueStatus.DONE)
            .build();
    when(issueDependencyRepository.listByIssueId(ISSUE_ID))
        .thenReturn(
            List.of(
                IssueDependency.builder()
                    .issueId(ISSUE_ID)
                    .dependsOnIssueId(DEP_ISSUE_ID)
                    .projectId(PROJECT_ID)
                    .build()));
    when(issueRepository.getById(DEP_ISSUE_ID)).thenReturn(depIssue);

    IssueInput input1 =
        IssueInput.builder()
            .issueId(ISSUE_ID)
            .sequence(1L)
            .kind(IssueInputKind.HUMAN)
            .body("Delivered comment")
            .idempotencyKey("secret-input-key-1")
            .build();
    when(issueInputRepository.listByIssueId(ISSUE_ID)).thenReturn(List.of(input1));

    Optional<String> resultOpt = projector.project(THREAD_ID);
    assertTrue(resultOpt.isPresent());
    String context = resultOpt.get();

    assertTrue(context.contains("# Issue Review Context: Reviewer"));
    assertTrue(context.contains("feature implemented"));
    assertTrue(context.contains("passed all tests"));
    assertTrue(context.contains("needs more tests"));
    assertFalse(context.contains("secret-terminal-action"));
    assertFalse(context.contains("secret-input-key"));
    assertEquals(1L, contextData(context).path("issue").path("input_sequence").asLong());
  }

  @Test
  void project_executor_crossProjectDependencyIsRejectedWithoutLeak() {
    // 验证跨项目依赖不会进入 Executor 动态上下文
    ProjectThreadOwnerContext owner =
        new ProjectThreadOwnerContext(
            ProjectRole.EXECUTOR, PROJECT_ID, ISSUE_ID, RUN_ID, "coder-agent");
    when(ownerResolver.resolve(THREAD_ID)).thenReturn(Optional.of(owner));
    when(issueRepository.getById(ISSUE_ID))
        .thenReturn(
            Issue.builder()
                .id(ISSUE_ID)
                .projectId(PROJECT_ID)
                .status(IssueStatus.IN_PROGRESS)
                .specRevision(1L)
                .build());
    when(issueRunRepository.getById(RUN_ID))
        .thenReturn(
            IssueRun.builder()
                .id(RUN_ID)
                .issueId(ISSUE_ID)
                .role(IssueRunRole.EXECUTOR)
                .actorType(IssueRunActorType.AGENT)
                .agentName("coder-agent")
                .observedSpecRevision(1L)
                .build());
    when(issueDependencyRepository.listByIssueId(ISSUE_ID))
        .thenReturn(
            List.of(
                IssueDependency.builder()
                    .issueId(ISSUE_ID)
                    .dependsOnIssueId(DEP_ISSUE_ID)
                    .projectId(PROJECT_ID)
                    .build()));
    when(issueRepository.getById(DEP_ISSUE_ID))
        .thenReturn(
            Issue.builder()
                .id(DEP_ISSUE_ID)
                .projectId(UUID.fromString("00000000-0000-0000-0000-000000000099"))
                .title("FOREIGN_SECRET_TITLE")
                .build());

    IllegalStateException error =
        assertThrows(IllegalStateException.class, () -> projector.project(THREAD_ID));
    assertEquals("Project thread ownership is inconsistent", error.getMessage());
    assertFalse(error.getMessage().contains("FOREIGN_SECRET_TITLE"));
  }

  @Test
  void project_reviewer_mismatchedSubmissionIsRejectedWithoutLeak() {
    // 验证 Reviewer 的 submission 必须属于当前 Issue 且为已提交 Executor Run
    ProjectThreadOwnerContext owner =
        new ProjectThreadOwnerContext(
            ProjectRole.REVIEWER, PROJECT_ID, ISSUE_ID, RUN_ID, "reviewer-agent");
    when(ownerResolver.resolve(THREAD_ID)).thenReturn(Optional.of(owner));
    when(issueRepository.getById(ISSUE_ID))
        .thenReturn(
            Issue.builder()
                .id(ISSUE_ID)
                .projectId(PROJECT_ID)
                .status(IssueStatus.IN_REVIEW)
                .specRevision(1L)
                .build());
    when(issueRunRepository.getById(RUN_ID))
        .thenReturn(
            IssueRun.builder()
                .id(RUN_ID)
                .issueId(ISSUE_ID)
                .role(IssueRunRole.REVIEWER)
                .actorType(IssueRunActorType.AGENT)
                .agentName("reviewer-agent")
                .submissionRunId(SUBMISSION_RUN_ID)
                .observedSpecRevision(1L)
                .build());
    when(issueRunRepository.getById(SUBMISSION_RUN_ID))
        .thenReturn(
            IssueRun.builder()
                .id(SUBMISSION_RUN_ID)
                .issueId(DEP_ISSUE_ID)
                .role(IssueRunRole.EXECUTOR)
                .status(IssueRunStatus.COMPLETED)
                .outcome(IssueRunOutcome.SUBMITTED)
                .result("FOREIGN_SECRET_RESULT")
                .build());

    IllegalStateException error =
        assertThrows(IllegalStateException.class, () -> projector.project(THREAD_ID));
    assertEquals("Project thread ownership is inconsistent", error.getMessage());
    assertFalse(error.getMessage().contains("FOREIGN_SECRET_RESULT"));
  }

  @Test
  void project_inconsistentOwnership_throwsIllegalStateException() {
    // 验证当实体缺失或项目归属不一致时坚决抛出 generic ownership inconsistency 异常
    ProjectThreadOwnerContext owner =
        new ProjectThreadOwnerContext(
            ProjectRole.COORDINATOR, PROJECT_ID, null, null, "coordinator-agent");
    when(ownerResolver.resolve(THREAD_ID)).thenReturn(Optional.of(owner));
    when(projectRepository.getById(PROJECT_ID)).thenReturn(null); // Project 缺失

    IllegalStateException ex =
        assertThrows(IllegalStateException.class, () -> projector.project(THREAD_ID));
    assertTrue(ex.getMessage().contains("Project thread ownership is inconsistent"));
  }

  private JsonNode contextData(String context) {
    String startMarker = "```json\n";
    int start = context.indexOf(startMarker);
    int end = context.indexOf("\n```", start + startMarker.length());
    return JsonValues.readTree(context.substring(start + startMarker.length(), end));
  }
}
