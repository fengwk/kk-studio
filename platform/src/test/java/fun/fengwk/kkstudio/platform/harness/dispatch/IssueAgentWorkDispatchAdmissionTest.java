package fun.fengwk.kkstudio.platform.harness.dispatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import fun.fengwk.kkstudio.harness.common.schema.InputSchema;
import fun.fengwk.kkstudio.harness.contributor.api.EnvironmentSupport;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ContributorBinding;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.runtime.port.WorkDispatchRequest;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTargetType;
import fun.fengwk.kkstudio.harness.tool.AgentToolDefinition;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.ToolVisibility;
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

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 产品派发门禁：把「宿主许可」与 Harness 的 READY -&gt; DISPATCHING 持久意图绑成一个事务，且必须先取产品行锁再执行意图。
 *
 * <p>测试意图：产品语义要求「暂停/阻塞/归档的 Issue 可以继续记录回答与审批，但不再开始新的模型/工具执行」，并且「正在进入下一状态时只允许模型
 * 收尾与只读工具」。这里守住三件事：一是判定链路的每一道门（归属、Issue/Project 事实、阶段职责与 Agent、活动 Run 的
 * Thread/Session/阶段坐标、收尾阶段的工具只读性），任何一项无法判定都必须拒绝；二是拒绝时绝不执行意图（调用方的 Harness 状态转换不会被写入）；三是 锁序固定为
 * Project FOR SHARE -&gt; Issue FOR UPDATE -&gt; 活动 Run FOR UPDATE，与生产写路径一致，不会产生 Thread -&gt; Issue
 * 的反向锁序。在途执行的观察/恢复/取消不经过本门禁（由 Processor 结构性放行，见 {@code
 * HarnessWorkDispatcherAdmissionTest}），因此暂停不会卡住在途收尾。
 */
class IssueAgentWorkDispatchAdmissionTest {

  private static final UUID PROJECT_ID = new UUID(0L, 1L);
  private static final UUID ISSUE_ID = new UUID(0L, 2L);
  private static final UUID THREAD_ID = new UUID(0L, 3L);
  private static final UUID SESSION_ID = new UUID(0L, 4L);
  private static final UUID RUN_ID = new UUID(0L, 5L);
  private static final UUID MODEL_INVOCATION_ID = new UUID(0L, 6L);
  private static final UUID TOOL_INVOCATION_ID = new UUID(0L, 7L);
  private static final UUID CHAT_THREAD_ID = new UUID(0L, 8L);

  /** INIT → DESIGN(designer, 3 runs) → REVIEW(reviewer) → DONE 的严格 workflow。 */
  private static final String WORKFLOW_JSON =
      "{"
          + "\"states\":["
          + "{\"state\":\"INIT\",\"name\":\"待开始\",\"next\":[\"DESIGN\"]},"
          + "{\"state\":\"DESIGN\",\"name\":\"设计\",\"agent\":\"designer\",\"maxRuns\":3,"
          + "\"next\":[\"REVIEW\"]},"
          + "{\"state\":\"REVIEW\",\"name\":\"检查\",\"agent\":\"reviewer\",\"maxRuns\":1,"
          + "\"next\":[\"DONE\"]},"
          + "{\"state\":\"BLOCKED\",\"name\":\"业务阻塞\"},"
          + "{\"state\":\"DONE\",\"name\":\"完成\"}"
          + "]}";

  private final IssueAgentThreadRepository threads = mock(IssueAgentThreadRepository.class);
  private final IssueRepository issues = mock(IssueRepository.class);
  private final ProjectRepository projects = mock(ProjectRepository.class);
  private final IssueRunRepository runs = mock(IssueRunRepository.class);
  private final IssueAgentWorkDispatchAdmission admission =
      new IssueAgentWorkDispatchAdmission(
          threads, issues, projects, runs, new ProjectWorkflowJsonCodec());
  private final AtomicInteger executedIntents = new AtomicInteger();

  private boolean bound = true;
  private Issue issue = issue("DESIGN");
  private Project project = project(WORKFLOW_JSON);
  private IssueRun run = activeRun("DESIGN", null);

  IssueAgentWorkDispatchAdmissionTest() {
    when(threads.findByThreadId(THREAD_ID))
        .thenAnswer(
            invocation -> bound ? new IssueAgentThread(ISSUE_ID, "designer", THREAD_ID) : null);
    when(issues.getById(ISSUE_ID)).thenAnswer(invocation -> issue);
    when(issues.lockById(ISSUE_ID)).thenAnswer(invocation -> issue);
    when(projects.lockForShare(PROJECT_ID)).thenAnswer(invocation -> project);
    when(runs.lockActiveByIssueId(ISSUE_ID)).thenAnswer(invocation -> run);
  }

