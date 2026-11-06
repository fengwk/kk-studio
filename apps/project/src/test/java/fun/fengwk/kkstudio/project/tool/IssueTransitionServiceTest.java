package fun.fengwk.kkstudio.project.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.harness.api.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.api.entry.ExecutionSelection;
import fun.fengwk.kkstudio.harness.api.entry.TurnEndOutcome;
import fun.fengwk.kkstudio.harness.api.entry.TurnStartReason;
import fun.fengwk.kkstudio.harness.api.history.Entry;
import fun.fengwk.kkstudio.harness.api.history.EntryPath;
import fun.fengwk.kkstudio.harness.api.history.EntryPayload;
import fun.fengwk.kkstudio.harness.api.history.MessagePayload;
import fun.fengwk.kkstudio.harness.api.history.RootPayload;
import fun.fengwk.kkstudio.harness.api.history.TurnEndPayload;
import fun.fengwk.kkstudio.harness.api.history.TurnStartPayload;
import fun.fengwk.kkstudio.harness.api.session.AgentMessage;
import fun.fengwk.kkstudio.harness.api.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.api.session.AssistantMessageMetadata;
import fun.fengwk.kkstudio.harness.api.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.api.thread.ThreadExecutionFacts;
import fun.fengwk.kkstudio.harness.model.ModelUsage;
import fun.fengwk.kkstudio.harness.model.provider.GenerationStopReason;
import fun.fengwk.kkstudio.project.domain.IssueRunStatus;
import fun.fengwk.kkstudio.project.domain.ProjectWorkflowJsonCodec;
import fun.fengwk.kkstudio.project.model.Issue;
import fun.fengwk.kkstudio.project.model.IssueAgentThread;
import fun.fengwk.kkstudio.project.model.IssueRun;
import fun.fengwk.kkstudio.project.model.Project;
import fun.fengwk.kkstudio.project.platform.IssueThreadExecution;
import fun.fengwk.kkstudio.project.platform.IssueThreadView;
import fun.fengwk.kkstudio.project.repo.IssueAgentThreadRepository;
import fun.fengwk.kkstudio.project.repo.IssueRepository;
import fun.fengwk.kkstudio.project.repo.IssueRunRepository;
import fun.fengwk.kkstudio.project.repo.ProjectRepository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * {@link IssueTransitionService} 的交接登记契约：调用 Thread 只用来定位 Project 自己的稳定绑定与活动 Run，真实授权只依据数据库中的活动 Run
 * 与该 Thread 的真实历史位置；同目标幂等、异目标与非法边拒绝，旧 Run 的迟到调用、兄弟分支、fork 与已闭合 Run 都在写前失败关闭。
 */
class IssueTransitionServiceTest {

  private static final UUID THREAD_ID = new UUID(0L, 1L);
  private static final UUID OTHER_THREAD_ID = new UUID(0L, 77L);
  private static final UUID SESSION_ID = new UUID(0L, 2L);
  private static final UUID ISSUE_ID = new UUID(0L, 3L);
  private static final UUID PROJECT_ID = new UUID(0L, 4L);
  private static final UUID RUN_ID = new UUID(0L, 5L);
  private static final UUID START_ENTRY_ID = new UUID(0L, 6L);
  private static final UUID ASSISTANT_ENTRY_ID = new UUID(0L, 7L);
  private static final UUID LATER_ENTRY_ID = new UUID(0L, 8L);
  private static final long RUN_VERSION = 7L;

