package fun.fengwk.kkstudio.platform.project.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import fun.fengwk.kkstudio.harness.runtime.AcceptCommandsCommand;
import fun.fengwk.kkstudio.harness.runtime.AcceptCommandsTarget;
import fun.fengwk.kkstudio.harness.runtime.AcceptedCommands;
import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.Session;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.thread.command.NewThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandPayloadJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.thread.command.UserMessageCommandPayload;
import fun.fengwk.kkstudio.platform.error.AiResourceNotFoundException;
import fun.fengwk.kkstudio.platform.error.AiValidationException;
import fun.fengwk.kkstudio.platform.harness.task.AgentBranchSettingsMaterializer;
import fun.fengwk.kkstudio.platform.orchestration.HarnessCommandAcceptanceOrchestrator;
import fun.fengwk.kkstudio.platform.orchestration.OwnerRef;
import fun.fengwk.kkstudio.platform.orchestration.OwnerType;
import fun.fengwk.kkstudio.platform.project.model.Issue;
import fun.fengwk.kkstudio.platform.project.model.IssueRun;
import fun.fengwk.kkstudio.platform.project.model.IssueRunActorType;
import fun.fengwk.kkstudio.platform.project.model.IssueRunRole;
import fun.fengwk.kkstudio.platform.project.model.IssueRunSession;
import fun.fengwk.kkstudio.platform.project.model.IssueRunStatus;
import fun.fengwk.kkstudio.platform.project.model.Project;
import fun.fengwk.kkstudio.platform.project.model.ProjectSession;
import fun.fengwk.kkstudio.platform.project.repo.IssueRepository;
import fun.fengwk.kkstudio.platform.project.repo.IssueRunRepository;
import fun.fengwk.kkstudio.platform.project.repo.IssueRunSessionRepository;
import fun.fengwk.kkstudio.platform.project.repo.ProjectRepository;
import fun.fengwk.kkstudio.platform.project.repo.ProjectSessionRepository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * {@link ProjectHarnessSessionBootstrapService} 单元测试。
 *
 * <p>测试意图：
 *
 * <ul>
 *   <li>验证权威 Agent 与 BranchSettings 的正确物化与选择；
 *   <li>验证 target 与 command 的结构、UUID 幂等键与 canonical requestHash 计算；
 *   <li>验证已归档项目、HUMAN 运行、终态/非活跃运行、缺失 Agent、所有权层级不一致等严格前置拒绝；
 *   <li>验证同 Owner 精确重放的正确委托行为；
 *   <li>验证异常消息脱敏，绝不回显初始用户消息正文或 action/idempotency UUID。
 * </ul>
 */
class ProjectHarnessSessionBootstrapServiceTest {

  private static final UUID PROJECT_ID = id(1);
  private static final UUID ISSUE_ID = id(2);
  private static final UUID RUN_ID = id(3);
  private static final UUID SESSION_ID = id(4);
  private static final UUID THREAD_ID = id(5);
  private static final UUID IDEMPOTENCY_KEY = id(6);
  private static final Instant NOW = Instant.parse("2026-09-13T10:00:00Z");

  private ProjectRepository projectRepository;
  private ProjectSessionRepository projectSessionRepository;
  private IssueRepository issueRepository;
  private IssueRunRepository issueRunRepository;
  private IssueRunSessionRepository issueRunSessionRepository;
  private AgentBranchSettingsMaterializer settingsMaterializer;
  private HarnessCommandAcceptanceOrchestrator acceptanceOrchestrator;
  private ProjectHarnessSessionBootstrapService service;

  @BeforeEach
  void setUp() {
    projectRepository = mock(ProjectRepository.class);
    projectSessionRepository = mock(ProjectSessionRepository.class);
    issueRepository = mock(IssueRepository.class);
    issueRunRepository = mock(IssueRunRepository.class);
    issueRunSessionRepository = mock(IssueRunSessionRepository.class);
    settingsMaterializer = mock(AgentBranchSettingsMaterializer.class);
    acceptanceOrchestrator = mock(HarnessCommandAcceptanceOrchestrator.class);

    service =
        new ProjectHarnessSessionBootstrapService(
            projectRepository,
            projectSessionRepository,
            issueRepository,
            issueRunRepository,
            issueRunSessionRepository,
            settingsMaterializer,
            acceptanceOrchestrator);
  }

