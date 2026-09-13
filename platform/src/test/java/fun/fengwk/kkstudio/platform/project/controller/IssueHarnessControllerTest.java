package fun.fengwk.kkstudio.platform.project.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
import fun.fengwk.kkstudio.harness.runtime.StopCommand;
import fun.fengwk.kkstudio.harness.runtime.ThreadSnapshot;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.history.MessagePayload;
import fun.fengwk.kkstudio.harness.runtime.history.ToolResultMetadata;
import fun.fengwk.kkstudio.harness.runtime.history.ToolResultStatus;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.thread.command.CustomMessageCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.NewThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.thread.command.UserMessageCommandPayload;
import fun.fengwk.kkstudio.platform.orchestration.HarnessCommandAcceptanceOrchestrator;
import fun.fengwk.kkstudio.platform.orchestration.OwnerRef;
import fun.fengwk.kkstudio.platform.orchestration.OwnerType;
import fun.fengwk.kkstudio.platform.project.controller.IssueHarnessController.Inspection;
import fun.fengwk.kkstudio.platform.project.controller.IssueHarnessController.InspectionStatus;
import fun.fengwk.kkstudio.platform.project.model.Issue;
import fun.fengwk.kkstudio.platform.project.model.IssueInput;
import fun.fengwk.kkstudio.platform.project.model.IssueInputKind;
import fun.fengwk.kkstudio.platform.project.model.IssueRun;
import fun.fengwk.kkstudio.platform.project.model.IssueRunRole;
import fun.fengwk.kkstudio.platform.project.model.IssueRunSession;
import fun.fengwk.kkstudio.platform.project.model.IssueRunStatus;
import fun.fengwk.kkstudio.platform.project.model.Project;
import fun.fengwk.kkstudio.platform.project.model.ProjectSession;
import fun.fengwk.kkstudio.platform.project.repo.IssueRunSessionRepository;
import fun.fengwk.kkstudio.platform.project.repo.ProjectSessionRepository;
import fun.fengwk.kkstudio.platform.project.session.BootstrapIssueRunSessionRequest;
import fun.fengwk.kkstudio.platform.project.session.ProjectHarnessSessionBootstrapService;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Issue Controller 的 Harness bridge 单元契约，覆盖投影、幂等命令、脱敏和 post-commit stop。 */
class IssueHarnessControllerTest {

  private static final Instant NOW = Instant.parse("2026-09-13T10:00:00Z");

  private ProjectSessionRepository projectSessionRepository;
  private IssueRunSessionRepository issueRunSessionRepository;
  private ProjectHarnessSessionBootstrapService bootstrapService;
  private HarnessCommandAcceptanceOrchestrator acceptanceOrchestrator;
  private HarnessRuntime harnessRuntime;
  private ObjectProvider<HarnessRuntime> harnessRuntimes;
  private IssueHarnessController controller;

  @BeforeEach
  void setUp() {
    projectSessionRepository = mock(ProjectSessionRepository.class);
    issueRunSessionRepository = mock(IssueRunSessionRepository.class);
    bootstrapService = mock(ProjectHarnessSessionBootstrapService.class);
    acceptanceOrchestrator = mock(HarnessCommandAcceptanceOrchestrator.class);
    harnessRuntime = mock(HarnessRuntime.class);
    harnessRuntimes = mock(ObjectProvider.class);
    when(harnessRuntimes.getIfAvailable()).thenReturn(harnessRuntime);
    controller =
        new IssueHarnessController(
            projectSessionRepository,
            issueRunSessionRepository,
            bootstrapService,
            acceptanceOrchestrator,
            harnessRuntimes);
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
    Issue issue = issue();
    IssueRun executor = run(IssueRunRole.EXECUTOR);
    controller.bootstrap(issue, executor);

    ArgumentCaptor<BootstrapIssueRunSessionRequest> requestCaptor =
        ArgumentCaptor.forClass(BootstrapIssueRunSessionRequest.class);
    verify(bootstrapService).bootstrapIssueRunSession(requestCaptor.capture());
    BootstrapIssueRunSessionRequest request = requestCaptor.getValue();
    assertEquals(executor.getId(), request.runId());
    assertEquals(
        IssueHarnessController.initialCommandKey(executor.getId()),
        request.initialCommandIdempotencyKey());
    assertEquals("Execute issue #7: Title", request.initialMessage());
    assertNotEquals(request.sessionId(), request.threadId());

    reset(bootstrapService);
    IssueRun reviewer = run(IssueRunRole.REVIEWER);
    controller.bootstrap(issue, reviewer);
    verify(bootstrapService).bootstrapIssueRunSession(requestCaptor.capture());
    assertEquals("Review issue #7: Title", requestCaptor.getValue().initialMessage());

    doThrow(new IllegalArgumentException("private-value"))
        .when(bootstrapService)
        .bootstrapIssueRunSession(any());
    IllegalStateException failure =
        assertThrows(IllegalStateException.class, () -> controller.bootstrap(issue, reviewer));
    assertEquals("Harness session bootstrap failed", failure.getMessage());
    assertNull(failure.getCause());
    assertFalse(failure.toString().contains("private-value"));
  }

