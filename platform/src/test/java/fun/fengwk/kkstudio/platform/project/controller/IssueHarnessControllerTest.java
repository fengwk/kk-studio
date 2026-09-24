package fun.fengwk.kkstudio.platform.project.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import fun.fengwk.kkstudio.harness.runtime.AcceptCommandsCommand;
import fun.fengwk.kkstudio.harness.runtime.AcceptCommandsTarget;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.harness.runtime.SetThreadYoloCommand;
import fun.fengwk.kkstudio.harness.runtime.StopCommand;
import fun.fengwk.kkstudio.harness.runtime.ThreadSnapshot;
import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnEndOutcome;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnStartReason;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.history.MessagePayload;
import fun.fengwk.kkstudio.harness.runtime.history.ToolResultMetadata;
import fun.fengwk.kkstudio.harness.runtime.history.ToolResultStatus;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndPayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnStartPayload;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.model.ModelCost;
import fun.fengwk.kkstudio.harness.runtime.model.ModelUsage;
import fun.fengwk.kkstudio.harness.runtime.model.provider.GenerationStopReason;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.AssistantMessageMetadata;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.thread.command.NewThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommand;
import fun.fengwk.kkstudio.platform.orchestration.HarnessCommandAcceptanceOrchestrator;
import fun.fengwk.kkstudio.platform.orchestration.OwnerRef;
import fun.fengwk.kkstudio.platform.orchestration.OwnerType;
import fun.fengwk.kkstudio.platform.project.controller.IssueHarnessController.Inspection;
import fun.fengwk.kkstudio.platform.project.controller.IssueHarnessController.InspectionStatus;
import fun.fengwk.kkstudio.platform.project.controller.IssueHarnessController.QualifiedSubmission;
import fun.fengwk.kkstudio.platform.project.model.Issue;
import fun.fengwk.kkstudio.platform.project.model.IssueActivity;
import fun.fengwk.kkstudio.platform.project.model.IssueActivityActorType;
import fun.fengwk.kkstudio.platform.project.model.IssueActivityKind;
import fun.fengwk.kkstudio.platform.project.model.IssueAgentSession;
import fun.fengwk.kkstudio.platform.project.model.IssueRun;
import fun.fengwk.kkstudio.platform.project.model.IssueRunRole;
import fun.fengwk.kkstudio.platform.project.model.IssueRunStatus;
import fun.fengwk.kkstudio.platform.project.model.Project;
import fun.fengwk.kkstudio.platform.project.repo.IssueAgentSessionRepository;
import fun.fengwk.kkstudio.platform.project.session.BootstrapIssueAgentSessionRequest;
import fun.fengwk.kkstudio.platform.project.session.ProjectHarnessSessionBootstrapService;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Issue Controller 的 Harness bridge 单元契约，覆盖投影、幂等命令、脱敏和 post-commit stop。 */
class IssueHarnessControllerTest {

  private static final Instant NOW = Instant.parse("2026-09-13T10:00:00Z");

  private IssueAgentSessionRepository issueAgentSessionRepository;
  private ProjectHarnessSessionBootstrapService bootstrapService;
  private HarnessCommandAcceptanceOrchestrator acceptanceOrchestrator;
  private HarnessRuntime harnessRuntime;
  private ObjectProvider<HarnessRuntime> harnessRuntimes;
  private IssueHarnessController controller;

  @BeforeEach
  void setUp() {
    issueAgentSessionRepository = mock(IssueAgentSessionRepository.class);
    bootstrapService = mock(ProjectHarnessSessionBootstrapService.class);
    acceptanceOrchestrator = mock(HarnessCommandAcceptanceOrchestrator.class);
    harnessRuntime = mock(HarnessRuntime.class);
    harnessRuntimes = mock(ObjectProvider.class);
    when(harnessRuntimes.getIfAvailable()).thenReturn(harnessRuntime);
    controller =
        new IssueHarnessController(
            issueAgentSessionRepository, bootstrapService, acceptanceOrchestrator, harnessRuntimes);
  }

