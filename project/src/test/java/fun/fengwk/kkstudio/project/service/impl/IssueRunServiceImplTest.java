package fun.fengwk.kkstudio.project.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import fun.fengwk.kkstudio.harness.runtime.AcceptCommandsCommand;
import fun.fengwk.kkstudio.harness.runtime.AcceptCommandsTarget;
import fun.fengwk.kkstudio.harness.runtime.AcceptedCommands;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.runtime.history.CustomEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.join.ThreadJoinRequest;
import fun.fengwk.kkstudio.harness.runtime.thread.command.CustomMessageCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.NewThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.thread.command.SetAgentCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.SetContributorStateCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.SetEnvironmentCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.SetModelCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandType;
import fun.fengwk.kkstudio.project.domain.ProjectWorkflowJsonCodec;
import fun.fengwk.kkstudio.project.model.Issue;
import fun.fengwk.kkstudio.project.model.IssueRun;
import fun.fengwk.kkstudio.project.model.IssueStageBudgetRow;
import fun.fengwk.kkstudio.project.model.Project;
import fun.fengwk.kkstudio.project.port.AgentBranchSettingsPort;
import fun.fengwk.kkstudio.project.repo.IssueActivityRepository;
import fun.fengwk.kkstudio.project.repo.IssueAgentThreadRepository;
import fun.fengwk.kkstudio.project.repo.IssueRepository;
import fun.fengwk.kkstudio.project.repo.IssueRunRepository;
import fun.fengwk.kkstudio.project.repo.IssueStageBudgetRepository;
import fun.fengwk.kkstudio.project.repo.ProjectRepository;
import fun.fengwk.kkstudio.project.service.IssueWorkStore;
import fun.fengwk.kkstudio.project.turn.ProjectRunScope;
import fun.fengwk.kkstudio.project.turn.ProjectRunScopeJsonCodec;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 显式 Run 接受：每次 Run 先分配 runId 并作为 root Join ticket 的 invocationId，命令批按固定顺序显式设置
 * Agent/Model/Environment、冻结 {@code project/run} contributor state，最后以恰一条 CUSTOM_MESSAGE 启动任务。
 *
 * <p>测试意图：断言接受方提交的 Harness 契约形状（命令类型与顺序、Join 身份、冻结快照的业务身份），不依赖真实 Runtime 执行；Run 记录、Join
 * 与上下文冻结的原子性由平台集成测试覆盖。
 */
class IssueRunServiceImplTest {