  /**
   * INIT → DESIGN(agent，可正常交接 REVISE 或 REVIEW) → REVISE(agent) → REVIEW(agent) → DONE；BLOCKED
   * 只能由专用操作进入。
   *
   * <p>DESIGN 声明两个合法目标，用于验证「已接受一个目标后拒绝另一个合法目标」的冲突语义。
   */
  private static final String WORKFLOW_JSON =
      "{"
          + "\"states\":["
          + "{\"state\":\"INIT\",\"name\":\"待开始\",\"next\":[\"DESIGN\"]},"
          + "{\"state\":\"DESIGN\",\"name\":\"设计\",\"agent\":\"designer\",\"maxRuns\":3,"
          + "\"next\":[\"REVISE\",\"REVIEW\"]},"
          + "{\"state\":\"REVISE\",\"name\":\"返工\",\"agent\":\"designer\",\"maxRuns\":3,"
          + "\"next\":[\"REVIEW\"]},"
          + "{\"state\":\"REVIEW\",\"name\":\"检查\",\"agent\":\"reviewer\",\"maxRuns\":1,"
          + "\"next\":[\"DONE\"]},"
          + "{\"state\":\"BLOCKED\",\"name\":\"业务阻塞\"},"
          + "{\"state\":\"DONE\",\"name\":\"完成\"}"
          + "]}";

  private final ProjectRepository projects = mock(ProjectRepository.class);
  private final IssueRepository issues = mock(IssueRepository.class);
  private final IssueRunRepository runs = mock(IssueRunRepository.class);
  private final IssueAgentThreadRepository bindings = mock(IssueAgentThreadRepository.class);
  private final IssueThreadExecution threadExecutions = mock(IssueThreadExecution.class);
  private final IssueTransitionService service =
      new IssueTransitionService(
          projects, issues, runs, bindings, threadExecutions, new ProjectWorkflowJsonCodec());

  private IssueRun run;

  IssueTransitionServiceTest() {
    useIssue("DESIGN");
    when(projects.lockForKeyShare(PROJECT_ID)).thenReturn(project());
    when(bindings.findByThreadId(THREAD_ID))
        .thenReturn(new IssueAgentThread(ISSUE_ID, "designer", PROJECT_ID, THREAD_ID));
    run = activeRun(null);
    when(runs.getActiveByIssueId(ISSUE_ID)).thenReturn(run);
    when(runs.lockById(RUN_ID)).thenReturn(run);
    when(runs.updateById(any(), anyLong())).thenReturn(true);
    useThreadHistory(START_ENTRY_ID, ASSISTANT_ENTRY_ID, LATER_ENTRY_ID);
  }

  private void useIssue(String state) {
    Issue issue = Issue.builder().id(ISSUE_ID).projectId(PROJECT_ID).state(state).build();
    when(issues.getById(ISSUE_ID)).thenReturn(issue);
    when(issues.lockById(ISSUE_ID)).thenReturn(issue);
  }

  private static Project project() {
    return Project.builder().id(PROJECT_ID).workflowJson(WORKFLOW_JSON).build();
  }

  private IssueRun activeRun(String nextState) {
    return IssueRun.builder()
        .id(RUN_ID)
        .issueId(ISSUE_ID)
        .state("DESIGN")
        .sessionId(SESSION_ID)
        .threadId(THREAD_ID)
        .status(IssueRunStatus.RUNNING)
        .startEntryId(START_ENTRY_ID)
        .nextState(nextState)
        .version(RUN_VERSION)
        .startedAt(Instant.now())
        .build();
  }

  /** 该 Thread 的 root-to-head 路径：调用方只能从真实历史位置证明自己是本 Run 的 assistant turn。 */
  private void useThreadHistory(UUID... entryIds) {
    List<Entry> entries = new ArrayList<>();
    BranchSettings settings =
        new BranchSettings("designer", new ExecutionSelection("fake", "{}"), null);
    entries.add(entry(entryIds[0], null, new RootPayload(settings)));
    for (int i = 1; i < entryIds.length; i++) {
      UUID turn = UUID.randomUUID();
      UUID input = UUID.randomUUID();
      entries.add(
          entry(
              turn,
              entries.getLast().id(),
              new TurnStartPayload(TurnStartReason.INPUT, settings, THREAD_ID)));
      entries.add(entry(input, turn, message(AgentMessageRole.USER)));
      entries.add(entry(entryIds[i], input, message(AgentMessageRole.ASSISTANT)));
      if (i + 1 < entryIds.length) {
        entries.add(
            entry(
                UUID.randomUUID(),
                entryIds[i],
                new TurnEndPayload(turn, TurnEndOutcome.COMPLETED, false, null, null)));
      }
    }
    usePath(new EntryPath(entries));
  }

