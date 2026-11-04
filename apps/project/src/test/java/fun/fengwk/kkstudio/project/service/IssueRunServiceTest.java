package fun.fengwk.kkstudio.project.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.json.JsonMapper;

import fun.fengwk.kkstudio.harness.api.AcceptedCommands;
import fun.fengwk.kkstudio.harness.api.command.CustomMessageCommandPayload;
import fun.fengwk.kkstudio.harness.api.command.NewThreadCommand;
import fun.fengwk.kkstudio.harness.api.command.SetAgentCommandPayload;
import fun.fengwk.kkstudio.harness.api.command.SetEnvironmentCommandPayload;
import fun.fengwk.kkstudio.harness.api.command.SetExecutionCommandPayload;
import fun.fengwk.kkstudio.harness.api.command.ThreadCommandType;
import fun.fengwk.kkstudio.harness.api.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.api.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.api.history.Entry;
import fun.fengwk.kkstudio.harness.api.history.EntryPath;
import fun.fengwk.kkstudio.harness.api.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.api.thread.ThreadExecutionFacts;
import fun.fengwk.kkstudio.harness.builtin.driver.BuiltinExecution;
import fun.fengwk.kkstudio.platform.harness.task.AgentBranchSettingsMaterializer;
import fun.fengwk.kkstudio.project.domain.IssueRunStatus;
import fun.fengwk.kkstudio.project.domain.ProjectWorkflowJsonCodec;
import fun.fengwk.kkstudio.project.error.ProjectValidationException;
import fun.fengwk.kkstudio.project.model.Issue;
import fun.fengwk.kkstudio.project.model.IssueRun;
import fun.fengwk.kkstudio.project.model.Project;
import fun.fengwk.kkstudio.project.platform.IssueRunTask;
import fun.fengwk.kkstudio.project.platform.IssueThreadExecution;
import fun.fengwk.kkstudio.project.platform.IssueThreadView;
import fun.fengwk.kkstudio.project.repo.IssueActivityRepository;
import fun.fengwk.kkstudio.project.repo.IssueAgentThreadRepository;
import fun.fengwk.kkstudio.project.repo.IssueRepository;
import fun.fengwk.kkstudio.project.repo.IssueRunRepository;
import fun.fengwk.kkstudio.project.repo.IssueStageBudgetRepository;
import fun.fengwk.kkstudio.project.repo.ProjectRepository;
import fun.fengwk.kkstudio.work.DurableWorkStore;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 显式 Run 接受：每次 Run 先分配 runId 并作为 root Join ticket 的 invocationId，命令批按固定顺序显式设置
 * Agent/Execution/Environment，最后以恰一条 CUSTOM_MESSAGE 启动任务。
 *
 * <p>测试意图：断言接受方提交的 Harness 契约形状（只有通用命令与一条渲染好的 Markdown 任务消息、Join 身份、历史起点），并断言收尾不再写任何 Harness 状态；Run
 * 记录、Join 与命令的原子性由平台集成测试覆盖。
 */
class IssueRunServiceTest {

  private static final UUID ISSUE_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
  private static final UUID PROJECT_ID = UUID.fromString("10000000-0000-0000-0000-000000000002");
  private static final String WORKFLOW =
      """
      {"states":[{"state":"INIT","name":"待开始","next":["DESIGN"]},\
      {"state":"DESIGN","name":"设计","agent":"worker","environment":"stage-env","instructions":"完成 **可交付** 方案","maxRuns":3,\
      "next":["DONE"]},\
      {"state":"BLOCKED","name":"业务阻塞"},\
      {"state":"DONE","name":"完成"}]}""";

