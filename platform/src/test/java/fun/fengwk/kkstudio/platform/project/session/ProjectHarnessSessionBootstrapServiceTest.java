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
import fun.fengwk.kkstudio.platform.project.model.IssueAgentSession;
import fun.fengwk.kkstudio.platform.project.model.Project;
import fun.fengwk.kkstudio.platform.project.repo.IssueAgentSessionRepository;
import fun.fengwk.kkstudio.platform.project.repo.IssueRepository;
import fun.fengwk.kkstudio.platform.project.repo.ProjectRepository;
import fun.fengwk.kkstudio.platform.project.service.IssueEvidenceService;

import java.time.Instant;
import java.util.UUID;

/**
 * {@link ProjectHarnessSessionBootstrapService} 单元测试。
 *
 * <p>测试意图：
 *
 * <ul>
 *   <li>验证权威 Agent 与 BranchSettings 的正确物化与选择；
 *   <li>验证 target 与 command 的结构、UUID 幂等键与 canonical requestHash 计算；
 *   <li>验证已归档项目/Issue、未指派 Agent、绑定不同 Session、所有权层级不一致等严格前置拒绝；
 *   <li>验证同 Owner 精确重放的正确委托行为；
 *   <li>验证异常消息脱敏，绝不回显初始用户消息正文或 action/idempotency UUID。
 * </ul>
 */
class ProjectHarnessSessionBootstrapServiceTest {

  private static final UUID PROJECT_ID = id(1);
  private static final UUID ISSUE_ID = id(2);
  private static final UUID AGENT_SESSION_ID = id(3);
  private static final UUID SESSION_ID = id(4);
  private static final UUID THREAD_ID = id(5);
  private static final UUID IDEMPOTENCY_KEY = id(6);
  private static final Instant NOW = Instant.parse("2026-09-13T10:00:00Z");

  private ProjectRepository projectRepository;
  private IssueRepository issueRepository;
  private IssueAgentSessionRepository issueAgentSessionRepository;
  private AgentBranchSettingsMaterializer settingsMaterializer;
  private HarnessCommandAcceptanceOrchestrator acceptanceOrchestrator;
  private IssueEvidenceService issueEvidenceService;
  private ProjectHarnessSessionBootstrapService service;

  @BeforeEach
  void setUp() {
    projectRepository = mock(ProjectRepository.class);
    issueRepository = mock(IssueRepository.class);
    issueAgentSessionRepository = mock(IssueAgentSessionRepository.class);
    settingsMaterializer = mock(AgentBranchSettingsMaterializer.class);
    acceptanceOrchestrator = mock(HarnessCommandAcceptanceOrchestrator.class);
    issueEvidenceService = mock(IssueEvidenceService.class);

    service =
        new ProjectHarnessSessionBootstrapService(
            projectRepository,
            issueRepository,
            issueAgentSessionRepository,
            settingsMaterializer,
            acceptanceOrchestrator,
            issueEvidenceService);
  }