  @AfterEach
  void clearTransactionSynchronization() {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.clearSynchronization();
    }
  }

  @Test
  void bootstrapBuildsRoleSpecificReplaySafeRequestAndSanitizesFailure() {
    // 测试意图：验证随机 Session 身份、稳定初始 command key、角色消息与异常脱敏。
    Project project = project();
    Issue issue = issue();
    IssueRun executor = run(IssueRunRole.EXECUTOR);
    controller.bootstrap(project, issue, executor);

    ArgumentCaptor<BootstrapIssueAgentSessionRequest> requestCaptor =
        ArgumentCaptor.forClass(BootstrapIssueAgentSessionRequest.class);
    verify(bootstrapService).bootstrapIssueAgentSession(requestCaptor.capture());
    BootstrapIssueAgentSessionRequest request = requestCaptor.getValue();
    assertEquals(issue.getId(), request.issueId());
    assertEquals(executor.getAgentName(), request.agentName());
    assertEquals(
        IssueHarnessController.initialCommandKey(executor.getId()),
        request.initialCommandIdempotencyKey());
    assertEquals("Execute issue #7: Title", request.initialMessage());
    assertNotEquals(request.sessionId(), request.threadId());

    reset(bootstrapService);
    IssueRun reviewer = run(IssueRunRole.REVIEWER);
    reviewer.setAgentName("reviewer-agent");
    controller.bootstrap(project, issue, reviewer);
    verify(bootstrapService).bootstrapIssueAgentSession(requestCaptor.capture());
    assertEquals("Review issue #7: Title", requestCaptor.getValue().initialMessage());

    doThrow(new IllegalArgumentException("private-value"))
        .when(bootstrapService)
        .bootstrapIssueAgentSession(any());
    IllegalStateException failure =
        assertThrows(
            IllegalStateException.class, () -> controller.bootstrap(project, issue, reviewer));
    assertEquals("Harness session bootstrap failed", failure.getMessage());
    assertNull(failure.getCause());
    assertFalse(failure.toString().contains("private-value"));
  }

  @Test
  void optionalBootstrapOnlyRunsInHarnessCapableDeployment() {
    // 测试意图：platform-only 组合根可推进 durable Run；web 组合根存在 Runtime 时才原子引导 Session。
    Project project = project();
    Issue issue = issue();
    IssueRun run = run(IssueRunRole.EXECUTOR);
    when(harnessRuntimes.getIfAvailable()).thenReturn(null);
    controller.bootstrapIfAvailable(project, issue, run);
    verify(bootstrapService, never()).bootstrapIssueAgentSession(any());

    when(harnessRuntimes.getIfAvailable()).thenReturn(harnessRuntime);
    controller.bootstrapIfAvailable(project, issue, run);
    verify(bootstrapService).bootstrapIssueAgentSession(any());
  }

  @Test
  void inspectClassifiesMissingUnknownProcessingAndQuiescentStates() {
    // 测试意图：验证 bridge 只按权威 snapshot 分类，并识别三类 UNKNOWN 来源。
    Issue issue = issue();
    IssueRun run = run(IssueRunRole.EXECUTOR);
    assertEquals(InspectionStatus.MISSING_SESSION, controller.inspect(issue, run).status());

    UUID sessionId = UUID.randomUUID();
    UUID threadId = UUID.randomUUID();
    IssueAgentSession agentSession =
        IssueAgentSession.builder()
            .id(UUID.randomUUID())
            .issueId(issue.getId())
            .agentName(run.getAgentName())
            .sessionId(sessionId)
            .threadId(threadId)
            .build();
    when(issueAgentSessionRepository.findByIssueIdAndAgentName(issue.getId(), run.getAgentName()))
        .thenReturn(agentSession);

    when(harnessRuntime.getThreadSnapshot(threadId)).thenReturn(null);
    assertEquals(InspectionStatus.MISSING_THREAD, controller.inspect(issue, run).status());

    ThreadState threadState = thread(sessionId, NOW);
    ThreadSnapshot snapshot = baseSnapshot(threadState);
    ModelInvocation unknownModel = mock(ModelInvocation.class);
    when(unknownModel.status()).thenReturn(ModelInvocationStatus.UNKNOWN);
    when(snapshot.model()).thenReturn(unknownModel);
    when(harnessRuntime.getThreadSnapshot(threadId)).thenReturn(snapshot);
    assertEquals(InspectionStatus.UNKNOWN, controller.inspect(issue, run).status());

    reset(snapshot);
    ToolInvocation unknownTool = mock(ToolInvocation.class);
    when(unknownTool.status()).thenReturn(ToolInvocationStatus.UNKNOWN);
    stubSnapshot(snapshot, threadState);
    when(snapshot.toolSiblings()).thenReturn(List.of(unknownTool));
    assertEquals(InspectionStatus.UNKNOWN, controller.inspect(issue, run).status());

    reset(snapshot);
    MessagePayload message = mock(MessagePayload.class);
    ToolResultMetadata metadata = mock(ToolResultMetadata.class);
    Entry messageEntry = mock(Entry.class);
    when(metadata.status()).thenReturn(ToolResultStatus.UNKNOWN);
    when(message.toolResultMetadata()).thenReturn(metadata);
    when(messageEntry.payload()).thenReturn(message);
    stubSnapshot(snapshot, threadState);
    when(snapshot.entryPath().entries()).thenReturn(List.of(messageEntry));
    assertEquals(InspectionStatus.UNKNOWN, controller.inspect(issue, run).status());

    reset(snapshot);
    stubSnapshot(snapshot, threadState);
    when(snapshot.queuedCommands()).thenReturn(List.of(mock(ThreadCommand.class)));
    assertEquals(InspectionStatus.PROCESSING, controller.inspect(issue, run).status());

    reset(snapshot);
    stubSnapshot(snapshot, threadState);
    Inspection inspection = controller.inspect(issue, run);
    assertEquals(InspectionStatus.QUIESCENT, inspection.status());
    assertEquals(snapshot, inspection.requireQuiescentSnapshot());
  }

  @Test
  void alignThreadYoloDelegatesToRuntime() {
    // 测试意图：验证 YOLO 对齐命令正确传递 threadId、版本号与目标开关。
    UUID threadId = UUID.randomUUID();
    when(harnessRuntime.setThreadYolo(new SetThreadYoloCommand(threadId, 2L, true)))
        .thenReturn(mock(ThreadState.class));
    assertTrue(controller.alignThreadYolo(threadId, 2L, true));

    doThrow(new IllegalStateException("conflict"))
        .when(harnessRuntime)
        .setThreadYolo(new SetThreadYoloCommand(threadId, 2L, false));
    assertFalse(controller.alignThreadYolo(threadId, 2L, false));
  }

  @Test
  void findQualifiedSubmissionValidatesStrictContract() {
    // 测试意图：验证只有完全符合 qualified turn 条件的输出才会被识别为 submission。
    IssueRun run = run(IssueRunRole.EXECUTOR);
    run.setCreatedAt(NOW);
    UUID threadId = UUID.randomUUID();
    ThreadState threadState = thread(UUID.randomUUID(), NOW);
    ThreadSnapshot snapshot = baseSnapshot(threadState);

    // 1. 无 TurnEnd -> null
    assertNull(controller.findQualifiedSubmission(run, snapshot, false));

    // 2. 有合规的 TurnEnd 与 Assistant 消息 -> 返回 QualifiedSubmission
    UUID turnStartId = UUID.randomUUID();
    UUID turnEndId = UUID.randomUUID();
    TurnStartPayload turnStartPayload =
        new TurnStartPayload(
            TurnStartReason.INPUT,
            new BranchSettings(
                "executor", new ModelSelection("provider", "model", "default"), null),
            threadState.id());
    Entry turnStartEntry = mock(Entry.class);
    when(turnStartEntry.id()).thenReturn(turnStartId);
    when(turnStartEntry.payload()).thenReturn(turnStartPayload);
    when(turnStartEntry.createdAt()).thenReturn(NOW.plusSeconds(1));

    TextMessageContent textContent = new TextMessageContent("Work completed successfully.");
    AgentMessage assistantMsg = new AgentMessage(AgentMessageRole.ASSISTANT, List.of(textContent));
    AssistantMessageMetadata metadata =
        new AssistantMessageMetadata(
            GenerationStopReason.COMPLETE,
            new ModelUsage(10L, 20L, 0L, 0L, 0L, 0L, 30L),
            new ModelCost(
                "USD",
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO));
    MessagePayload messagePayload = new MessagePayload(assistantMsg, metadata, null);
    Entry assistantEntry = mock(Entry.class);
    when(assistantEntry.id()).thenReturn(UUID.randomUUID());
    when(assistantEntry.payload()).thenReturn(messagePayload);
    when(assistantEntry.createdAt()).thenReturn(NOW.plusSeconds(2));

    TurnEndPayload turnEndPayload =
        new TurnEndPayload(turnStartId, TurnEndOutcome.COMPLETED, false, null, null);
    Entry turnEndEntry = mock(Entry.class);
    when(turnEndEntry.id()).thenReturn(turnEndId);
    when(turnEndEntry.payload()).thenReturn(turnEndPayload);
    when(turnEndEntry.createdAt()).thenReturn(NOW.plusSeconds(3));

    when(snapshot.entryPath().entries())
        .thenReturn(List.of(turnStartEntry, assistantEntry, turnEndEntry));

    QualifiedSubmission submission = controller.findQualifiedSubmission(run, snapshot, false);
    assertNotNull(submission);
    assertEquals(turnEndId, submission.finalEntryId());
    assertEquals("Work completed successfully.", submission.summary());

    // 3. 若有未处理的 targeted activities -> 不应作为 submission
    assertNull(controller.findQualifiedSubmission(run, snapshot, true));
  }

  @Test
  void deliverActivityAndSystemContinuationUseDeterministicKeys() {
    // 测试意图：验证 activity 与 system continuation 使用正确的 owner、target、正文和幂等键。
    Issue issue = issue();
    IssueRun run = run(IssueRunRole.EXECUTOR);
    run.setContinuationCount(2);
    run.setObservedActivitySequence(5L);
    IssueAgentSession agentSession =
        IssueAgentSession.builder()
            .id(UUID.randomUUID())
            .issueId(issue.getId())
            .agentName(run.getAgentName())
            .sessionId(UUID.randomUUID())
            .threadId(UUID.randomUUID())
            .build();
    ThreadState thread = thread(agentSession.getSessionId(), NOW);
    ThreadSnapshot snapshot = baseSnapshot(thread);

    IssueActivity activity =
        IssueActivity.builder()
            .issueId(issue.getId())
            .sequence(6L)
            .kind(IssueActivityKind.HUMAN_INPUT)
            .actorType(IssueActivityActorType.HUMAN)
            .body("Please update the requirement")
            .build();

    controller.deliverActivity(issue, run, activity, agentSession, snapshot);
    controller.sendSystemContinuation(issue, run, agentSession, snapshot);

    ArgumentCaptor<OwnerRef> ownerCaptor = ArgumentCaptor.forClass(OwnerRef.class);
    ArgumentCaptor<AcceptCommandsCommand> commandCaptor =
        ArgumentCaptor.forClass(AcceptCommandsCommand.class);
    verify(acceptanceOrchestrator, times(2)).accept(ownerCaptor.capture(), commandCaptor.capture());

    assertEquals(
        List.of(
            new OwnerRef(OwnerType.ISSUE_AGENT_SESSION, agentSession.getId()),
            new OwnerRef(OwnerType.ISSUE_AGENT_SESSION, agentSession.getId())),
        ownerCaptor.getAllValues());

    AcceptCommandsCommand activityBatch = commandCaptor.getAllValues().get(0);
    assertThreadCursor(activityBatch, thread);
    NewThreadCommand activityCommand = activityBatch.commands().getFirst();
    assertEquals(
        IssueHarnessController.activityDeliveryKey(run.getId(), 6L),
        activityCommand.idempotencyKey());

    AcceptCommandsCommand systemBatch = commandCaptor.getAllValues().get(1);
    assertThreadCursor(systemBatch, thread);
    NewThreadCommand systemCommand = systemBatch.commands().getFirst();
    assertEquals(
        IssueHarnessController.continuationKey(run.getId(), 2, 5L, "SYSTEM"),
        systemCommand.idempotencyKey());
  }

  @Test
  void stopRunsAfterCommitAndContinuesPastIndividualFailures() {
    // 测试意图：事务内只登记回调；提交后按稳定顺序 best-effort 停止所有 Thread。
    Issue issue = issue();
    IssueRun run = run(IssueRunRole.EXECUTOR);
    UUID sessionId = UUID.randomUUID();
    IssueAgentSession agentSession =
        IssueAgentSession.builder()
            .id(UUID.randomUUID())
            .issueId(issue.getId())
            .agentName(run.getAgentName())
            .sessionId(sessionId)
            .threadId(UUID.randomUUID())
            .build();
    when(issueAgentSessionRepository.findByIssueIdAndAgentName(issue.getId(), run.getAgentName()))
        .thenReturn(agentSession);

    ThreadState later = thread(sessionId, NOW.plusSeconds(1));
    ThreadState earliest = thread(sessionId, NOW);
    when(harnessRuntime.listThreadsBySession(sessionId)).thenReturn(List.of(later, earliest));
    doThrow(new IllegalStateException("private-value"))
        .when(harnessRuntime)
        .stop(any(StopCommand.class));

    TransactionSynchronizationManager.initSynchronization();
    controller.stopAfterCommit(issue.getId(), run.getAgentName());
    verify(harnessRuntime, never()).listThreadsBySession(any());
    List<TransactionSynchronization> synchronizations =
        TransactionSynchronizationManager.getSynchronizations();
    TransactionSynchronizationManager.clearSynchronization();
    assertEquals(1, synchronizations.size());
    synchronizations.getFirst().afterCommit();

    ArgumentCaptor<StopCommand> captor = ArgumentCaptor.forClass(StopCommand.class);
    verify(harnessRuntime, times(2)).stop(captor.capture());
    assertEquals(earliest.id(), captor.getAllValues().get(0).threadId());
    assertEquals(later.id(), captor.getAllValues().get(1).threadId());
  }

  @Test
  void stopWithoutTransactionIsImmediateAndMissingRelationIsNoOp() {
    // 测试意图：无 Spring synchronization 时立即 stop，缺失归属关系时不触碰 Runtime。
    Issue issue = issue();
    IssueRun run = run(IssueRunRole.EXECUTOR);
    controller.stopAfterCommit(issue.getId(), run.getAgentName());
    verify(harnessRuntime, never()).listThreadsBySession(any());
  }

  @Test
  void inspectionAndIdempotencyHelpersRejectInvalidUsage() {
    // 测试意图：Inspection 形状不变量和 deterministic key 输入差异不会产生别名。
    assertThrows(
        IllegalArgumentException.class,
        () -> new Inspection(InspectionStatus.QUIESCENT, null, null));
    Inspection missing = new Inspection(InspectionStatus.MISSING_SESSION, null, null);
    assertThrows(IllegalStateException.class, missing::requireQuiescentSnapshot);

    UUID runId = UUID.randomUUID();
    assertEquals(
        IssueHarnessController.initialCommandKey(runId),
        IssueHarnessController.initialCommandKey(runId));
    assertNotEquals(
        IssueHarnessController.continuationKey(runId, 1, 2L, "USER"),
        IssueHarnessController.continuationKey(runId, 1, 2L, "SYSTEM"));
  }

  private ThreadSnapshot baseSnapshot(ThreadState thread) {
    ThreadSnapshot snapshot = mock(ThreadSnapshot.class);
    stubSnapshot(snapshot, thread);
    return snapshot;
  }

  private void stubSnapshot(ThreadSnapshot snapshot, ThreadState thread) {
    EntryPath path = mock(EntryPath.class);
    Entry head = mock(Entry.class);
    when(path.entries()).thenReturn(List.of());
    when(path.openTurnStart()).thenReturn(Optional.empty());
    when(path.head()).thenReturn(head);
    when(snapshot.thread()).thenReturn(thread);
    when(snapshot.entryPath()).thenReturn(path);
    when(snapshot.queuedCommands()).thenReturn(List.of());
    when(snapshot.toolSiblings()).thenReturn(List.of());
  }

  private void assertThreadCursor(AcceptCommandsCommand batch, ThreadState thread) {
    AcceptCommandsTarget.Thread target = (AcceptCommandsTarget.Thread) batch.target();
    assertEquals(thread.id(), target.threadId());
    assertEquals(thread.headEntryId(), target.expectedHeadEntryId());
    assertEquals(thread.nextCommandSequence(), target.expectedNextCommandSequence());
  }

  private Project project() {
    return Project.builder()
        .id(UUID.randomUUID())
        .title("Project")
        .yoloEnabled(true)
        .maxReviewRejections(3)
        .version(0L)
        .build();
  }

  private Issue issue() {
    Project project = project();
    return Issue.builder()
        .id(UUID.randomUUID())
        .projectId(project.getId())
        .number(7L)
        .title("Title")
        .assigneeAgentName("executor-agent")
        .reviewerAgentName("reviewer-agent")
        .version(0L)
        .build();
  }

  private IssueRun run(IssueRunRole role) {
    return IssueRun.builder()
        .id(UUID.randomUUID())
        .issueId(UUID.randomUUID())
        .ordinal(1L)
        .role(role)
        .agentName("executor-agent")
        .status(IssueRunStatus.RUNNING)
        .continuationCount(0)
        .maxContinuations(3)
        .version(0L)
        .build();
  }

  private ThreadState thread(UUID sessionId, Instant createdAt) {
    return new ThreadState(
        UUID.randomUUID(),
        sessionId,
        UUID.randomUUID(),
        "a".repeat(64),
        "main",
        false,
        4L,
        2L,
        createdAt,
        createdAt);
  }
}