  private static Entry entry(UUID id, UUID parent, EntryPayload payload) {
    return new Entry(id, SESSION_ID, parent, payload, Instant.now());
  }

  private static MessagePayload message(AgentMessageRole role) {
    return new MessagePayload(
        new AgentMessage(role, List.of(new TextMessageContent("test"))),
        role == AgentMessageRole.ASSISTANT
            ? new AssistantMessageMetadata(
                GenerationStopReason.COMPLETE, new ModelUsage(1L, 1L, 0L, 0L, Map.of(), 0L, 2L))
            : null,
        null);
  }

  private void usePath(EntryPath path) {
    List<Entry> entries = path.entries();
    UUID headId = entries.getLast().id();
    when(threadExecutions.readThread(THREAD_ID))
        .thenReturn(
            new IssueThreadView(
                SESSION_ID, headId, 1L, 1L, path, new ThreadExecutionFacts(false, false, false)));
  }

  /** 旧 Turn 的 assistant 即使在新 Run 起点之后落库，也不能借用新 Run。 */
  @Test
  void rejectsAssistantMaterializedAfterRunStartFromOldTurn() {
    UUID root = UUID.randomUUID();
    UUID turn = UUID.randomUUID();
    BranchSettings settings =
        new BranchSettings("designer", new ExecutionSelection("fake", "{}"), null);
    usePath(
        new EntryPath(
            List.of(
                entry(root, null, new RootPayload(settings)),
                entry(turn, root, new TurnStartPayload(TurnStartReason.INPUT, settings, THREAD_ID)),
                entry(START_ENTRY_ID, turn, message(AgentMessageRole.USER)),
                entry(ASSISTANT_ENTRY_ID, START_ENTRY_ID, message(AgentMessageRole.ASSISTANT)))));
    assertRejected(ASSISTANT_ENTRY_ID, "REVIEW", "assistant turn of the active run");
    assertEquals(null, run.getNextState());
    verify(runs, never()).updateById(any(), anyLong());
  }

  /** 断言一次交接被确定性拒绝，并且拒绝原因包含预期片段。 */
  private void assertRejected(
      UUID threadId, UUID assistantEntryId, String toState, String expected) {
    Runnable call = () -> service.accept(threadId, assistantEntryId, toState);
    DomainException rejected = assertThrows(DomainException.class, call::run);
    assertTrue(rejected.getMessage().contains(expected), rejected.getMessage());
  }

  private void assertRejected(UUID assistantEntryId, String toState, String expected) {
    assertRejected(THREAD_ID, assistantEntryId, toState, expected);
  }

  /** 测试意图：合法交接只登记 Run 的 next_state —— Issue 阶段与 Issue 行都不在本调用中被改写。 */
  @Test
  void acceptsLegalHandoffWithoutChangingTheIssueStage() {
    IssueTransitionService.IssueTransitionResult result =
        service.accept(THREAD_ID, ASSISTANT_ENTRY_ID, "REVIEW");

    assertEquals("DESIGN", result.fromState());
    assertEquals("REVIEW", result.toState());
    assertEquals(RUN_ID, result.runId());
    assertFalse(result.replayed());
    assertEquals("REVIEW", run.getNextState());
    assertEquals("DESIGN", run.getState());
    verify(runs).updateById(run, RUN_VERSION);
    verify(issues, never()).updateById(any(), anyLong());
  }

  /** 测试意图：同一目标的重复调用是丢响应后的幂等重放，不重复写行、不推进版本。 */
  @Test
  void replaysSameTargetWithoutWriting() {
    service.accept(THREAD_ID, ASSISTANT_ENTRY_ID, "REVIEW");

    IssueTransitionService.IssueTransitionResult replay =
        service.accept(THREAD_ID, LATER_ENTRY_ID, "REVIEW");

    assertTrue(replay.replayed());
    assertEquals("REVIEW", replay.toState());
    verify(runs).updateById(run, RUN_VERSION);
  }