  @Test
  void optionalBootstrapOnlyRunsInHarnessCapableDeployment() {
    // 测试意图：platform-only 组合根可推进 durable Run；web 组合根存在 Runtime 时才原子引导 Session。
    Issue issue = issue();
    IssueRun run = run(IssueRunRole.EXECUTOR);
    when(harnessRuntimes.getIfAvailable()).thenReturn(null);
    controller.bootstrapIfAvailable(issue, run);
    verify(bootstrapService, never()).bootstrapIssueRunSession(any());

    when(harnessRuntimes.getIfAvailable()).thenReturn(harnessRuntime);
    controller.bootstrapIfAvailable(issue, run);
    verify(bootstrapService).bootstrapIssueRunSession(any());
  }

  @Test
  void inspectClassifiesMissingUnknownProcessingAndQuiescentStates() {
    // 测试意图：验证 bridge 只按最早 Thread 的权威 snapshot 分类，并识别三类 UNKNOWN 来源。
    IssueRun run = run(IssueRunRole.EXECUTOR);
    assertEquals(InspectionStatus.MISSING_SESSION, controller.inspect(run).status());

    UUID sessionId = UUID.randomUUID();
    when(issueRunSessionRepository.findByRunId(run.getId()))
        .thenReturn(IssueRunSession.builder().runId(run.getId()).sessionId(sessionId).build());
    when(harnessRuntime.listThreadsBySession(sessionId)).thenReturn(List.of());
    assertEquals(InspectionStatus.MISSING_THREAD, controller.inspect(run).status());

    ThreadState later = thread(sessionId, NOW.plusSeconds(1));
    ThreadState earliest = thread(sessionId, NOW);
    when(harnessRuntime.listThreadsBySession(sessionId)).thenReturn(List.of(later, earliest));

    ThreadSnapshot snapshot = baseSnapshot(earliest);
    ModelInvocation unknownModel = mock(ModelInvocation.class);
    when(unknownModel.status()).thenReturn(ModelInvocationStatus.UNKNOWN);
    when(snapshot.model()).thenReturn(unknownModel);
    when(harnessRuntime.getThreadSnapshot(earliest.id())).thenReturn(snapshot);
    assertEquals(InspectionStatus.UNKNOWN, controller.inspect(run).status());

    reset(snapshot);
    ToolInvocation unknownTool = mock(ToolInvocation.class);
    when(unknownTool.status()).thenReturn(ToolInvocationStatus.UNKNOWN);
    stubSnapshot(snapshot, earliest);
    when(snapshot.toolSiblings()).thenReturn(List.of(unknownTool));
    assertEquals(InspectionStatus.UNKNOWN, controller.inspect(run).status());

    reset(snapshot);
    MessagePayload message = mock(MessagePayload.class);
    ToolResultMetadata metadata = mock(ToolResultMetadata.class);
    Entry messageEntry = mock(Entry.class);
    when(metadata.status()).thenReturn(ToolResultStatus.UNKNOWN);
    when(message.toolResultMetadata()).thenReturn(metadata);
    when(messageEntry.payload()).thenReturn(message);
    stubSnapshot(snapshot, earliest);
    when(snapshot.entryPath().entries()).thenReturn(List.of(messageEntry));
    assertEquals(InspectionStatus.UNKNOWN, controller.inspect(run).status());

    reset(snapshot);
    stubSnapshot(snapshot, earliest);
    when(snapshot.queuedCommands()).thenReturn(List.of(mock(ThreadCommand.class)));
    assertEquals(InspectionStatus.PROCESSING, controller.inspect(run).status());

    reset(snapshot);
    stubSnapshot(snapshot, earliest);
    Inspection inspection = controller.inspect(run);
    assertEquals(InspectionStatus.QUIESCENT, inspection.status());
    assertEquals(snapshot, inspection.requireQuiescentSnapshot());
    verify(harnessRuntime, times(5)).getThreadSnapshot(earliest.id());
    verify(harnessRuntime, never()).getThreadSnapshot(later.id());
  }