  private static final UUID ISSUE_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
  private static final UUID PROJECT_ID = UUID.fromString("10000000-0000-0000-0000-000000000002");
  private static final String WORKFLOW =
      """
      {"states":[{"state":"INIT","name":"待开始","next":["DESIGN"]},\
      {"state":"DESIGN","name":"设计","agent":"worker","instructions":"完成可交付方案","maxRuns":3,\
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
  private final IssueWorkStore issueWorkStore = mock(IssueWorkStore.class);
  private final ProjectWorkflowJsonCodec workflowCodec = new ProjectWorkflowJsonCodec();
  private final AgentBranchSettingsPort agentBranchSettingsPort =
      mock(AgentBranchSettingsPort.class);
  private final HarnessRuntime runtime = mock(HarnessRuntime.class);

  @SuppressWarnings("unchecked")
  private final ObjectProvider<HarnessRuntime> runtimes = mock(ObjectProvider.class);

  private final IssueRunServiceImpl service =
      new IssueRunServiceImpl(
          projectRepository,
          issueRepository,
          issueRunRepository,
          stageBudgetRepository,
          issueAgentThreadRepository,
          issueActivityRepository,
          issueWorkStore,
          workflowCodec,
          agentBranchSettingsPort,
          runtimes,
          new ObjectMapper());

  /**
   * 新 Agent 接受 Run：五条命令按 SET_AGENT→SET_MODEL→SET_ENVIRONMENT→SET_CONTRIBUTOR_STATE→CUSTOM_MESSAGE
   * 提交。
   */
  @Test
  void acceptsExplicitRunCommandBatchFrozenToRunTicket() {
    Issue issue = issue();
    Project project = project();
    BranchSettings settings =
        new BranchSettings("worker", new ModelSelection("provider", "model", "default"), null);
    AtomicReference<IssueRun> inserted = new AtomicReference<>();
    Entry rootEntry = mock(Entry.class);
    UUID rootEntryId = UUID.randomUUID();
    AcceptedCommands accepted = mock(AcceptedCommands.class);

    when(issueRepository.getById(ISSUE_ID)).thenReturn(issue);
    when(projectRepository.lockForKeyShare(PROJECT_ID)).thenReturn(project);
    when(issueRepository.lockById(ISSUE_ID)).thenReturn(issue);
    when(issueRunRepository.lockActiveByIssueId(ISSUE_ID)).thenReturn(null);
    when(stageBudgetRepository.get(ISSUE_ID, "DESIGN"))
        .thenReturn(
            IssueStageBudgetRow.builder()
                .issueId(ISSUE_ID)
                .state("DESIGN")
                .maxRuns(5)
                .budgetAfterOrdinal(0)
                .build());
    when(issueRunRepository.countByIssueIdAndStateAfterOrdinal(ISSUE_ID, "DESIGN", 0))
        .thenReturn(0L);
    when(issueAgentThreadRepository.findByIssueIdAndAgentName(ISSUE_ID, "worker")).thenReturn(null);
    when(agentBranchSettingsPort.materializeBranchSettings("worker")).thenReturn(settings);
    when(rootEntry.id()).thenReturn(rootEntryId);
    when(accepted.rootEntry()).thenReturn(rootEntry);
    when(runtime.acceptCommandsAndJoin(any(), any(), any())).thenReturn(accepted);
    when(runtimes.getIfAvailable()).thenReturn(runtime);
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

    ArgumentCaptor<AcceptCommandsCommand> commandCaptor =
        ArgumentCaptor.forClass(AcceptCommandsCommand.class);
    ArgumentCaptor<ThreadJoinRequest> joinCaptor = ArgumentCaptor.forClass(ThreadJoinRequest.class);
    verify(runtime).acceptCommandsAndJoin(commandCaptor.capture(), joinCaptor.capture(), any());

    AcceptCommandsTarget.NewSession target =
        assertInstanceOf(AcceptCommandsTarget.NewSession.class, commandCaptor.getValue().target());
    List<NewThreadCommand> commands = commandCaptor.getValue().commands();
    assertEquals(
        List.of(
            ThreadCommandType.SET_AGENT,
            ThreadCommandType.SET_MODEL,
            ThreadCommandType.SET_ENVIRONMENT,
            ThreadCommandType.SET_CONTRIBUTOR_STATE,
            ThreadCommandType.CUSTOM_MESSAGE),
        commands.stream().map(command -> command.payload().type()).toList());
    assertInstanceOf(SetAgentCommandPayload.class, commands.get(0).payload());
    assertInstanceOf(SetModelCommandPayload.class, commands.get(1).payload());
    assertInstanceOf(SetEnvironmentCommandPayload.class, commands.get(2).payload());
    assertInstanceOf(CustomMessageCommandPayload.class, commands.get(4).payload());

    // runId 在接受源输入前分配，必须同时是 Run 主键与 root Join ticket 的 invocationId。
    ThreadJoinRequest join = joinCaptor.getValue();
    assertEquals(inserted.get().getId(), run.getId());
    assertEquals(run.getId(), join.invocationId());
    assertNull(join.parentThreadId());
    String taskRequestHash = commands.get(4).requestHash();
    assertEquals(taskRequestHash, join.requestHash());

    // 冻结快照必须携带本 Run 与 sourceThreadId 的业务身份，供上下文投影与工具授权共同使用。
    SetContributorStateCommandPayload stateCommand =
        assertInstanceOf(SetContributorStateCommandPayload.class, commands.get(3).payload());
    CustomEntryPayload state = stateCommand.state();
    assertEquals(ProjectRunScope.CONTRIBUTOR_ID, state.contributorId());
    assertEquals(ProjectRunScope.CUSTOM_TYPE, state.customType());
    assertEquals(ProjectRunScope.SCHEMA_VERSION, state.schemaVersion());
    ProjectRunScope scope = new ProjectRunScopeJsonCodec().decode(state.dataJson());
    assertEquals(run.getId(), scope.runId());
    assertEquals(ISSUE_ID, scope.issueId());
    assertEquals(PROJECT_ID, scope.projectId());
    assertEquals(target.threadId(), scope.sourceThreadId());
    assertEquals("DESIGN", scope.stage());
    assertEquals("worker", scope.agentName());
    assertEquals(rootEntryId, run.getStartEntryId());
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