  /** 测试意图：已接受目标后不接受第二个不同目标，避免收尾时出现两个互相矛盾的交接意图。 */
  @Test
  void rejectsDifferentTargetAfterAcceptance() {
    service.accept(THREAD_ID, ASSISTANT_ENTRY_ID, "REVISE");

    // REVISE 与 REVIEW 都是 DESIGN 的合法目标，但已接受一个后必须拒绝另一个。
    assertRejected(LATER_ENTRY_ID, "REVIEW", "already accepted");
    assertEquals("REVISE", run.getNextState());
  }

  /** 测试意图：目标必须是当前阶段 workflow {@code next} 白名单中的启用阶段；保留态不能被交接绕过。 */
  @Test
  void rejectsTargetOutsideCurrentStageNextList() {
    assertRejected(ASSISTANT_ENTRY_ID, "DONE", "cannot transition to DONE");
    assertRejected(ASSISTANT_ENTRY_ID, "BLOCKED", "cannot transition to BLOCKED");
    assertRejected(ASSISTANT_ENTRY_ID, "INIT", "cannot transition to INIT");
    assertRejected(ASSISTANT_ENTRY_ID, "design", "invalid to_state: design");
    assertRejected(ASSISTANT_ENTRY_ID, "  ", "non-blank");
    verify(runs, never()).updateById(any(), anyLong());
  }

  /** 测试意图：没有任何稳定绑定的 Thread（别的 Session 或 fork 出的分支）不能借用交接权限。 */
  @Test
  void rejectsThreadWithoutStableBinding() {
    assertRejected(OTHER_THREAD_ID, ASSISTANT_ENTRY_ID, "REVIEW", "bound to an Issue agent");
    verify(threadExecutions, never()).readThread(any());
    verify(runs, never()).updateById(any(), anyLong());
  }

  /** 测试意图：Run 的 Thread 才是执行身份——绑定存在但活动 Run 属于别的 Thread 时在写前拒绝。 */
  @Test
  void rejectsActiveRunOfAnotherThread() {
    run.setThreadId(OTHER_THREAD_ID);
    when(runs.lockById(RUN_ID)).thenReturn(run);

    assertRejected(ASSISTANT_ENTRY_ID, "REVIEW", "different thread");
    verify(runs, never()).updateById(any(), anyLong());
  }

  /**
   * 测试意图：同一 Thread 会被后续 Run 复用，旧 Run 的 assistant turn 迟到落库时不得获得新 Run 的写权限——assistant 入口必须严格晚于 活动
   * Run 的 startEntryId。
   */
  @Test
  void rejectsStaleAssistantTurnOfTheSameThread() {
    // 后续 Run 已在本 Thread 上从 LATER_ENTRY_ID 开始，旧 assistant 入口更早，因此是迟到调用。
    run.setStartEntryId(LATER_ENTRY_ID);
    when(runs.lockById(RUN_ID)).thenReturn(run);
    useThreadHistory(START_ENTRY_ID, ASSISTANT_ENTRY_ID, LATER_ENTRY_ID);

    assertRejected(ASSISTANT_ENTRY_ID, "REVIEW", "assistant turn of the active run");
    verify(runs, never()).updateById(any(), anyLong());
  }

  /** 测试意图：assistant 入口必须落在该 Run 起点之后的路径上，起点本身不能当成一次合法调用。 */
  @Test
  void rejectsAssistantTurnAtTheRunStart() {
    useThreadHistory(START_ENTRY_ID, LATER_ENTRY_ID);

    assertRejected(START_ENTRY_ID, "REVIEW", "assistant turn of the active run");
    verify(runs, never()).updateById(any(), anyLong());
  }

  /** 测试意图：不在当前 root-to-head 路径上的 assistant 入口（兄弟分支或 fork 继承）不能被当成调用归属。 */
  @Test
  void rejectsAssistantTurnOutsideTheCurrentPath() {
    useThreadHistory(START_ENTRY_ID, LATER_ENTRY_ID);

    assertRejected(ASSISTANT_ENTRY_ID, "REVIEW", "assistant turn of the active run");
    verify(runs, never()).updateById(any(), anyLong());
  }