  private final ProjectRepository projectRepository = mock(ProjectRepository.class);
  private final IssueRepository issueRepository = mock(IssueRepository.class);
  private final IssueRunRepository issueRunRepository = mock(IssueRunRepository.class);
  private final IssueStageBudgetRepository stageBudgetRepository =
      mock(IssueStageBudgetRepository.class);
  private final IssueAgentThreadRepository issueAgentThreadRepository =
      mock(IssueAgentThreadRepository.class);
  private final IssueActivityRepository issueActivityRepository =
      mock(IssueActivityRepository.class);
  private final DurableWorkStore issueWorkQueue = mock(DurableWorkStore.class);
  private final ProjectWorkflowJsonCodec workflowCodec = new ProjectWorkflowJsonCodec();
  private final AgentBranchSettingsMaterializer agentBranchSettings =
      mock(AgentBranchSettingsMaterializer.class);
  private final IssueThreadExecution threadExecutions = mock(IssueThreadExecution.class);

  @SuppressWarnings("unchecked")
  private final ObjectProvider<IssueThreadExecution> executions = mock(ObjectProvider.class);

  private final IssueRunService service =
      new IssueRunService(
          projectRepository,
          issueRepository,
          issueRunRepository,
          stageBudgetRepository,
          issueAgentThreadRepository,
          issueActivityRepository,
          issueWorkQueue,
          workflowCodec,
          agentBranchSettings,
          executions,
          JsonMapper.builder().build());

  /**
   * 新 Agent 接受 Run：四条命令按 SET_AGENT→SET_EXECUTION→SET_ENVIRONMENT→CUSTOM_MESSAGE 提交，任务消息是唯一业务上下文。
   */
  @Test
  void acceptsGeneralCommandsAndRenderedTaskMessage() {
    Issue issue = issue();
    Project project = project();
    BranchSettings settings =
        new BranchSettings(
            "worker", BuiltinExecution.of(ModelSelection.model("model", "default")), null);
    AtomicReference<IssueRun> inserted = new AtomicReference<>();
    Entry rootEntry = mock(Entry.class);
    UUID rootEntryId = UUID.randomUUID();
    AcceptedCommands accepted = mock(AcceptedCommands.class);

    when(issueRepository.getById(ISSUE_ID)).thenReturn(issue);
    when(projectRepository.lockForShare(PROJECT_ID)).thenReturn(project);
    when(issueRepository.lockById(ISSUE_ID)).thenReturn(issue);
    when(issueRunRepository.lockActiveByIssueId(ISSUE_ID)).thenReturn(null);
    when(stageBudgetRepository.get(ISSUE_ID, "DESIGN"))
        .thenReturn(new StageBudgetView("DESIGN", 5, 0, 0, 5));
    when(issueAgentThreadRepository.findByIssueIdAndAgentName(ISSUE_ID, "worker")).thenReturn(null);
    when(agentBranchSettings.materialize("worker")).thenReturn(settings);
    when(rootEntry.id()).thenReturn(rootEntryId);
    when(accepted.rootEntry()).thenReturn(rootEntry);
    when(threadExecutions.submitRunTask(any())).thenReturn(accepted);
    when(executions.getIfAvailable()).thenReturn(threadExecutions);
    when(issueAgentThreadRepository.insert(any())).thenReturn(true);
    when(issueRunRepository.insert(any()))
        .thenAnswer(
            invocation -> {
              inserted.set(invocation.getArgument(0));
              return true;
            });
    when(issueActivityRepository.insert(any())).thenReturn(true);
    when(issueRepository.updateById(any(), anyLong())).thenReturn(true);
    when(issueRunRepository.getById(any())).thenAnswer(invocation -> inserted.get());

    IssueRun run = service.acceptRun(ISSUE_ID, "accept-1");

    ArgumentCaptor<IssueRunTask> taskCaptor = ArgumentCaptor.forClass(IssueRunTask.class);
    verify(threadExecutions).submitRunTask(taskCaptor.capture());

    IssueRunTask task = taskCaptor.getValue();
    assertNotNull(task.sessionId());
    assertNull(task.continuation());
    List<NewThreadCommand> commands = task.commands();
    assertEquals(
        List.of(
            ThreadCommandType.SET_AGENT,
            ThreadCommandType.SET_EXECUTION,
            ThreadCommandType.SET_ENVIRONMENT,
            ThreadCommandType.CUSTOM_MESSAGE),
        commands.stream().map(command -> command.payload().type()).toList());
    assertInstanceOf(SetAgentCommandPayload.class, commands.get(0).payload());
    assertInstanceOf(SetExecutionCommandPayload.class, commands.get(1).payload());
    // SET_ENVIRONMENT 取当前 stage 的显式环境，而不是 Agent 默认 settings 的 environmentName（此处为 null）。
    SetEnvironmentCommandPayload environment =
        assertInstanceOf(SetEnvironmentCommandPayload.class, commands.get(2).payload());
    assertEquals("stage-env", environment.environmentName());

    CustomMessageCommandPayload taskCommand =
        assertInstanceOf(CustomMessageCommandPayload.class, commands.get(3).payload());
    String message = ((TextMessageContent) taskCommand.message().contents().get(0)).text();
    assertTrue(message.contains("stage DESIGN"), message);
    assertTrue(message.contains("完成 **可交付** 方案"), message);
    assertTrue(message.contains("#7 修复登录"), message);
    assertTrue(message.contains("登录偶发失败"), message);
    assertTrue(message.contains("Allowed handoff targets: DONE"), message);
    // 可信 Markdown 必须原样渲染，不能出现 XML/Markdown 转义。
    assertFalse(message.contains("&quot;"), message);
    assertFalse(message.contains("&#"), message);

    // runId 在接受源输入前分配，必须同时是 Run 主键与 root Join ticket 的 invocationId。
    assertEquals(inserted.get().getId(), run.getId());
    assertEquals(run.getId(), task.runId());
    assertEquals(commands.get(3).requestHash(), task.requestHash());
    assertEquals(rootEntryId, run.getStartEntryId());
  }

