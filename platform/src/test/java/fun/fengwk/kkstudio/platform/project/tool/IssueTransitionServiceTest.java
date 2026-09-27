package fun.fengwk.kkstudio.platform.project.tool;

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

import fun.fengwk.kkstudio.platform.error.AiValidationException;
import fun.fengwk.kkstudio.platform.error.AiVersionConflictException;
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
import java.util.UUID;

/**
 * {@link IssueTransitionService} 的交接登记契约：只把目标写入活动 Run 的 {@code next_state}，同目标幂等、异目标与非法边拒绝，
 * 身份/门禁不满足时在写前失败关闭。
 */
class IssueTransitionServiceTest {

  private static final UUID THREAD_ID = new UUID(0L, 1L);
  private static final UUID SESSION_ID = new UUID(0L, 2L);
  private static final UUID ISSUE_ID = new UUID(0L, 3L);
  private static final UUID PROJECT_ID = new UUID(0L, 4L);
  private static final UUID RUN_ID = new UUID(0L, 5L);
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

  private final IssueAgentThreadRepository bindings = mock(IssueAgentThreadRepository.class);
  private final ProjectRepository projects = mock(ProjectRepository.class);
  private final IssueRepository issues = mock(IssueRepository.class);
  private final IssueRunRepository runs = mock(IssueRunRepository.class);
  private final IssueTransitionService service =
      new IssueTransitionService(bindings, projects, issues, runs, new ProjectWorkflowJsonCodec());

  private IssueRun run;

  IssueTransitionServiceTest() {
    useIssue("DESIGN");
    when(bindings.findByThreadId(THREAD_ID))
        .thenReturn(new IssueAgentThread(ISSUE_ID, "designer", THREAD_ID));
    when(projects.lockForKeyShare(PROJECT_ID)).thenReturn(project());
    run = activeRun(null);
    when(runs.lockActiveByIssueId(ISSUE_ID)).thenReturn(run);
    when(runs.updateById(any(), anyLong())).thenReturn(true);
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
        .nextState(nextState)
        .version(RUN_VERSION)
        .startedAt(Instant.now())
        .build();
  }

  private AiValidationException reject(String toState) {
    return assertThrows(AiValidationException.class, () -> service.accept(THREAD_ID, toState));
  }

  /** 测试意图：合法交接只登记 Run 的 next_state —— Issue 阶段与 Issue 行都不在本调用中被改写。 */
  @Test
  void acceptsLegalHandoffWithoutChangingTheIssueStage() {
    IssueTransitionService.IssueTransitionResult result = service.accept(THREAD_ID, "REVIEW");

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
    service.accept(THREAD_ID, "REVIEW");

    IssueTransitionService.IssueTransitionResult replay = service.accept(THREAD_ID, "REVIEW");

    assertTrue(replay.replayed());
    assertEquals("REVIEW", replay.toState());
    verify(runs).updateById(run, RUN_VERSION);
  }

  /** 测试意图：已接受目标后不接受第二个不同目标，避免收尾时出现两个互相矛盾的交接意图。 */
  @Test
  void rejectsDifferentTargetAfterAcceptance() {
    service.accept(THREAD_ID, "REVISE");

    // REVISE 与 REVIEW 都是 DESIGN 的合法目标，但已接受一个后必须拒绝另一个，避免出现两个互相矛盾的交接意图。
    assertTrue(reject("REVIEW").getMessage().contains("already accepted"));
    assertEquals("REVISE", run.getNextState());
  }

  /** 测试意图：目标必须是当前阶段 workflow {@code next} 白名单中的启用阶段；BLOCKED/INIT 等保留态不能被交接绕过。 */
  @Test
  void rejectsTargetOutsideCurrentStageNextList() {
    assertTrue(reject("DONE").getMessage().contains("cannot transition to DONE"));
    assertTrue(reject("BLOCKED").getMessage().contains("cannot transition to BLOCKED"));
    assertTrue(reject("INIT").getMessage().contains("cannot transition to INIT"));
    assertTrue(reject("design").getMessage().contains("invalid to_state: design"));
    assertTrue(reject("  ").getMessage().contains("non-blank"));
    verify(runs, never()).updateById(any(), anyLong());
  }