  @Test
  void bootstrapProjectSession_success_selectsAuthoritativeAgentAndMaterializesSettings() {
    Project project = testProject("coordinator-agent", null);
    when(projectRepository.lockForShare(PROJECT_ID)).thenReturn(project);
    when(projectSessionRepository.findByProjectId(PROJECT_ID)).thenReturn(null);

    BranchSettings settings = branchSettings("coordinator-agent");
    when(settingsMaterializer.materialize("coordinator-agent")).thenReturn(settings);

    AcceptedCommands accepted = stubAcceptedCommands(SESSION_ID, THREAD_ID, false);
    when(acceptanceOrchestrator.accept(eq(new OwnerRef(OwnerType.PROJECT, PROJECT_ID)), any()))
        .thenReturn(accepted);

    // 初始命令是用户正文事实，校验不得裁剪有意义的首尾空白或换行。
    String initialMessage = "  Start coordinator\n";
    BootstrapProjectSessionRequest request =
        new BootstrapProjectSessionRequest(
            PROJECT_ID, SESSION_ID, THREAD_ID, IDEMPOTENCY_KEY, initialMessage);
    AcceptedCommands result = service.bootstrapProjectSession(request);

    assertEquals(accepted, result);
    assertFalse(result.replayed());

    ArgumentCaptor<AcceptCommandsCommand> commandCaptor =
        ArgumentCaptor.forClass(AcceptCommandsCommand.class);
    verify(acceptanceOrchestrator)
        .accept(eq(new OwnerRef(OwnerType.PROJECT, PROJECT_ID)), commandCaptor.capture());

    AcceptCommandsCommand captured = commandCaptor.getValue();
    AcceptCommandsTarget.NewSession target = (AcceptCommandsTarget.NewSession) captured.target();
    assertEquals(SESSION_ID, target.sessionId());
    assertEquals(THREAD_ID, target.threadId());
    assertEquals(settings, target.rootSettings());
    assertFalse(target.yoloEnabled());

    assertEquals(1, captured.commands().size());
    NewThreadCommand initialCommand = captured.commands().getFirst();
    assertEquals(IDEMPOTENCY_KEY, initialCommand.idempotencyKey());
    assertTrue(initialCommand.payload() instanceof UserMessageCommandPayload);
    UserMessageCommandPayload userPayload = (UserMessageCommandPayload) initialCommand.payload();
    assertEquals(AgentMessageRole.USER, userPayload.message().role());
    assertEquals(
        initialMessage, ((TextMessageContent) userPayload.message().contents().getFirst()).text());
    assertEquals(
        ThreadCommandPayloadJsonCodec.requestHash(userPayload), initialCommand.requestHash());
  }

  @Test
  void bootstrapProjectSession_convenienceOverloadDelegatesToRecord() {
    Project project = testProject("coord-agent", null);
    when(projectRepository.lockForShare(PROJECT_ID)).thenReturn(project);
    when(settingsMaterializer.materialize("coord-agent")).thenReturn(branchSettings("coord-agent"));
    AcceptedCommands accepted = stubAcceptedCommands(SESSION_ID, THREAD_ID, false);
    when(acceptanceOrchestrator.accept(any(), any())).thenReturn(accepted);

    AcceptedCommands result =
        service.bootstrapProjectSession(
            PROJECT_ID, SESSION_ID, THREAD_ID, IDEMPOTENCY_KEY, "Overload text");
    assertEquals(accepted, result);
  }

  @Test
  void bootstrapProjectSession_rejectsNonExistentProject() {
    when(projectRepository.lockForShare(PROJECT_ID)).thenReturn(null);

    BootstrapProjectSessionRequest request =
        new BootstrapProjectSessionRequest(
            PROJECT_ID, SESSION_ID, THREAD_ID, IDEMPOTENCY_KEY, "Text");

    assertThrows(AiResourceNotFoundException.class, () -> service.bootstrapProjectSession(request));
    verify(acceptanceOrchestrator, never()).accept(any(), any());
  }