  /** 保留态不能启动 Run，也不能派发 Harness 命令。 */
  @Test
  void rejectsReservedStageBeforeDispatch() {
    Issue issue = issue();
    issue.setState("INIT");
    when(issueRepository.getById(ISSUE_ID)).thenReturn(issue);
    when(projectRepository.lockForShare(PROJECT_ID)).thenReturn(project());
    when(issueRepository.lockById(ISSUE_ID)).thenReturn(issue);

    ProjectValidationException error =
        assertThrows(ProjectValidationException.class, () -> service.acceptRun(ISSUE_ID, "start"));
    assertTrue(error.getMessage().contains("not in an executable work stage"));
    verify(threadExecutions, never()).submitRunTask(any());
    verify(issueRunRepository, never()).insert(any());
  }

  /** 没有 Agent 的工作阶段不允许启动，也不能创建额度或派发命令。 */
  @Test
  void rejectsStageWithoutAgentBeforeAuthorizingBudget() {
    Issue issue = issue();
    Project project = project();
    project.setWorkflowJson(
        WORKFLOW
            .replace("\"agent\":\"worker\",", "")
            .replace("\"environment\":\"stage-env\",", "")
            .replace("\"maxRuns\":3,", ""));
    when(issueRepository.getById(ISSUE_ID)).thenReturn(issue);
    when(projectRepository.lockForShare(PROJECT_ID)).thenReturn(project);
    when(issueRepository.lockById(ISSUE_ID)).thenReturn(issue);

    ProjectValidationException error =
        assertThrows(ProjectValidationException.class, () -> service.acceptRun(ISSUE_ID, "start"));
    assertTrue(error.getMessage().contains("no enabled agent"));
    verify(stageBudgetRepository, never()).insert(any(), any());
    verify(threadExecutions, never()).submitRunTask(any());
  }