  @Test
  void bootstrapIssueAgentSession_success_selectsAuthoritativeAgentAndMaterializesSettings() {
    stubValidIssueHierarchy("executor-agent", "reviewer-agent");

    BranchSettings settings = branchSettings("executor-agent");
    when(settingsMaterializer.materialize("executor-agent")).thenReturn(settings);

    when(issueAgentSessionRepository.findByIssueIdAndAgentName(ISSUE_ID, "executor-agent"))
        .thenReturn(null);
    when(issueAgentSessionRepository.bindOrGet(any(IssueAgentSession.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    AcceptedCommands accepted = stubAcceptedCommands(SESSION_ID, THREAD_ID, false);
    when(acceptanceOrchestrator.accept(
            eq(new OwnerRef(OwnerType.ISSUE_AGENT_SESSION, AGENT_SESSION_ID)), any()))
        .thenReturn(accepted);

    String initialMessage = "  Execute issue task\n";
    BootstrapIssueAgentSessionRequest request =
        new BootstrapIssueAgentSessionRequest(
            ISSUE_ID, "executor-agent", SESSION_ID, THREAD_ID, IDEMPOTENCY_KEY, initialMessage);

    // Mock bindOrGet to return agentSession with AGENT_SESSION_ID
    when(issueAgentSessionRepository.bindOrGet(any(IssueAgentSession.class)))
        .thenReturn(
            IssueAgentSession.builder()
                .id(AGENT_SESSION_ID)
                .issueId(ISSUE_ID)
                .agentName("executor-agent")
                .sessionId(SESSION_ID)
                .threadId(THREAD_ID)
                .build());

    AcceptedCommands result = service.bootstrapIssueAgentSession(request);

    assertEquals(accepted, result);
    assertFalse(result.replayed());

    ArgumentCaptor<AcceptCommandsCommand> commandCaptor =
        ArgumentCaptor.forClass(AcceptCommandsCommand.class);
    verify(acceptanceOrchestrator)
        .accept(
            eq(new OwnerRef(OwnerType.ISSUE_AGENT_SESSION, AGENT_SESSION_ID)),
            commandCaptor.capture());

    AcceptCommandsCommand captured = commandCaptor.getValue();
    AcceptCommandsTarget.NewSession target = (AcceptCommandsTarget.NewSession) captured.target();
    assertEquals(SESSION_ID, target.sessionId());
    assertEquals(THREAD_ID, target.threadId());
    assertEquals(settings, target.rootSettings());
    assertTrue(target.yoloEnabled());

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
  void bootstrapIssueAgentSession_reviewerAgent_success() {
    stubValidIssueHierarchy("executor-agent", "reviewer-agent");

    BranchSettings settings = branchSettings("reviewer-agent");
    when(settingsMaterializer.materialize("reviewer-agent")).thenReturn(settings);

    when(issueAgentSessionRepository.findByIssueIdAndAgentName(ISSUE_ID, "reviewer-agent"))
        .thenReturn(null);
    when(issueAgentSessionRepository.bindOrGet(any(IssueAgentSession.class)))
        .thenReturn(
            IssueAgentSession.builder()
                .id(AGENT_SESSION_ID)
                .issueId(ISSUE_ID)
                .agentName("reviewer-agent")
                .sessionId(SESSION_ID)
                .threadId(THREAD_ID)
                .build());

    AcceptedCommands accepted = stubAcceptedCommands(SESSION_ID, THREAD_ID, false);
    when(acceptanceOrchestrator.accept(
            eq(new OwnerRef(OwnerType.ISSUE_AGENT_SESSION, AGENT_SESSION_ID)), any()))
        .thenReturn(accepted);

    BootstrapIssueAgentSessionRequest request =
        new BootstrapIssueAgentSessionRequest(
            ISSUE_ID, "reviewer-agent", SESSION_ID, THREAD_ID, IDEMPOTENCY_KEY, "Review task");

    AcceptedCommands result = service.bootstrapIssueAgentSession(request);
    assertEquals(accepted, result);
    verify(acceptanceOrchestrator)
        .accept(eq(new OwnerRef(OwnerType.ISSUE_AGENT_SESSION, AGENT_SESSION_ID)), any());
  }

  @Test
  void bootstrapIssueAgentSession_convenienceOverloadDelegatesToRecord() {
    stubValidIssueHierarchy("exec-agent", "rev-agent");
    when(settingsMaterializer.materialize("exec-agent")).thenReturn(branchSettings("exec-agent"));
    when(issueAgentSessionRepository.bindOrGet(any(IssueAgentSession.class)))
        .thenReturn(
            IssueAgentSession.builder()
                .id(AGENT_SESSION_ID)
                .issueId(ISSUE_ID)
                .agentName("exec-agent")
                .sessionId(SESSION_ID)
                .threadId(THREAD_ID)
                .build());
    AcceptedCommands accepted = stubAcceptedCommands(SESSION_ID, THREAD_ID, false);
    when(acceptanceOrchestrator.accept(any(), any())).thenReturn(accepted);

    AcceptedCommands result =
        service.bootstrapIssueAgentSession(
            ISSUE_ID, "exec-agent", SESSION_ID, THREAD_ID, IDEMPOTENCY_KEY, "Overload text");
    assertEquals(accepted, result);
  }

  @Test
  void bootstrapIssueAgentSession_rejectsNonExistentIssue() {
    when(issueRepository.getById(ISSUE_ID)).thenReturn(null);

    BootstrapIssueAgentSessionRequest request =
        new BootstrapIssueAgentSessionRequest(
            ISSUE_ID, "agent", SESSION_ID, THREAD_ID, IDEMPOTENCY_KEY, "Text");

    assertThrows(
        AiResourceNotFoundException.class, () -> service.bootstrapIssueAgentSession(request));
    verify(acceptanceOrchestrator, never()).accept(any(), any());
  }

  @Test
  void bootstrapIssueAgentSession_rejectsLockedEntitiesNotFound() {
    Issue issue = testIssue(PROJECT_ID, null, "agent", null);
    when(issueRepository.getById(ISSUE_ID)).thenReturn(issue);

    BootstrapIssueAgentSessionRequest request =
        new BootstrapIssueAgentSessionRequest(
            ISSUE_ID, "agent", SESSION_ID, THREAD_ID, IDEMPOTENCY_KEY, "Text");

    // Project lock missing
    when(projectRepository.lockForShare(PROJECT_ID)).thenReturn(null);
    assertThrows(
        AiResourceNotFoundException.class, () -> service.bootstrapIssueAgentSession(request));

    // Issue lock missing
    when(projectRepository.lockForShare(PROJECT_ID)).thenReturn(testProject(null));
    when(issueRepository.lockById(ISSUE_ID)).thenReturn(null);
    assertThrows(
        AiResourceNotFoundException.class, () -> service.bootstrapIssueAgentSession(request));
  }

  @Test
  void bootstrapIssueAgentSession_rejectsInconsistentHierarchyState() {
    Issue issue = testIssue(PROJECT_ID, null, "agent", null);
    when(issueRepository.getById(ISSUE_ID)).thenReturn(issue);
    when(projectRepository.lockForShare(PROJECT_ID)).thenReturn(testProject(null));

    UUID differentProjectId = id(77);
    Issue mismatchedIssue = testIssue(differentProjectId, null, "agent", null);
    when(issueRepository.lockById(ISSUE_ID)).thenReturn(mismatchedIssue);

    BootstrapIssueAgentSessionRequest request =
        new BootstrapIssueAgentSessionRequest(
            ISSUE_ID, "agent", SESSION_ID, THREAD_ID, IDEMPOTENCY_KEY, "Text");

    AiValidationException ex =
        assertThrows(
            AiValidationException.class, () -> service.bootstrapIssueAgentSession(request));
    assertTrue(ex.getMessage().contains("Inconsistent owner hierarchy state"));
  }

  @Test
  void bootstrapIssueAgentSession_rejectsArchivedProjectOrArchivedIssue() {
    stubValidIssueHierarchy("agent", null);
    BootstrapIssueAgentSessionRequest request =
        new BootstrapIssueAgentSessionRequest(
            ISSUE_ID, "agent", SESSION_ID, THREAD_ID, IDEMPOTENCY_KEY, "Text");

    // Archived project
    when(projectRepository.lockForShare(PROJECT_ID)).thenReturn(testProject(NOW));
    assertThrows(AiValidationException.class, () -> service.bootstrapIssueAgentSession(request));

    // Archived issue
    when(projectRepository.lockForShare(PROJECT_ID)).thenReturn(testProject(null));
    when(issueRepository.lockById(ISSUE_ID)).thenReturn(testIssue(PROJECT_ID, NOW, "agent", null));
    assertThrows(AiValidationException.class, () -> service.bootstrapIssueAgentSession(request));
  }

  @Test
  void bootstrapIssueAgentSession_rejectsUnassignedAgent() {
    stubValidIssueHierarchy("assignee-agent", "reviewer-agent");
    BootstrapIssueAgentSessionRequest request =
        new BootstrapIssueAgentSessionRequest(
            ISSUE_ID, "unassigned-agent", SESSION_ID, THREAD_ID, IDEMPOTENCY_KEY, "Text");

    AiValidationException ex =
        assertThrows(
            AiValidationException.class, () -> service.bootstrapIssueAgentSession(request));
    assertTrue(ex.getMessage().contains("is not assigned to issue"));
    verify(acceptanceOrchestrator, never()).accept(any(), any());
  }

  @Test
  void bootstrapIssueAgentSession_rejectsExistingBoundDifferentSession() {
    stubValidIssueHierarchy("agent", null);
    UUID otherSessionId = id(88);
    when(issueAgentSessionRepository.findByIssueIdAndAgentName(ISSUE_ID, "agent"))
        .thenReturn(
            IssueAgentSession.builder()
                .id(AGENT_SESSION_ID)
                .issueId(ISSUE_ID)
                .agentName("agent")
                .sessionId(otherSessionId)
                .threadId(THREAD_ID)
                .build());

    BootstrapIssueAgentSessionRequest request =
        new BootstrapIssueAgentSessionRequest(
            ISSUE_ID, "agent", SESSION_ID, THREAD_ID, IDEMPOTENCY_KEY, "Text");

    AiValidationException ex =
        assertThrows(
            AiValidationException.class, () -> service.bootstrapIssueAgentSession(request));
    assertTrue(ex.getMessage().contains("already bound to a different session"));
    verify(acceptanceOrchestrator, never()).accept(any(), any());
  }

  @Test
  void bootstrapIssueAgentSession_exactReplayDelegation() {
    stubValidIssueHierarchy("exec-agent", null);
    IssueAgentSession existing =
        IssueAgentSession.builder()
            .id(AGENT_SESSION_ID)
            .issueId(ISSUE_ID)
            .agentName("exec-agent")
            .sessionId(SESSION_ID)
            .threadId(THREAD_ID)
            .build();
    when(issueAgentSessionRepository.findByIssueIdAndAgentName(ISSUE_ID, "exec-agent"))
        .thenReturn(existing);
    when(issueAgentSessionRepository.bindOrGet(any(IssueAgentSession.class))).thenReturn(existing);
    when(settingsMaterializer.materialize("exec-agent")).thenReturn(branchSettings("exec-agent"));

    AcceptedCommands replayed = stubAcceptedCommands(SESSION_ID, THREAD_ID, true);
    when(acceptanceOrchestrator.accept(
            eq(new OwnerRef(OwnerType.ISSUE_AGENT_SESSION, AGENT_SESSION_ID)), any()))
        .thenReturn(replayed);

    BootstrapIssueAgentSessionRequest request =
        new BootstrapIssueAgentSessionRequest(
            ISSUE_ID, "exec-agent", SESSION_ID, THREAD_ID, IDEMPOTENCY_KEY, "Exact replay run");
    AcceptedCommands result = service.bootstrapIssueAgentSession(request);

    assertTrue(result.replayed());
    verify(acceptanceOrchestrator).accept(any(), any());
  }

  @Test
  void messageValidation_blankOrNullOrOversized() {
    assertThrows(
        NullPointerException.class,
        () ->
            new BootstrapIssueAgentSessionRequest(
                ISSUE_ID, "agent", SESSION_ID, THREAD_ID, IDEMPOTENCY_KEY, null));

    stubValidIssueHierarchy("agent", null);
    BootstrapIssueAgentSessionRequest blankReq =
        new BootstrapIssueAgentSessionRequest(
            ISSUE_ID, "agent", SESSION_ID, THREAD_ID, IDEMPOTENCY_KEY, "   ");
    assertThrows(AiValidationException.class, () -> service.bootstrapIssueAgentSession(blankReq));

    String oversizedMessage = "m".repeat(1048577);
    BootstrapIssueAgentSessionRequest oversizedReq =
        new BootstrapIssueAgentSessionRequest(
            ISSUE_ID, "agent", SESSION_ID, THREAD_ID, IDEMPOTENCY_KEY, oversizedMessage);
    assertThrows(
        AiValidationException.class, () -> service.bootstrapIssueAgentSession(oversizedReq));
  }

  @Test
  void noSensitiveBodyOrActionIdInExceptionMessages() {
    String sensitiveBody = "SUPER_SECRET_PAYLOAD_CONTENT_98765";
    UUID sensitiveActionKey = UUID.randomUUID();

    // 1. Project archived
    stubValidIssueHierarchy("agent", null);
    when(projectRepository.lockForShare(PROJECT_ID)).thenReturn(testProject(NOW));
    assertDoesNotContainSensitive(
        assertThrows(
            AiValidationException.class,
            () ->
                service.bootstrapIssueAgentSession(
                    new BootstrapIssueAgentSessionRequest(
                        ISSUE_ID,
                        "agent",
                        SESSION_ID,
                        THREAD_ID,
                        sensitiveActionKey,
                        sensitiveBody))),
        sensitiveBody,
        sensitiveActionKey);

    // 2. Unassigned agent
    stubValidIssueHierarchy("assignee", "reviewer");
    assertDoesNotContainSensitive(
        assertThrows(
            AiValidationException.class,
            () ->
                service.bootstrapIssueAgentSession(
                    new BootstrapIssueAgentSessionRequest(
                        ISSUE_ID,
                        "other-agent",
                        SESSION_ID,
                        THREAD_ID,
                        sensitiveActionKey,
                        sensitiveBody))),
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

  private void stubValidIssueHierarchy(String assigneeAgent, String reviewerAgent) {
    Project project = testProject(null);
    Issue issue = testIssue(PROJECT_ID, null, assigneeAgent, reviewerAgent);

    when(issueRepository.getById(ISSUE_ID)).thenReturn(issue);
    when(projectRepository.lockForShare(PROJECT_ID)).thenReturn(project);
    when(issueRepository.lockById(ISSUE_ID)).thenReturn(issue);
  }

  private static Project testProject(Instant archivedAt) {
    return Project.builder()
        .id(PROJECT_ID)
        .title("Test Project")
        .description("Description")
        .yoloEnabled(true)
        .maxReviewRejections(3)
        .archivedAt(archivedAt)
        .version(1L)
        .build();
  }

  private static Issue testIssue(
      UUID projectId, Instant archivedAt, String assigneeAgent, String reviewerAgent) {
    return Issue.builder()
        .id(ISSUE_ID)
        .projectId(projectId)
        .number(1L)
        .title("Test Issue")
        .description("Issue Desc")
        .assigneeAgentName(assigneeAgent)
        .reviewerAgentName(reviewerAgent)
        .archivedAt(archivedAt)
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