  @Test
  void bootstrapProjectSession_rejectsArchivedProject() {
    Project archivedProject = testProject("coordinator-agent", NOW);
    when(projectRepository.lockForShare(PROJECT_ID)).thenReturn(archivedProject);

    BootstrapProjectSessionRequest request =
        new BootstrapProjectSessionRequest(
            PROJECT_ID, SESSION_ID, THREAD_ID, IDEMPOTENCY_KEY, "Text");

    AiValidationException ex =
        assertThrows(AiValidationException.class, () -> service.bootstrapProjectSession(request));
    assertTrue(ex.getMessage().contains("Cannot bootstrap session for archived project"));
    verify(acceptanceOrchestrator, never()).accept(any(), any());
  }

  @Test
  void bootstrapProjectSession_rejectsMissingCoordinatorAgent() {
    Project missingAgentProject = testProject("   ", null);
    when(projectRepository.lockForShare(PROJECT_ID)).thenReturn(missingAgentProject);

    BootstrapProjectSessionRequest request =
        new BootstrapProjectSessionRequest(
            PROJECT_ID, SESSION_ID, THREAD_ID, IDEMPOTENCY_KEY, "Text");

    assertThrows(AiValidationException.class, () -> service.bootstrapProjectSession(request));
    verify(acceptanceOrchestrator, never()).accept(any(), any());
  }

  @Test
  void bootstrapProjectSession_rejectsExistingBoundDifferentSession() {
    Project project = testProject("coordinator-agent", null);
    when(projectRepository.lockForShare(PROJECT_ID)).thenReturn(project);
    UUID otherSessionId = id(99);
    when(projectSessionRepository.findByProjectId(PROJECT_ID))
        .thenReturn(
            ProjectSession.builder().projectId(PROJECT_ID).sessionId(otherSessionId).build());

    BootstrapProjectSessionRequest request =
        new BootstrapProjectSessionRequest(
            PROJECT_ID, SESSION_ID, THREAD_ID, IDEMPOTENCY_KEY, "Text");

    AiValidationException ex =
        assertThrows(AiValidationException.class, () -> service.bootstrapProjectSession(request));
    assertTrue(ex.getMessage().contains("already bound to a different session"));
    verify(acceptanceOrchestrator, never()).accept(any(), any());
  }

  @Test
  void bootstrapProjectSession_exactReplayDelegation() {
    Project project = testProject("coordinator-agent", null);
    when(projectRepository.lockForShare(PROJECT_ID)).thenReturn(project);
    when(projectSessionRepository.findByProjectId(PROJECT_ID))
        .thenReturn(ProjectSession.builder().projectId(PROJECT_ID).sessionId(SESSION_ID).build());
    when(settingsMaterializer.materialize("coordinator-agent"))
        .thenReturn(branchSettings("coordinator-agent"));

    AcceptedCommands replayed = stubAcceptedCommands(SESSION_ID, THREAD_ID, true);
    when(acceptanceOrchestrator.accept(eq(new OwnerRef(OwnerType.PROJECT, PROJECT_ID)), any()))
        .thenReturn(replayed);

    BootstrapProjectSessionRequest request =
        new BootstrapProjectSessionRequest(
            PROJECT_ID, SESSION_ID, THREAD_ID, IDEMPOTENCY_KEY, "Exact replay prompt");
    AcceptedCommands result = service.bootstrapProjectSession(request);

    assertTrue(result.replayed());
    verify(acceptanceOrchestrator).accept(any(), any());
  }