  private static Issue issue(String state) {
    return Issue.builder()
        .id(ISSUE_ID)
        .projectId(PROJECT_ID)
        .number(12L)
        .title("修复渲染缺陷")
        .state(state)
        .build();
  }

  private static Project project(String workflowJson) {
    return Project.builder().id(PROJECT_ID).workflowJson(workflowJson).build();
  }

  private static IssueRun activeRun(String state, String nextState) {
    return IssueRun.builder()
        .id(RUN_ID)
        .issueId(ISSUE_ID)
        .ordinal(1L)
        .state(state)
        .sessionId(SESSION_ID)
        .threadId(THREAD_ID)
        .status(IssueRunStatus.RUNNING)
        .nextState(nextState)
        .version(1L)
        .build();
  }

  private static WorkDispatchRequest modelRequest() {
    return new WorkDispatchRequest(
        WorkTargetType.MODEL, MODEL_INVOCATION_ID, THREAD_ID, SESSION_ID, null);
  }

  private static WorkDispatchRequest modelRequestOf(UUID threadId) {
    return new WorkDispatchRequest(
        WorkTargetType.MODEL, MODEL_INVOCATION_ID, threadId, SESSION_ID, null);
  }

  private static WorkDispatchRequest toolRequest(ToolSideEffect sideEffect) {
    return new WorkDispatchRequest(
        WorkTargetType.TOOL, TOOL_INVOCATION_ID, THREAD_ID, SESSION_ID, toolBinding(sideEffect));
  }

  private static ToolBinding toolBinding(ToolSideEffect sideEffect) {
    ToolDescriptor descriptor =
        new ToolDescriptor(
            "bash",
            "run a command",
            "bash",
            new InputSchema("arguments", Map.of(), Set.of(), false),
            sideEffect,
            Duration.ofSeconds(30));
    return new ToolBinding(
        new AgentToolDefinition(descriptor, ToolVisibility.SELECTABLE),
        new ContributorBinding("test", "bash", List.of()),
        EnvironmentSupport.NONE,
        null,
        null);
  }

  /** 执行一次派发意图，并记录意图是否真的被宿主放行执行。 */
  private boolean admitted(WorkDispatchRequest request) {
    Optional<Boolean> outcome =
        admission.executeIfAdmitted(
            request,
            () -> {
              executedIntents.incrementAndGet();
              return Boolean.TRUE;
            });
    return outcome.orElse(Boolean.FALSE);
  }

  @Test
  void admitsNewExecutionForTheActiveRunCoordinatesAndTakesProductLocksFirst() {
    assertTrue(admitted(modelRequest()));
    assertTrue(admitted(toolRequest(ToolSideEffect.IDEMPOTENT)));
    assertEquals(2, executedIntents.get());

    // 锁序必须是 Project FOR SHARE -> Issue FOR UPDATE -> 活动 Run FOR UPDATE：与生产写路径一致。
    InOrder order = inOrder(projects, issues, runs);
    order.verify(projects).lockForShare(PROJECT_ID);
    order.verify(issues).lockById(ISSUE_ID);
    order.verify(runs).lockActiveByIssueId(ISSUE_ID);
  }

  @Test
  void admitsThreadsWithoutIssueBinding() {
    // Chat 与内部委派 Thread 没有产品暂停语义，不受门禁影响，也不读任何产品事实。
    bound = false;
    assertTrue(admitted(modelRequestOf(CHAT_THREAD_ID)));
    assertTrue(
        admitted(
            new WorkDispatchRequest(
                WorkTargetType.TOOL,
                TOOL_INVOCATION_ID,
                CHAT_THREAD_ID,
                SESSION_ID,
                toolBinding(ToolSideEffect.NON_IDEMPOTENT))));
    assertEquals(2, executedIntents.get());
    verifyNoInteractions(issues);
    verifyNoInteractions(projects);
    verifyNoInteractions(runs);
  }

