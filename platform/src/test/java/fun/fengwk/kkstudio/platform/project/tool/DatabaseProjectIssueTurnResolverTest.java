package fun.fengwk.kkstudio.platform.project.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.project.domain.IssueRunStatus;
import fun.fengwk.kkstudio.project.domain.ProjectWorkflowJsonCodec;
import fun.fengwk.kkstudio.project.model.Issue;
import fun.fengwk.kkstudio.project.model.IssueAgentThread;
import fun.fengwk.kkstudio.project.model.IssueRun;
import fun.fengwk.kkstudio.project.model.Project;
import fun.fengwk.kkstudio.project.repo.IssueAgentThreadRepository;
import fun.fengwk.kkstudio.project.repo.IssueRepository;
import fun.fengwk.kkstudio.project.repo.IssueRunRepository;
import fun.fengwk.kkstudio.project.repo.ProjectRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link DatabaseProjectIssueTurnResolver} 的归属解析契约：只有「稳定归属 + Issue/Project 事实 + 当前阶段职责 + 活动 Run 坐标」
 * 四者同时成立才产出 turn 事实，任一门禁关闭都确定性拒绝，数据不一致原样传播。
 */
class DatabaseProjectIssueTurnResolverTest {

  private static final UUID THREAD_ID = new UUID(0L, 1L);
  private static final UUID SESSION_ID = new UUID(0L, 2L);
  private static final UUID ISSUE_ID = new UUID(0L, 3L);
  private static final UUID PROJECT_ID = new UUID(0L, 4L);
  private static final UUID RUN_ID = new UUID(0L, 5L);

  /** INIT → DESIGN(designer, env-a, 指令) → REVIEW(reviewer) → DONE 的严格 workflow。 */
  private static final String WORKFLOW_JSON =
      "{"
          + "\"states\":["
          + "{\"state\":\"INIT\",\"name\":\"待开始\",\"next\":[\"DESIGN\"]},"
          + "{\"state\":\"DESIGN\",\"name\":\"设计\",\"agent\":\"designer\",\"environment\":\"env-a\","
          + "\"instructions\":\"完成可交付方案\",\"maxRuns\":3,\"next\":[\"REVIEW\"]},"
          + "{\"state\":\"REVIEW\",\"name\":\"检查\",\"agent\":\"reviewer\",\"maxRuns\":1,"
          + "\"next\":[\"DONE\"]},"
          + "{\"state\":\"BLOCKED\",\"name\":\"业务阻塞\"},"
          + "{\"state\":\"DONE\",\"name\":\"完成\"}"
          + "]}";

  private final IssueAgentThreadRepository bindings = mock(IssueAgentThreadRepository.class);
  private final IssueRepository issues = mock(IssueRepository.class);
  private final ProjectRepository projects = mock(ProjectRepository.class);
  private final IssueRunRepository runs = mock(IssueRunRepository.class);
  private final DatabaseProjectIssueTurnResolver resolver =
      new DatabaseProjectIssueTurnResolver(
          bindings, issues, projects, runs, new ProjectWorkflowJsonCodec());

  DatabaseProjectIssueTurnResolverTest() {
    when(bindings.findByThreadId(THREAD_ID))
        .thenReturn(new IssueAgentThread(ISSUE_ID, "designer", THREAD_ID));
    Issue issue = issue("DESIGN");
    when(issues.getById(ISSUE_ID)).thenReturn(issue);
    when(projects.getById(PROJECT_ID)).thenReturn(project(WORKFLOW_JSON));
    when(runs.getActiveByIssueId(ISSUE_ID)).thenReturn(activeRun("DESIGN", THREAD_ID, SESSION_ID));
  }

  private static Issue issue(String state) {
    return Issue.builder()
        .id(ISSUE_ID)
        .projectId(PROJECT_ID)
        .number(12L)
        .title("修复渲染缺陷")
        .description("验收描述")
        .state(state)
        .build();
  }

  private static Project project(String workflowJson) {
    return Project.builder().id(PROJECT_ID).workflowJson(workflowJson).build();
  }