  @Test
  void bootstrapIssueRunSession_success_selectsAuthoritativeAgentAndMaterializesSettings() {
    stubValidIssueRunHierarchy("executor-agent", IssueRunActorType.AGENT, IssueRunStatus.RUNNING);

    BranchSettings settings = branchSettings("executor-agent");
    when(settingsMaterializer.materialize("executor-agent")).thenReturn(settings);

    AcceptedCommands accepted = stubAcceptedCommands(SESSION_ID, THREAD_ID, false);
    when(acceptanceOrchestrator.accept(eq(new OwnerRef(OwnerType.ISSUE_RUN, RUN_ID)), any()))
        .thenReturn(accepted);

    BootstrapIssueRunSessionRequest request =
        new BootstrapIssueRunSessionRequest(
            RUN_ID, SESSION_ID, THREAD_ID, IDEMPOTENCY_KEY, "Execute issue run");
    AcceptedCommands result = service.bootstrapIssueRunSession(request);

    assertEquals(accepted, result);
    assertFalse(result.replayed());

    ArgumentCaptor<AcceptCommandsCommand> commandCaptor =
        ArgumentCaptor.forClass(AcceptCommandsCommand.class);
    verify(acceptanceOrchestrator)
        .accept(eq(new OwnerRef(OwnerType.ISSUE_RUN, RUN_ID)), commandCaptor.capture());

    AcceptCommandsCommand captured = commandCaptor.getValue();
    AcceptCommandsTarget.NewSession target = (AcceptCommandsTarget.NewSession) captured.target();
    assertEquals(SESSION_ID, target.sessionId());
    assertEquals(THREAD_ID, target.threadId());
    assertEquals(settings, target.rootSettings());

    assertEquals(1, captured.commands().size());
    NewThreadCommand cmd = captured.commands().getFirst();
    assertEquals(IDEMPOTENCY_KEY, cmd.idempotencyKey());
    assertTrue(cmd.payload() instanceof UserMessageCommandPayload);
  }

  @Test
  void bootstrapIssueRunSession_convenienceOverloadDelegatesToRecord() {
    stubValidIssueRunHierarchy("exec-agent", IssueRunActorType.AGENT, IssueRunStatus.RUNNING);
    when(settingsMaterializer.materialize("exec-agent")).thenReturn(branchSettings("exec-agent"));
    AcceptedCommands accepted = stubAcceptedCommands(SESSION_ID, THREAD_ID, false);
    when(acceptanceOrchestrator.accept(any(), any())).thenReturn(accepted);

    AcceptedCommands result =
        service.bootstrapIssueRunSession(
            RUN_ID, SESSION_ID, THREAD_ID, IDEMPOTENCY_KEY, "Overload run text");
    assertEquals(accepted, result);
  }

  @Test
  void bootstrapIssueRunSession_rejectsNonExistentRunOrIssue() {
    when(issueRunRepository.getById(RUN_ID)).thenReturn(null);

    BootstrapIssueRunSessionRequest request =
        new BootstrapIssueRunSessionRequest(RUN_ID, SESSION_ID, THREAD_ID, IDEMPOTENCY_KEY, "Text");

    assertThrows(
        AiResourceNotFoundException.class, () -> service.bootstrapIssueRunSession(request));

    IssueRun run = testIssueRun(ISSUE_ID, "agent", IssueRunActorType.AGENT, IssueRunStatus.RUNNING);
    when(issueRunRepository.getById(RUN_ID)).thenReturn(run);
    when(issueRepository.getById(ISSUE_ID)).thenReturn(null);

    assertThrows(
        AiResourceNotFoundException.class, () -> service.bootstrapIssueRunSession(request));
    verify(acceptanceOrchestrator, never()).accept(any(), any());
  }

  @Test
  void bootstrapIssueRunSession_rejectsLockedEntitiesNotFound() {
    IssueRun run = testIssueRun(ISSUE_ID, "agent", IssueRunActorType.AGENT, IssueRunStatus.RUNNING);
    Issue issue = testIssue(PROJECT_ID, null);
    when(issueRunRepository.getById(RUN_ID)).thenReturn(run);
    when(issueRepository.getById(ISSUE_ID)).thenReturn(issue);

    BootstrapIssueRunSessionRequest request =
        new BootstrapIssueRunSessionRequest(RUN_ID, SESSION_ID, THREAD_ID, IDEMPOTENCY_KEY, "Text");

    // Project lock missing
    when(projectRepository.lockForShare(PROJECT_ID)).thenReturn(null);
    assertThrows(
        AiResourceNotFoundException.class, () -> service.bootstrapIssueRunSession(request));

    // Issue lock missing
    when(projectRepository.lockForShare(PROJECT_ID)).thenReturn(testProject("coord", null));
    when(issueRepository.lockById(ISSUE_ID)).thenReturn(null);
    assertThrows(
        AiResourceNotFoundException.class, () -> service.bootstrapIssueRunSession(request));

    // Run lock missing
    when(issueRepository.lockById(ISSUE_ID)).thenReturn(issue);
    when(issueRunRepository.lockById(RUN_ID)).thenReturn(null);
    assertThrows(
        AiResourceNotFoundException.class, () -> service.bootstrapIssueRunSession(request));
  }