  @Test
  void rejectsExecutionWhileIssueIsPausedBlockedArchivedOrInReservedState() {
    // 控制暂停（人工停止/失败/不明）：只记录、不派发。
    issue =
        Issue.builder()
            .id(ISSUE_ID)
            .projectId(PROJECT_ID)
            .state("DESIGN")
            .pauseReason("USER")
            .pauseDetail("manual stop")
            .build();
    assertFalse(admitted(modelRequest()));
    assertFalse(admitted(toolRequest(ToolSideEffect.READ_ONLY)));
    // 业务 BLOCKED：等人工解除后同一 Work 才能继续。
    issue = issue("BLOCKED");
    assertFalse(admitted(modelRequest()));
    // 归档 Issue。
    issue =
        Issue.builder()
            .id(ISSUE_ID)
            .projectId(PROJECT_ID)
            .state("DESIGN")
            .archivedAt(Instant.parse("2026-07-01T00:00:00Z"))
            .build();
    assertFalse(admitted(modelRequest()));
    // 归档 Project。
    issue = issue("DESIGN");
    project =
        Project.builder()
            .id(PROJECT_ID)
            .workflowJson(WORKFLOW_JSON)
            .archivedAt(Instant.parse("2026-07-01T00:00:00Z"))
            .build();
    assertFalse(admitted(modelRequest()));
    // 保留状态不是工作阶段：INIT 还没开始、DONE 已结束。
    project = project(WORKFLOW_JSON);
    issue = issue("INIT");
    assertFalse(admitted(modelRequest()));
    issue = issue("DONE");
    assertFalse(admitted(modelRequest()));
    assertEquals(0, executedIntents.get());
  }

  @Test
  void rejectsWhenFactsAreMissingOrInconsistent() {
    // 绑定存在但 Issue/Project 行缺失：归属事实损坏，fail closed。
    issue = null;
    assertFalse(admitted(modelRequest()));
    issue = issue("DESIGN");
    project = null;
    assertFalse(admitted(modelRequest()));
    // workflow 无法解码或阶段未定义：无法判定职责，fail closed。
    project = project("{\"states\":[]}");
    assertFalse(admitted(modelRequest()));
    project = project(WORKFLOW_JSON);
    issue = issue("UNKNOWN_STAGE");
    assertFalse(admitted(modelRequest()));
    assertEquals(0, executedIntents.get());
  }

  @Test
  void rejectsThreadsThatAreNotTheActiveRunCoordinates() {
    // 没有活动 Run：任何新执行都不成立。
    run = null;
    assertFalse(admitted(modelRequest()));
    // Run 已不是 active。
    run = activeRun("DESIGN", null);
    run.setStatus(IssueRunStatus.COMPLETED);
    assertFalse(admitted(modelRequest()));
    // 活动 Run 属于别的 Thread。
    run = activeRun("DESIGN", null);
    run.setThreadId(CHAT_THREAD_ID);
    assertFalse(admitted(modelRequest()));
    // 活动 Run 属于别的 Session。
    run = activeRun("DESIGN", null);
    run.setSessionId(CHAT_THREAD_ID);
    assertFalse(admitted(modelRequest()));
    // 活动 Run 属于别的阶段：旧阶段的迟到执行不借当前权限。
    run = activeRun("REVIEW", null);
    assertFalse(admitted(modelRequest()));
    assertEquals(0, executedIntents.get());
  }

  @Test
  void rejectsWhenTheStageIsNoLongerAssignedToTheBoundAgent() {
    // 阶段改派给别的 Agent 后，旧 Thread 不能继续以旧职责开始新的执行。
    issue = issue("REVIEW");
    run = activeRun("REVIEW", null);
    assertFalse(admitted(modelRequest()));
    assertEquals(0, executedIntents.get());
  }

  @Test
  void forbidsBusinessToolWritesWhileEnteringTheNextStateButAllowsModelAndReadOnlyTools() {
    // 活动 Run 已登记 nextState：本 Run 进入收尾——模型可以收尾，只读工具可以继续观察，业务写工具不得再开始。
    run = activeRun("DESIGN", "REVIEW");
    assertTrue(admitted(modelRequest()));
    assertTrue(admitted(toolRequest(ToolSideEffect.READ_ONLY)));
    assertFalse(admitted(toolRequest(ToolSideEffect.IDEMPOTENT)));
    assertFalse(admitted(toolRequest(ToolSideEffect.NON_IDEMPOTENT)));
    assertEquals(2, executedIntents.get());
  }

  @Test
  void rejectsNullArguments() {
    assertThrows(
        NullPointerException.class, () -> admission.executeIfAdmitted(null, () -> Boolean.TRUE));
    assertThrows(
        NullPointerException.class, () -> admission.executeIfAdmitted(modelRequest(), null));
    assertEquals(0, executedIntents.get());
  }
}