  private static IssueRun activeRun(String state, UUID threadId, UUID sessionId) {
    return IssueRun.builder()
        .id(RUN_ID)
        .issueId(ISSUE_ID)
        .state(state)
        .sessionId(sessionId)
        .threadId(threadId)
        .status(IssueRunStatus.RUNNING)
        .build();
  }

  private Optional<ProjectIssueTurnFacts> resolve() {
    return resolver.resolve(THREAD_ID, "designer", SESSION_ID);
  }

  private ProjectIssueTurnRejection reject() {
    return assertThrows(ProjectIssueTurnRejection.class, this::resolve);
  }

  /** 测试意图：未绑定的 Thread 是普通 branch，必须返回空而不是任何形式的 Project 事实。 */
  @Test
  void returnsEmptyForUnboundThread() {
    UUID unbound = new UUID(0L, 99L);
    assertTrue(resolver.resolve(unbound, "designer", SESSION_ID).isEmpty());
  }

  /** 测试意图：四类事实齐备时投影当前阶段职责与合法目标，Environment 来自阶段配置。 */
  @Test
  void projectsCurrentStageFacts() {
    ProjectIssueTurnFacts facts = resolve().orElseThrow();

    assertEquals(ISSUE_ID, facts.issueId());
    assertEquals(PROJECT_ID, facts.projectId());
    assertEquals(RUN_ID, facts.runId());
    assertEquals(12L, facts.issueNumber());
    assertEquals("修复渲染缺陷", facts.issueTitle());
    assertEquals("验收描述", facts.issueDescription());
    assertEquals("DESIGN", facts.stage());
    assertEquals("设计", facts.stageName());
    assertEquals("完成可交付方案", facts.stageInstructions());
    assertEquals(List.of("REVIEW"), facts.nextStates());
    assertEquals("env-a", facts.environmentName());
    assertEquals("designer", facts.agentName());
  }

  /** 测试意图：branch 冻结的 Agent 必须与稳定绑定完全一致，否则拒绝（Thread 不可被别的 Agent 借用）。 */
  @Test
  void rejectsWhenBranchAgentDiffersFromBindingAgent() {
    ProjectIssueTurnRejection rejection =
        assertThrows(
            ProjectIssueTurnRejection.class,
            () -> resolver.resolve(THREAD_ID, "reviewer", SESSION_ID));
    assertTrue(rejection.getMessage().contains("bound to agent designer"), rejection.getMessage());
  }

  /** 测试意图：阶段被改派给别的 Agent 后，旧 Thread 不能继续以旧职责执行。 */
  @Test
  void rejectsWhenCurrentStageIsReassignedToAnotherAgent() {
    when(projects.getById(PROJECT_ID))
        .thenReturn(project(WORKFLOW_JSON.replace("\"designer\"", "\"reviewer\"")));

    assertTrue(reject().getMessage().contains("is assigned to agent reviewer"));
  }

  /** 测试意图：归档、控制暂停与保留阶段（INIT/BLOCKED/DONE）都不是可执行的 Issue Agent turn。 */
  @Test
  void rejectsArchivedPausedAndReservedStages() {
    Issue archived = issue("DESIGN");
    archived.setArchivedAt(Instant.now());
    when(issues.getById(ISSUE_ID)).thenReturn(archived);
    assertEquals("Issue is archived", reject().getMessage());

    Issue paused = issue("DESIGN");
    paused.setPauseReason("USER");
    when(issues.getById(ISSUE_ID)).thenReturn(paused);
    assertEquals("Issue is paused: USER", reject().getMessage());

    when(issues.getById(ISSUE_ID)).thenReturn(issue("BLOCKED"));
    assertEquals("Issue is not in an executable work stage: BLOCKED", reject().getMessage());

    when(issues.getById(ISSUE_ID)).thenReturn(issue("INIT"));
    assertEquals("Issue is not in an executable work stage: INIT", reject().getMessage());
  }