  @Test
  void continuationsUseExactOwnerCursorPayloadAndDeterministicKeys() {
    // 测试意图：验证 user/system continuation 的 owner、cursor、正文和幂等键完整绑定。
    Issue issue = issue();
    issue.setSpecRevision(9L);
    IssueRun run = run(IssueRunRole.EXECUTOR);
    run.setContinuationCount(2);
    run.setObservedSpecRevision(4L);
    run.setObservedInputSequence(5L);
    ThreadState thread = thread(UUID.randomUUID(), NOW);
    ThreadSnapshot snapshot = baseSnapshot(thread);
    Inspection inspection = new Inspection(InspectionStatus.QUIESCENT, snapshot);
    IssueInput input =
        IssueInput.builder()
            .issueId(issue.getId())
            .sequence(6L)
            .kind(IssueInputKind.HUMAN)
            .body("  exact input  ")
            .build();

    controller.sendUserContinuation(issue, run, input, inspection);
    controller.sendSystemContinuation(issue, run, inspection);

    ArgumentCaptor<OwnerRef> ownerCaptor = ArgumentCaptor.forClass(OwnerRef.class);
    ArgumentCaptor<AcceptCommandsCommand> commandCaptor =
        ArgumentCaptor.forClass(AcceptCommandsCommand.class);
    verify(acceptanceOrchestrator, times(2)).accept(ownerCaptor.capture(), commandCaptor.capture());
    assertEquals(
        List.of(
            new OwnerRef(OwnerType.ISSUE_RUN, run.getId()),
            new OwnerRef(OwnerType.ISSUE_RUN, run.getId())),
        ownerCaptor.getAllValues());

    AcceptCommandsCommand userBatch = commandCaptor.getAllValues().get(0);
    assertThreadCursor(userBatch, thread);
    NewThreadCommand userCommand = userBatch.commands().getFirst();
    assertEquals(
        IssueHarnessController.continuationKey(run.getId(), 2, 9L, 6L, "USER"),
        userCommand.idempotencyKey());
    UserMessageCommandPayload userPayload = (UserMessageCommandPayload) userCommand.payload();
    assertEquals(
        "New issue input (spec revision 9, sequence 6, kind HUMAN):\n  exact input  ",
        text(userPayload.message()));

    AcceptCommandsCommand systemBatch = commandCaptor.getAllValues().get(1);
    assertThreadCursor(systemBatch, thread);
    NewThreadCommand systemCommand = systemBatch.commands().getFirst();
    assertEquals(
        IssueHarnessController.continuationKey(run.getId(), 2, 4L, 5L, "SYSTEM"),
        systemCommand.idempotencyKey());
    CustomMessageCommandPayload systemPayload =
        (CustomMessageCommandPayload) systemCommand.payload();
    assertEquals("Continue working on issue #7.", text(systemPayload.message()));
  }