  /** 测试意图：交接只能由绑定该 Issue+Agent 的 Thread 发起。 */
  @Test
  void rejectsUnboundThread() {
    UUID unbound = new UUID(0L, 99L);
    assertTrue(
        assertThrows(AiValidationException.class, () -> service.accept(unbound, "REVIEW"))
            .getMessage()
            .contains("only available on an issue agent thread"));
  }

  /** 测试意图：归档或控制暂停的 Issue 不接受新的交接意图；暂停期间保留既有目标由收尾路径处理。 */
  @Test
  void rejectsArchivedOrPausedIssue() {
    Issue archived = Issue.builder().id(ISSUE_ID).projectId(PROJECT_ID).state("DESIGN").build();
    archived.setArchivedAt(Instant.now());
    when(issues.lockById(ISSUE_ID)).thenReturn(archived);
    assertEquals("Issue is archived", reject("REVIEW").getMessage());

    Issue paused = Issue.builder().id(ISSUE_ID).projectId(PROJECT_ID).state("DESIGN").build();
    paused.setPauseReason("USER");
    when(issues.lockById(ISSUE_ID)).thenReturn(paused);
    assertTrue(reject("REVIEW").getMessage().contains("Issue is paused"));
    verify(runs, never()).updateById(any(), anyLong());
  }

  /** 测试意图：Issue 不在可执行工作阶段（保留态）时没有可交接的阶段。 */
  @Test
  void rejectsIssueWithoutWorkStage() {
    useIssue("INIT");
    when(runs.lockActiveByIssueId(ISSUE_ID)).thenReturn(run);

    assertTrue(reject("DESIGN").getMessage().contains("not in an executable work stage: INIT"));
    verify(runs, never()).updateById(any(), anyLong());
  }

  /** 测试意图：终态或无活动 Run 的 Issue 不接受交接（旧 Run 的迟到调用不借新权限）。 */
  @Test
  void rejectsWhenThereIsNoActiveRun() {
    when(runs.lockActiveByIssueId(ISSUE_ID)).thenReturn(null);
    assertTrue(reject("REVIEW").getMessage().contains("no active run"));

    IssueRun terminal = activeRun(null);
    terminal.setStatus(IssueRunStatus.COMPLETED);
    when(runs.lockActiveByIssueId(ISSUE_ID)).thenReturn(terminal);
    assertTrue(reject("REVIEW").getMessage().contains("no active run"));
    verify(runs, never()).updateById(any(), anyLong());
  }

  /** 测试意图：活动 Run 属于别的 Thread 或阶段与 Issue 不一致时，调用方不是当前执行身份，交接必须拒绝。 */
  @Test
  void rejectsForeignOrStaleRunIdentity() {
    IssueRun otherThread = activeRun(null);
    otherThread.setThreadId(new UUID(0L, 77L));
    when(runs.lockActiveByIssueId(ISSUE_ID)).thenReturn(otherThread);
    assertTrue(reject("REVIEW").getMessage().contains("different thread"));

    IssueRun staleState = activeRun(null);
    staleState.setState("REVIEW");
    when(runs.lockActiveByIssueId(ISSUE_ID)).thenReturn(staleState);
    assertTrue(reject("REVIEW").getMessage().contains("does not match the current issue stage"));
    verify(runs, never()).updateById(any(), anyLong());
  }

  /** 测试意图：版本 CAS 竞争失败必须整体失败，绝不留下"已经接受"的部分事实。 */
  @Test
  void failsWhenVersionCasLoses() {
    when(runs.updateById(any(), anyLong())).thenReturn(false);

    AiVersionConflictException conflict =
        assertThrows(AiVersionConflictException.class, () -> service.accept(THREAD_ID, "REVIEW"));
    assertEquals("issue_run", conflict.resource());
  }

  /** 测试意图：归属存在但 Issue/Project 行缺失是数据不一致，原样传播而不是当成业务拒绝。 */
  @Test
  void throwsWhenOwnedRowsAreMissing() {
    when(issues.getById(ISSUE_ID)).thenReturn(null);
    IllegalStateException missing =
        assertThrows(IllegalStateException.class, () -> service.accept(THREAD_ID, "REVIEW"));
    assertEquals("Project thread ownership is inconsistent", missing.getMessage());
  }
}