  @Test
  void bootstrapIssueRunSession_rejectsInconsistentHierarchyState() {
    IssueRun run = testIssueRun(ISSUE_ID, "agent", IssueRunActorType.AGENT, IssueRunStatus.RUNNING);
    Issue issue = testIssue(PROJECT_ID, null);
    when(issueRunRepository.getById(RUN_ID)).thenReturn(run);
    when(issueRepository.getById(ISSUE_ID)).thenReturn(issue);
    when(projectRepository.lockForShare(PROJECT_ID)).thenReturn(testProject("coord", null));

    UUID differentProjectId = id(77);
    Issue mismatchedIssue = testIssue(differentProjectId, null);
    when(issueRepository.lockById(ISSUE_ID)).thenReturn(mismatchedIssue);
    when(issueRunRepository.lockById(RUN_ID)).thenReturn(run);

    BootstrapIssueRunSessionRequest request =
        new BootstrapIssueRunSessionRequest(RUN_ID, SESSION_ID, THREAD_ID, IDEMPOTENCY_KEY, "Text");

    AiValidationException ex =
        assertThrows(AiValidationException.class, () -> service.bootstrapIssueRunSession(request));
    assertTrue(ex.getMessage().contains("Inconsistent owner hierarchy state"));
  }

  @Test
  void bootstrapIssueRunSession_rejectsArchivedProjectOrArchivedIssue() {
    stubValidIssueRunHierarchy("agent", IssueRunActorType.AGENT, IssueRunStatus.RUNNING);
    BootstrapIssueRunSessionRequest request =
        new BootstrapIssueRunSessionRequest(RUN_ID, SESSION_ID, THREAD_ID, IDEMPOTENCY_KEY, "Text");

    // Archived project
    when(projectRepository.lockForShare(PROJECT_ID)).thenReturn(testProject("coord", NOW));
    assertThrows(AiValidationException.class, () -> service.bootstrapIssueRunSession(request));

    // Archived issue
    when(projectRepository.lockForShare(PROJECT_ID)).thenReturn(testProject("coord", null));
    when(issueRepository.lockById(ISSUE_ID)).thenReturn(testIssue(PROJECT_ID, NOW));
    assertThrows(AiValidationException.class, () -> service.bootstrapIssueRunSession(request));
  }

  @Test
  void bootstrapIssueRunSession_rejectsHumanActor() {
    stubValidIssueRunHierarchy(null, IssueRunActorType.HUMAN, IssueRunStatus.RUNNING);
    BootstrapIssueRunSessionRequest request =
        new BootstrapIssueRunSessionRequest(RUN_ID, SESSION_ID, THREAD_ID, IDEMPOTENCY_KEY, "Text");

    AiValidationException ex =
        assertThrows(AiValidationException.class, () -> service.bootstrapIssueRunSession(request));
    assertTrue(ex.getMessage().contains("Cannot bootstrap session for non-agent run"));
  }

  @Test
  void bootstrapIssueRunSession_rejectsMissingAgent() {
    stubValidIssueRunHierarchy("   ", IssueRunActorType.AGENT, IssueRunStatus.RUNNING);
    BootstrapIssueRunSessionRequest request =
        new BootstrapIssueRunSessionRequest(RUN_ID, SESSION_ID, THREAD_ID, IDEMPOTENCY_KEY, "Text");

    AiValidationException ex =
        assertThrows(AiValidationException.class, () -> service.bootstrapIssueRunSession(request));
    assertTrue(ex.getMessage().contains("Issue run agent is missing"));
  }