  /** 归档后的项目和 Issue 均不能接受新的 Run。 */
  @Test
  void rejectsArchivedOwnersBeforeDispatch() {
    Issue issue = issue();
    Project project = project();
    when(issueRepository.getById(ISSUE_ID)).thenReturn(issue);
    when(projectRepository.lockForShare(PROJECT_ID)).thenReturn(project);
    when(issueRepository.lockById(ISSUE_ID)).thenReturn(issue);

    project.setArchivedAt(Instant.now());
    ProjectValidationException projectError =
        assertThrows(ProjectValidationException.class, () -> service.acceptRun(ISSUE_ID, "start"));
    assertTrue(projectError.getMessage().contains("archived project"));
    project.setArchivedAt(null);
    issue.setArchivedAt(Instant.now());
    ProjectValidationException issueError =
        assertThrows(ProjectValidationException.class, () -> service.acceptRun(ISSUE_ID, "start"));
    assertTrue(issueError.getMessage().contains("archived issue"));
    verify(threadExecutions, never()).submitRunTask(any());
    verify(issueRunRepository, never()).insert(any());
  }

  /** 测试意图：Run 收尾只收敛 Project 自己的行，不再向 Harness 接受任何状态写入（旧实现的 scope 闭合批次彻底消失）。 */
  @Test
  void completeRunWritesNoHarnessState() {
    UUID threadId = UUID.randomUUID();
    UUID runId = UUID.randomUUID();
    UUID startEntryId = UUID.randomUUID();
    UUID endEntryId = UUID.randomUUID();
    IssueRun run =
        IssueRun.builder()
            .id(runId)
            .issueId(ISSUE_ID)
            .ordinal(0)
            .state("DESIGN")
            .sessionId(UUID.randomUUID())
            .threadId(threadId)
            .status(IssueRunStatus.RUNNING)
            .startEntryId(startEntryId)
            .observedActivitySequence(0)
            .remainingExecutionMs(1000L)
            .activeSince(Instant.now())
            .version(3L)
            .build();
    Entry startEntry = mock(Entry.class);
    when(startEntry.id()).thenReturn(startEntryId);
    Entry endEntry = mock(Entry.class);
    when(endEntry.id()).thenReturn(endEntryId);
    EntryPath path = mock(EntryPath.class);
    when(path.entries()).thenReturn(List.of(startEntry, endEntry));
    IssueThreadView view =
        new IssueThreadView(
            run.getSessionId(),
            endEntryId,
            4L,
            5L,
            path,
            new ThreadExecutionFacts(false, false, false));

    when(issueRunRepository.getById(runId)).thenReturn(run);
    when(issueRepository.getById(ISSUE_ID)).thenReturn(issue());
    when(projectRepository.lockForKeyShare(PROJECT_ID)).thenReturn(project());
    when(issueRepository.lockById(ISSUE_ID)).thenReturn(issue());
    when(issueRunRepository.lockById(runId)).thenReturn(run);
    when(issueActivityRepository.insert(any())).thenReturn(true);
    when(issueRunRepository.updateById(any(), anyLong())).thenReturn(true);
    when(issueRepository.updateById(any(), anyLong())).thenReturn(true);
    when(executions.getIfAvailable()).thenReturn(threadExecutions);
    when(threadExecutions.readThread(threadId)).thenReturn(view);

    service.finishRun(
        runId, 3L, "complete-1", IssueRunStatus.COMPLETED, endEntryId, null, null, null);

    // 历史区间校验仍然读取权威路径，但整个收尾不产生任何 Harness 命令接受。
    verify(threadExecutions).readThread(threadId);
    verify(threadExecutions, never()).acceptCommands(any(), any());
    assertEquals(IssueRunStatus.COMPLETED, run.getStatus());
    assertEquals(endEntryId, run.getEndEntryId());
  }