  /** 测试意图：活动 Run 的起点不在该 Thread 当前路径上是数据不一致，必须原样传播而不是当成业务拒绝。 */
  @Test
  void throwsWhenTheRunStartIsNotOnTheThreadPath() {
    useThreadHistory(ASSISTANT_ENTRY_ID, LATER_ENTRY_ID);

    IllegalStateException inconsistent =
        assertThrows(
            IllegalStateException.class, () -> service.accept(THREAD_ID, LATER_ENTRY_ID, "REVIEW"));
    assertEquals("Project thread ownership is inconsistent", inconsistent.getMessage());
    verify(runs, never()).updateById(any(), anyLong());
  }

  /** 测试意图：终态或无活动 Run 的 Issue 不接受交接（旧 Run 的迟到调用不借新权限）。 */
  @Test
  void rejectsWhenThereIsNoActiveRun() {
    when(runs.getActiveByIssueId(ISSUE_ID)).thenReturn(null);

    assertRejected(ASSISTANT_ENTRY_ID, "REVIEW", "no active run");
    verify(threadExecutions, never()).readThread(any());
    verify(runs, never()).updateById(any(), anyLong());
  }

  /** 测试意图：锁内发现活动 Run 已闭合（并发收尾获胜）时按"没有活动 Run"拒绝，不继续写入交接。 */
  @Test
  void rejectsRunClosedBetweenLookupAndLock() {
    IssueRun terminal = activeRun(null);
    terminal.setStatus(IssueRunStatus.COMPLETED);
    when(runs.lockById(RUN_ID)).thenReturn(terminal);

    assertRejected(ASSISTANT_ENTRY_ID, "REVIEW", "no active run");
    verify(runs, never()).updateById(any(), anyLong());
  }

  /** 测试意图：活动 Run 的阶段与当前 Issue 阶段不一致时不接受交接。 */
  @Test
  void rejectsStageMismatchBetweenRunAndIssue() {
    run.setState("REVIEW");
    when(runs.lockById(RUN_ID)).thenReturn(run);

    assertRejected(ASSISTANT_ENTRY_ID, "REVIEW", "does not match the current issue stage");
    verify(runs, never()).updateById(any(), anyLong());
  }

  /** 测试意图：Issue 不在可执行工作阶段（保留态）时没有可交接的阶段。 */
  @Test
  void rejectsIssueWithoutWorkStage() {
    useIssue("INIT");
    run.setState("INIT");
    when(runs.lockById(RUN_ID)).thenReturn(run);

    assertRejected(ASSISTANT_ENTRY_ID, "DESIGN", "not in an executable work stage: INIT");
    verify(runs, never()).updateById(any(), anyLong());
  }

  /** 损坏的 Issue 阶段不得绕过 workflow 校验或写入交接目标。 */
  @Test
  void rejectsInvalidIssueState() {
    useIssue("invalid state");

    assertRejected(ASSISTANT_ENTRY_ID, "REVIEW", "invalid project workflow or issue state");
    verify(runs, never()).updateById(any(), anyLong());
  }

  /** 测试意图：版本 CAS 竞争失败必须整体失败，绝不留下"已经接受"的部分事实。 */
  @Test
  void failsWhenVersionCasLoses() {
    when(runs.updateById(any(), anyLong())).thenReturn(false);

    DomainException conflict =
        assertThrows(
            DomainException.class, () -> service.accept(THREAD_ID, ASSISTANT_ENTRY_ID, "REVIEW"));
    assertEquals("issue_run", conflict.resource());
  }

  /** 测试意图：绑定存在但 Issue/Project 行缺失是数据不一致，原样传播而不是当成业务拒绝。 */
  @Test
  void throwsWhenOwnedRowsAreMissing() {
    when(issues.getById(ISSUE_ID)).thenReturn(null);

    IllegalStateException missing =
        assertThrows(
            IllegalStateException.class,
            () -> service.accept(THREAD_ID, ASSISTANT_ENTRY_ID, "REVIEW"));
    assertEquals("Project thread ownership is inconsistent", missing.getMessage());
  }
}