  /** 测试意图：停用或被改成人工阶段的阶段不产生 Agent turn。 */
  @Test
  void rejectsDisabledOrManualCurrentStage() {
    when(projects.getById(PROJECT_ID))
        .thenReturn(
            project(
                WORKFLOW_JSON.replace(
                    "\"agent\":\"designer\",", "\"enabled\":false,\"agent\":\"designer\",")));

    assertEquals("Issue stage DESIGN has no enabled agent", reject().getMessage());
  }

  /** 测试意图：没有活动 Run 时不得规划 Issue Agent turn（输入必须经 Issue 编排接受 Run，而不是借用旧权限）。 */
  @Test
  void rejectsWhenIssueHasNoActiveRun() {
    when(runs.getActiveByIssueId(ISSUE_ID)).thenReturn(null);
    assertEquals("Issue has no active run", reject().getMessage());

    IssueRun terminal = activeRun("DESIGN", THREAD_ID, SESSION_ID);
    terminal.setStatus(IssueRunStatus.COMPLETED);
    when(runs.getActiveByIssueId(ISSUE_ID)).thenReturn(terminal);
    assertEquals("Issue has no active run", reject().getMessage());
  }

  /** 测试意图：活动 Run 的 Thread / Session / 阶段任一不匹配都拒绝，旧 Run 与同名阶段的迟到执行都不能借当前权限。 */
  @Test
  void rejectsActiveRunWithForeignIdentity() {
    when(runs.getActiveByIssueId(ISSUE_ID))
        .thenReturn(activeRun("DESIGN", new UUID(0L, 77L), SESSION_ID));
    assertEquals("Active run belongs to a different thread", reject().getMessage());

    when(runs.getActiveByIssueId(ISSUE_ID))
        .thenReturn(activeRun("DESIGN", THREAD_ID, new UUID(0L, 78L)));
    assertEquals("Active run belongs to a different session", reject().getMessage());

    when(runs.getActiveByIssueId(ISSUE_ID)).thenReturn(activeRun("REVIEW", THREAD_ID, SESSION_ID));
    assertEquals(
        "Active run stage REVIEW does not match the current issue stage DESIGN",
        reject().getMessage());
  }

  /** 测试意图：workflow 或 Issue 状态损坏时确定性拒绝规划，而不是把非法配置当成可执行阶段。 */
  @Test
  void rejectsInvalidWorkflowOrIssueState() {
    when(projects.getById(PROJECT_ID)).thenReturn(project("not-a-json"));
    assertEquals("invalid project workflow or issue state: DESIGN", reject().getMessage());

    when(issues.getById(ISSUE_ID)).thenReturn(issue("design"));
    assertTrue(reject().getMessage().startsWith("invalid project workflow or issue state"));
  }

  /** 测试意图：归属存在但 Issue/Project 行缺失是数据不一致，必须原样传播而不是降级为「无 Project 工具」。 */
  @Test
  void throwsWhenOwnedIssueOrProjectRowIsMissing() {
    when(issues.getById(ISSUE_ID)).thenReturn(null);
    IllegalStateException issueMissing = assertThrows(IllegalStateException.class, this::resolve);
    assertEquals("Project thread ownership is inconsistent", issueMissing.getMessage());

    when(issues.getById(ISSUE_ID)).thenReturn(issue("DESIGN"));
    when(projects.getById(PROJECT_ID)).thenReturn(null);
    IllegalStateException projectMissing = assertThrows(IllegalStateException.class, this::resolve);
    assertEquals("Project thread ownership is inconsistent", projectMissing.getMessage());
  }

  /** 测试意图：阶段未配置 Environment 时事实里的 name 为 null（由 Turn 解析器清除 branch 上的历史选择）。 */
  @Test
  void projectsNullEnvironmentWhenStageDoesNotConfigureOne() {
    when(projects.getById(PROJECT_ID))
        .thenReturn(project(WORKFLOW_JSON.replace("\"environment\":\"env-a\",", "")));

    ProjectIssueTurnFacts facts = resolve().orElseThrow();
    assertNull(facts.environmentName());
    assertInstanceOf(List.class, facts.nextStates());
  }
}