  @Test
  void continuationRequiresQuiescenceAndSanitizesAcceptanceFailure() {
    // 测试意图：非静止 snapshot 不得投递；下游异常不得携带私密输入向上冒泡。
    Issue issue = issue();
    IssueRun run = run(IssueRunRole.EXECUTOR);
    Inspection processing =
        new Inspection(InspectionStatus.PROCESSING, baseSnapshot(thread(UUID.randomUUID(), NOW)));
    assertThrows(
        IllegalStateException.class,
        () -> controller.sendSystemContinuation(issue, run, processing));
    verify(acceptanceOrchestrator, never()).accept(any(), any());

    Inspection quiescent =
        new Inspection(InspectionStatus.QUIESCENT, baseSnapshot(thread(UUID.randomUUID(), NOW)));
    doThrow(new IllegalArgumentException("private-value"))
        .when(acceptanceOrchestrator)
        .accept(any(), any());
    IllegalStateException failure =
        assertThrows(
            IllegalStateException.class,
            () -> controller.sendSystemContinuation(issue, run, quiescent));
    assertEquals("Harness continuation delivery failed", failure.getMessage());
    assertNull(failure.getCause());
  }

  @Test
  void attentionOnlyTargetsAnExistingCoordinatorAndIsReplaySafe() {
    // 测试意图：不得抢建 Coordinator Session；已有相同 key 时 no-op，否则投递带原因的通知。
    Project project = project();
    Issue issue = issue();
    IssueRun run = run(IssueRunRole.EXECUTOR);
    run.setStatus(IssueRunStatus.WAITING_HUMAN);
    run.setWaitingReason("Need a decision");
    controller.deliverAttention(project, issue, run);
    verify(harnessRuntime, never()).listThreadsBySession(any());

    UUID sessionId = UUID.randomUUID();
    when(projectSessionRepository.findByProjectId(project.getId()))
        .thenReturn(
            ProjectSession.builder().projectId(project.getId()).sessionId(sessionId).build());
    when(harnessRuntime.listThreadsBySession(sessionId)).thenReturn(List.of());
    controller.deliverAttention(project, issue, run);
    verify(acceptanceOrchestrator, never()).accept(any(), any());

    ThreadState thread = thread(sessionId, NOW);
    when(harnessRuntime.listThreadsBySession(sessionId)).thenReturn(List.of(thread));
    UUID key = IssueHarnessController.coordinatorAttentionKey(run);
    when(harnessRuntime.findThreadCommand(thread.id(), key))
        .thenReturn(Optional.of(mock(ThreadCommand.class)));
    controller.deliverAttention(project, issue, run);
    verify(acceptanceOrchestrator, never()).accept(any(), any());

    when(harnessRuntime.findThreadCommand(thread.id(), key)).thenReturn(Optional.empty());
    controller.deliverAttention(project, issue, run);
    ArgumentCaptor<OwnerRef> ownerCaptor = ArgumentCaptor.forClass(OwnerRef.class);
    ArgumentCaptor<AcceptCommandsCommand> commandCaptor =
        ArgumentCaptor.forClass(AcceptCommandsCommand.class);
    verify(acceptanceOrchestrator).accept(ownerCaptor.capture(), commandCaptor.capture());
    assertEquals(new OwnerRef(OwnerType.PROJECT, project.getId()), ownerCaptor.getValue());
    assertThreadCursor(commandCaptor.getValue(), thread);
    NewThreadCommand command = commandCaptor.getValue().commands().getFirst();
    assertEquals(key, command.idempotencyKey());
    CustomMessageCommandPayload payload = (CustomMessageCommandPayload) command.payload();
    assertEquals("Issue #7 run entered WAITING_HUMAN: Need a decision", text(payload.message()));
  }