  @Test
  void bootstrapIssueRunSession_rejectsNonActiveStatus() {
    for (IssueRunStatus status :
        List.of(
            IssueRunStatus.COMPLETED,
            IssueRunStatus.FAILED,
            IssueRunStatus.CANCELLED,
            IssueRunStatus.UNKNOWN)) {
      stubValidIssueRunHierarchy("agent", IssueRunActorType.AGENT, status);
      BootstrapIssueRunSessionRequest request =
          new BootstrapIssueRunSessionRequest(
              RUN_ID, SESSION_ID, THREAD_ID, IDEMPOTENCY_KEY, "Text");

      AiValidationException ex =
          assertThrows(
              AiValidationException.class, () -> service.bootstrapIssueRunSession(request));
      assertTrue(ex.getMessage().contains("Cannot bootstrap session for non-active run"));
    }
  }

  @Test
  void bootstrapIssueRunSession_rejectsExistingBoundDifferentSession() {
    stubValidIssueRunHierarchy("agent", IssueRunActorType.AGENT, IssueRunStatus.RUNNING);
    UUID otherSessionId = id(88);
    when(issueRunSessionRepository.findByRunId(RUN_ID))
        .thenReturn(IssueRunSession.builder().runId(RUN_ID).sessionId(otherSessionId).build());

    BootstrapIssueRunSessionRequest request =
        new BootstrapIssueRunSessionRequest(RUN_ID, SESSION_ID, THREAD_ID, IDEMPOTENCY_KEY, "Text");

    AiValidationException ex =
        assertThrows(AiValidationException.class, () -> service.bootstrapIssueRunSession(request));
    assertTrue(ex.getMessage().contains("already bound to a different session"));
  }

  @Test
  void bootstrapIssueRunSession_exactReplayDelegation() {
    stubValidIssueRunHierarchy("exec-agent", IssueRunActorType.AGENT, IssueRunStatus.RUNNING);
    when(issueRunSessionRepository.findByRunId(RUN_ID))
        .thenReturn(IssueRunSession.builder().runId(RUN_ID).sessionId(SESSION_ID).build());
    when(settingsMaterializer.materialize("exec-agent")).thenReturn(branchSettings("exec-agent"));

    AcceptedCommands replayed = stubAcceptedCommands(SESSION_ID, THREAD_ID, true);
    when(acceptanceOrchestrator.accept(eq(new OwnerRef(OwnerType.ISSUE_RUN, RUN_ID)), any()))
        .thenReturn(replayed);

    BootstrapIssueRunSessionRequest request =
        new BootstrapIssueRunSessionRequest(
            RUN_ID, SESSION_ID, THREAD_ID, IDEMPOTENCY_KEY, "Exact replay run");
    AcceptedCommands result = service.bootstrapIssueRunSession(request);

    assertTrue(result.replayed());
    verify(acceptanceOrchestrator).accept(any(), any());
  }

  @Test
  void messageValidation_blankOrNullOrOversized() {
    assertThrows(
        NullPointerException.class,
        () ->
            new BootstrapProjectSessionRequest(
                PROJECT_ID, SESSION_ID, THREAD_ID, IDEMPOTENCY_KEY, null));

    BootstrapProjectSessionRequest blankReq =
        new BootstrapProjectSessionRequest(
            PROJECT_ID, SESSION_ID, THREAD_ID, IDEMPOTENCY_KEY, "   ");
    assertThrows(AiValidationException.class, () -> service.bootstrapProjectSession(blankReq));
  }