  @Test
  void rejectsInvalidTerminalArgumentsBeforeReadingRun() {
    // 不合法状态和跨终态字段不能访问持久化或被幂等重放掩盖。
    UUID runId = UUID.randomUUID();
    UUID end = UUID.randomUUID();
    assertThrows(
        ProjectValidationException.class,
        () -> service.finishRun(runId, 0, "key", IssueRunStatus.RUNNING, end, null, null, null));
    assertThrows(
        ProjectValidationException.class,
        () -> service.finishRun(runId, 0, "key", null, end, null, null, null));
    assertThrows(
        ProjectValidationException.class,
        () ->
            service.finishRun(runId, 0, "key", IssueRunStatus.COMPLETED, end, null, null, "error"));
    assertThrows(
        ProjectValidationException.class,
        () -> service.finishRun(runId, 0, "key", IssueRunStatus.CANCELLED, end, end, null, null));
    assertThrows(
        ProjectValidationException.class,
        () ->
            service.finishRun(
                runId, 0, "key", IssueRunStatus.FAILED, end, null, "REVIEW", "error"));
    assertThrows(
        ProjectValidationException.class,
        () -> service.finishRun(runId, 0, "key", IssueRunStatus.FAILED, end, null, null, null));
    verify(issueRunRepository, never()).getById(any());
  }

  /** 缺少 Harness 运行时必须明确失败，不能伪造已接受的 Run。 */
  @Test
  void missingHarnessRuntimeRejectsAcceptance() {
    Issue issue = issue();
    when(issueRepository.getById(ISSUE_ID)).thenReturn(issue);
    when(projectRepository.lockForShare(PROJECT_ID)).thenReturn(project());
    when(issueRepository.lockById(ISSUE_ID)).thenReturn(issue);
    when(stageBudgetRepository.insert(any(), any())).thenReturn(true);
    when(agentBranchSettings.materialize("worker"))
        .thenReturn(
            new BranchSettings(
                "worker", BuiltinExecution.of(ModelSelection.model("model", "default")), null));

    IllegalStateException error =
        assertThrows(IllegalStateException.class, () -> service.acceptRun(ISSUE_ID, "accept"));
    assertTrue(error.getMessage().contains("harness runtime"));
    verify(issueRunRepository, never()).insert(any());
  }

  /** 耗尽的执行额度不能通过等待或恢复绕过；终态也不能被重新激活。 */
  @Test
  void exhaustedAndTerminalRunsCannotWaitOrResume() {
    UUID runId = UUID.randomUUID();
    IssueRun run =
        IssueRun.builder()
            .id(runId)
            .issueId(ISSUE_ID)
            .status(IssueRunStatus.RUNNING)
            .remainingExecutionMs(0)
            .version(1)
            .build();
    when(issueRunRepository.getById(runId)).thenReturn(run);
    when(issueRepository.getById(ISSUE_ID)).thenReturn(issue());
    when(projectRepository.lockForKeyShare(PROJECT_ID)).thenReturn(project());
    when(issueRepository.lockById(ISSUE_ID)).thenReturn(issue());
    when(issueRunRepository.lockById(runId)).thenReturn(run);

    assertThrows(ProjectValidationException.class, () -> service.waitRun(runId, 1));
    run.setStatus(IssueRunStatus.WAITING);
    assertThrows(ProjectValidationException.class, () -> service.resumeRun(runId, 1));
    run.setStatus(IssueRunStatus.FAILED);
    assertThrows(ProjectValidationException.class, () -> service.resumeRun(runId, 1));
    assertThrows(ProjectValidationException.class, () -> service.waitRun(runId, 1));
    verify(issueRunRepository, never()).updateById(any(), anyLong());
  }

  private static Issue issue() {
    return Issue.builder()
        .id(ISSUE_ID)
        .projectId(PROJECT_ID)
        .number(7L)
        .title("修复登录")
        .description("登录偶发失败")
        .state("DESIGN")
        .nextRunOrdinal(0)
        .nextActivitySequence(1)
        .version(0)
        .build();
  }

  private static Project project() {
    return Project.builder()
        .id(PROJECT_ID)
        .title("p")
        .workflowJson(WORKFLOW)
        .yoloEnabled(false)
        .version(0)
        .build();
  }
}