  @Test
  void attentionFailureIsSanitized() {
    // 测试意图：Coordinator 投递失败只暴露稳定边界错误，不回显下游敏感文本。
    Project project = project();
    Issue issue = issue();
    IssueRun run = run(IssueRunRole.EXECUTOR);
    UUID sessionId = UUID.randomUUID();
    ThreadState thread = thread(sessionId, NOW);
    when(projectSessionRepository.findByProjectId(project.getId()))
        .thenReturn(
            ProjectSession.builder().projectId(project.getId()).sessionId(sessionId).build());
    when(harnessRuntime.listThreadsBySession(sessionId)).thenReturn(List.of(thread));
    doThrow(new IllegalArgumentException("private-value"))
        .when(harnessRuntime)
        .findThreadCommand(any(), any());

    IllegalStateException failure =
        assertThrows(
            IllegalStateException.class, () -> controller.deliverAttention(project, issue, run));

    assertEquals("Coordinator attention delivery failed", failure.getMessage());
    assertNull(failure.getCause());
    assertFalse(failure.toString().contains("private-value"));
  }

  @Test
  void stopRunsAfterCommitAndContinuesPastIndividualFailures() {
    // 测试意图：事务内只登记回调；提交后按稳定顺序 best-effort 停止所有 Thread。
    IssueRun run = run(IssueRunRole.EXECUTOR);
    UUID sessionId = UUID.randomUUID();
    when(issueRunSessionRepository.findByRunId(run.getId()))
        .thenReturn(IssueRunSession.builder().runId(run.getId()).sessionId(sessionId).build());
    ThreadState later = thread(sessionId, NOW.plusSeconds(1));
    ThreadState earliest = thread(sessionId, NOW);
    when(harnessRuntime.listThreadsBySession(sessionId)).thenReturn(List.of(later, earliest));
    doThrow(new IllegalStateException("private-value"))
        .when(harnessRuntime)
        .stop(any(StopCommand.class));

    TransactionSynchronizationManager.initSynchronization();
    controller.stopAfterCommit(run);
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
    IssueRun run = run(IssueRunRole.EXECUTOR);
    controller.stopAfterCommit(run);
    verify(harnessRuntime, never()).listThreadsBySession(any());

    UUID sessionId = UUID.randomUUID();
    when(issueRunSessionRepository.findByRunId(run.getId()))
        .thenReturn(IssueRunSession.builder().runId(run.getId()).sessionId(sessionId).build());
    when(harnessRuntime.listThreadsBySession(sessionId))
        .thenThrow(new IllegalStateException("private-value"));
    controller.stopAfterCommit(run);
    verify(harnessRuntime).listThreadsBySession(sessionId);
  }

  @Test
  void inspectionAndIdempotencyHelpersRejectInvalidUsage() {
    // 测试意图：Inspection 形状不变量和 deterministic key 输入差异不会产生别名。
    assertThrows(
        IllegalArgumentException.class, () -> new Inspection(InspectionStatus.QUIESCENT, null));
    Inspection missing = new Inspection(InspectionStatus.MISSING_SESSION, null);
    assertThrows(IllegalStateException.class, missing::requireQuiescentSnapshot);

    UUID runId = UUID.randomUUID();
    assertEquals(
        IssueHarnessController.initialCommandKey(runId),
        IssueHarnessController.initialCommandKey(runId));
    assertNotEquals(
        IssueHarnessController.continuationKey(runId, 1, 2L, 3L, "USER"),
        IssueHarnessController.continuationKey(runId, 1, 2L, 3L, "SYSTEM"));
    IssueRun run = run(IssueRunRole.EXECUTOR);
    UUID first = IssueHarnessController.coordinatorAttentionKey(run);
    run.setVersion(1L);
    assertNotEquals(first, IssueHarnessController.coordinatorAttentionKey(run));
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

  private String text(AgentMessage message) {
    return ((TextMessageContent) message.contents().getFirst()).text();
  }

  private Project project() {
    return Project.builder()
        .id(UUID.randomUUID())
        .title("Project")
        .coordinatorAgentName("coordinator")
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
        .specRevision(1L)
        .inputSequence(0L)
        .version(0L)
        .build();
  }

  private IssueRun run(IssueRunRole role) {
    return IssueRun.builder()
        .id(UUID.randomUUID())
        .issueId(UUID.randomUUID())
        .ordinal(1L)
        .role(role)
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