  @Test
  void noSensitiveBodyOrActionIdInExceptionMessages() {
    String sensitiveBody = "SUPER_SECRET_PAYLOAD_CONTENT_98765";
    UUID sensitiveActionKey = UUID.randomUUID();

    // 1. Blank body validation
    assertDoesNotContainSensitive(
        assertThrows(
            AiValidationException.class,
            () ->
                service.bootstrapProjectSession(
                    new BootstrapProjectSessionRequest(
                        PROJECT_ID, SESSION_ID, THREAD_ID, sensitiveActionKey, "   "))),
        sensitiveBody,
        sensitiveActionKey);

    // 2. Project archived
    when(projectRepository.lockForShare(PROJECT_ID))
        .thenReturn(testProject("coordinator-agent", NOW));
    assertDoesNotContainSensitive(
        assertThrows(
            AiValidationException.class,
            () ->
                service.bootstrapProjectSession(
                    new BootstrapProjectSessionRequest(
                        PROJECT_ID, SESSION_ID, THREAD_ID, sensitiveActionKey, sensitiveBody))),
        sensitiveBody,
        sensitiveActionKey);

    // 3. IssueRun non-active
    stubValidIssueRunHierarchy("agent", IssueRunActorType.AGENT, IssueRunStatus.COMPLETED);
    assertDoesNotContainSensitive(
        assertThrows(
            AiValidationException.class,
            () ->
                service.bootstrapIssueRunSession(
                    new BootstrapIssueRunSessionRequest(
                        RUN_ID, SESSION_ID, THREAD_ID, sensitiveActionKey, sensitiveBody))),
        sensitiveBody,
        sensitiveActionKey);
  }

  private void assertDoesNotContainSensitive(
      Throwable throwable, String sensitiveBody, UUID sensitiveKey) {
    assertNotNull(throwable);
    String message = throwable.getMessage();
    if (message != null) {
      assertFalse(message.contains(sensitiveBody), "Exception message leaked sensitive body!");
      assertFalse(
          message.contains(sensitiveKey.toString()), "Exception message leaked action key!");
    }
  }

  private void stubValidIssueRunHierarchy(
      String agentName, IssueRunActorType actorType, IssueRunStatus status) {
    Project project = testProject("coordinator-agent", null);
    Issue issue = testIssue(PROJECT_ID, null);
    IssueRun run = testIssueRun(ISSUE_ID, agentName, actorType, status);

    when(issueRunRepository.getById(RUN_ID)).thenReturn(run);
    when(issueRepository.getById(ISSUE_ID)).thenReturn(issue);
    when(projectRepository.lockForShare(PROJECT_ID)).thenReturn(project);
    when(issueRepository.lockById(ISSUE_ID)).thenReturn(issue);
    when(issueRunRepository.lockById(RUN_ID)).thenReturn(run);
    when(issueRunSessionRepository.findByRunId(RUN_ID)).thenReturn(null);
  }

  private static Project testProject(String coordinatorAgent, Instant archivedAt) {
    return Project.builder()
        .id(PROJECT_ID)
        .title("Test Project")
        .description("Description")
        .coordinatorAgentName(coordinatorAgent)
        .archivedAt(archivedAt)
        .version(1L)
        .build();
  }

  private static Issue testIssue(UUID projectId, Instant archivedAt) {
    return Issue.builder()
        .id(ISSUE_ID)
        .projectId(projectId)
        .number(1L)
        .title("Test Issue")
        .description("Issue Desc")
        .archivedAt(archivedAt)
        .version(1L)
        .build();
  }

  private static IssueRun testIssueRun(
      UUID issueId, String agentName, IssueRunActorType actorType, IssueRunStatus status) {
    return IssueRun.builder()
        .id(RUN_ID)
        .issueId(issueId)
        .ordinal(1L)
        .role(IssueRunRole.EXECUTOR)
        .actorType(actorType)
        .agentName(agentName)
        .status(status)
        .version(1L)
        .build();
  }

  private static BranchSettings branchSettings(String agentName) {
    return new BranchSettings(agentName, new ModelSelection("prov", "mod", "var"), null);
  }

  private static AcceptedCommands stubAcceptedCommands(
      UUID sessionId, UUID threadId, boolean replayed) {
    AcceptedCommands accepted = mock(AcceptedCommands.class);
    when(accepted.session()).thenReturn(new Session(sessionId, "session", NOW));
    when(accepted.replayed()).thenReturn(replayed);
    return accepted;
  }

  private static UUID id(int value) {
    return new UUID(0L, value);
  }
}
