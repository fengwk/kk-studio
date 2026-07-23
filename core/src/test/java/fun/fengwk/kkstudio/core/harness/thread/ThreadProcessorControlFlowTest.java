package fun.fengwk.kkstudio.core.harness.thread;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.agent.extension.ProviderRequestInterceptorChain;
import fun.fengwk.kkstudio.harness.runtime.context.SessionContextBuilder;
import fun.fengwk.kkstudio.harness.runtime.extension.HarnessLifecycleObservers;
import fun.fengwk.kkstudio.harness.runtime.permission.PermissionAction;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.CustomMessageEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.session.MessageEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.session.SessionEntry;
import fun.fengwk.kkstudio.harness.runtime.session.SessionEntryStore;
import fun.fengwk.kkstudio.harness.runtime.session.SessionEntryType;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.thread.AgentThread;
import fun.fengwk.kkstudio.harness.runtime.thread.CompactionService;
import fun.fengwk.kkstudio.harness.runtime.thread.ProviderMessageProjector;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadIdGenerator;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadProcessor;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadProcessor.ThreadToolPort;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadProcessorConfig;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadRetryPolicy;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadRuntimeConfigResolver;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadStatus;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadStore;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadTransactions;
import fun.fengwk.kkstudio.harness.runtime.thread.TurnResourceResolver;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInvocationStatus;
import fun.fengwk.kkstudio.harness.tool.ToolExecutionLocation;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** ThreadProcessor 的非异步状态机边界：测试端口返回值而非复制实现细节。 */
class ThreadProcessorControlFlowTest {

  @Test
  void kickRejectsNonPositiveThreadId() {
    ProcessorFixture fixture = fixture(Runnable::run, mock(ScheduledExecutorService.class));

    assertThrows(IllegalArgumentException.class, () -> fixture.processor().kick(0));
  }

  @Test
  void rejectedExecutorSchedulesDeferredActivationWithoutRunningCallerThread() {
    ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
    Executor rejected =
        task -> {
          throw new RejectedExecutionException("worker saturated");
        };
    ProcessorFixture fixture = fixture(rejected, scheduler);

    fixture.processor().kick(7L);

    verify(scheduler).schedule(any(Runnable.class), eq(50L), eq(TimeUnit.MILLISECONDS));
    verifyNoInteractions(fixture.threadStore());
  }

  @Test
  void rejectedDeferredActivationIsLeftForDurableRecovery() {
    ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
    when(scheduler.schedule(any(Runnable.class), eq(50L), eq(TimeUnit.MILLISECONDS)))
        .thenThrow(new RejectedExecutionException("scheduler saturated"));
    Executor rejected =
        task -> {
          throw new RejectedExecutionException("worker saturated");
        };
    ProcessorFixture fixture = fixture(rejected, scheduler);

    assertDoesNotThrow(() -> fixture.processor().kick(7L));

    verify(scheduler).schedule(any(Runnable.class), eq(50L), eq(TimeUnit.MILLISECONDS));
    verifyNoInteractions(fixture.threadStore());
  }

  @Test
  void processReleasesLeaseWhenDurableThreadDisappearsDuringActivation() {
    ProcessorFixture fixture = fixture(Runnable::run, mock(ScheduledExecutorService.class));
    acquireOwnedThread(fixture, ThreadStatus.RUNNING);
    when(fixture.threadStore().renew(anyLong(), anyString(), any(), any())).thenReturn(true);
    when(fixture.threadStore().find(1L)).thenReturn(Optional.empty());

    assertThrows(IllegalStateException.class, () -> fixture.processor().process(1L));

    verify(fixture.threadStore()).release(eq(1L), anyString(), any());
  }

  @Test
  void processStopsWhenRenewalLosesOwnership() {
    ProcessorFixture fixture = fixture(Runnable::run, mock(ScheduledExecutorService.class));
    acquireOwnedThread(fixture, ThreadStatus.RUNNING);
    when(fixture.threadStore().renew(anyLong(), anyString(), any(), any())).thenReturn(false);

    fixture.processor().process(1L);

    verifyNoInteractions(fixture.transactions());
  }

  @Test
  void processStopsWhenTokenChangesAfterSuccessfulRenewal() {
    ProcessorFixture fixture = fixture(Runnable::run, mock(ScheduledExecutorService.class));
    acquireOwnedThread(fixture, ThreadStatus.RUNNING);
    when(fixture.threadStore().renew(anyLong(), anyString(), any(), any())).thenReturn(true);
    when(fixture.threadStore().find(1L))
        .thenReturn(Optional.of(thread(ThreadStatus.RUNNING, "other")));

    fixture.processor().process(1L);

    verifyNoInteractions(fixture.transactions());
  }

  @Test
  void terminalToolApplyFencingFailureWaitsInsteadOfProceedingToModel() {
    ProcessorFixture fixture = fixture(Runnable::run, mock(ScheduledExecutorService.class));
    acquireOwnedThread(fixture, ThreadStatus.RUNNING);
    when(fixture.threadStore().renew(anyLong(), anyString(), any(), any())).thenReturn(true);
    when(fixture.threadStore().find(1L)).thenAnswer(invocation -> ownedThread(fixture));
    when(fixture.toolPort().hasTerminalResultsPendingApply(1L, 3L)).thenReturn(true);
    when(fixture.transactions().applyTerminalToolResults(anyLong(), anyString(), any()))
        .thenReturn(false);
    when(fixture
            .transactions()
            .waitForExternal(eq(1L), anyString(), eq("tools_or_permission"), any()))
        .thenReturn(true);

    fixture.processor().process(1L);

    verify(fixture.transactions())
        .waitForExternal(eq(1L), anyString(), eq("tools_or_permission"), any());
  }

  @Test
  void nonTerminalToolWaitThatLosesFencingStopsWithoutWritingWaitingState() {
    ProcessorFixture fixture = fixture(Runnable::run, mock(ScheduledExecutorService.class));
    acquireOwnedThread(fixture, ThreadStatus.RUNNING);
    when(fixture.threadStore().renew(anyLong(), anyString(), any(), any())).thenReturn(true);
    when(fixture.threadStore().find(1L))
        .thenAnswer(invocation -> ownedThread(fixture))
        .thenReturn(Optional.of(thread(ThreadStatus.RUNNING, "other")));
    when(fixture.toolPort().listNonTerminal(1L, 3L)).thenReturn(List.of(waitingInvocation()));
    when(fixture
            .transactions()
            .waitForExternal(eq(1L), anyString(), eq("tools_or_permission"), any()))
        .thenReturn(false);

    fixture.processor().process(1L);

    verify(fixture.transactions())
        .waitForExternal(eq(1L), anyString(), eq("tools_or_permission"), any());
  }

  @Test
  void retryingThreadStopsWhenTurnAdmissionReportsLostOwnership() {
    ProcessorFixture fixture = fixture(Runnable::run, mock(ScheduledExecutorService.class));
    acquireOwnedThread(fixture, ThreadStatus.RETRYING);
    when(fixture.threadStore().renew(anyLong(), anyString(), any(), any())).thenReturn(true);
    when(fixture.threadStore().find(1L))
        .thenAnswer(invocation -> ownedThread(fixture, ThreadStatus.RETRYING));
    when(fixture.transactions().beginTurn(anyLong(), anyString(), any()))
        .thenReturn(ThreadTransactions.BeginTurnResult.lostOwnership());

    fixture.processor().process(1L);

    verify(fixture.transactions()).beginTurn(anyLong(), anyString(), any());
  }

  @Test
  void customSystemHeadQuiescesWithoutCreatingModelResponseDebt() {
    ProcessorFixture fixture = fixture(Runnable::run, mock(ScheduledExecutorService.class));
    acquireOwnedThread(fixture, ThreadStatus.RUNNING);
    when(fixture.threadStore().renew(anyLong(), anyString(), any(), any())).thenReturn(true);
    when(fixture.threadStore().find(1L)).thenAnswer(invocation -> ownedThread(fixture));
    when(fixture.entryStore().loadPath(2L, 3L)).thenReturn(List.of(systemCustomEntry()));
    when(fixture.transactions().harvestQueuedInputs(anyLong(), anyString(), any()))
        .thenReturn(ThreadTransactions.HarvestResult.none());
    when(fixture.transactions().quiesce(anyLong(), anyString(), any()))
        .thenReturn(ThreadTransactions.QuiescenceResult.IDLE);

    fixture.processor().process(1L);

    verifyNoInteractions(fixture.resourceResolver());
    verify(fixture.transactions()).quiesce(anyLong(), anyString(), any());
  }

  @Test
  void quiescenceLostOwnershipReturnsWithoutPublishingIdle() {
    ProcessorFixture fixture = fixture(Runnable::run, mock(ScheduledExecutorService.class));
    configureCustomSystemHead(fixture);
    when(fixture.transactions().quiesce(anyLong(), anyString(), any()))
        .thenReturn(ThreadTransactions.QuiescenceResult.LOST_OWNERSHIP);

    fixture.processor().process(1L);

    verify(fixture.transactions()).quiesce(anyLong(), anyString(), any());
  }

  @Test
  void quiescenceWorkRemainingContinuesUntilLeaseIsLost() {
    ProcessorFixture fixture = fixture(Runnable::run, mock(ScheduledExecutorService.class));
    configureCustomSystemHead(fixture);
    when(fixture.threadStore().renew(anyLong(), anyString(), any(), any())).thenReturn(true, false);
    when(fixture.transactions().quiesce(anyLong(), anyString(), any()))
        .thenReturn(ThreadTransactions.QuiescenceResult.WORK_REMAINS);

    fixture.processor().process(1L);

    verify(fixture.transactions()).quiesce(anyLong(), anyString(), any());
  }

  @Test
  void terminalToolApplyFencingLossStopsBeforeExternalWait() {
    ProcessorFixture fixture = fixture(Runnable::run, mock(ScheduledExecutorService.class));
    acquireOwnedThread(fixture, ThreadStatus.RUNNING);
    when(fixture.threadStore().renew(anyLong(), anyString(), any(), any())).thenReturn(true);
    when(fixture.threadStore().find(1L))
        .thenAnswer(invocation -> ownedThread(fixture))
        .thenReturn(Optional.of(thread(ThreadStatus.RUNNING, "other")));
    when(fixture.toolPort().hasTerminalResultsPendingApply(1L, 3L)).thenReturn(true);
    when(fixture.transactions().applyTerminalToolResults(anyLong(), anyString(), any()))
        .thenReturn(false);

    fixture.processor().process(1L);

    verify(fixture.transactions(), never())
        .waitForExternal(eq(1L), anyString(), eq("tools_or_permission"), any());
  }

  @Test
  void refreshedThreadFencingLossStopsBeforeMailboxHarvest() {
    ProcessorFixture fixture = fixture(Runnable::run, mock(ScheduledExecutorService.class));
    acquireOwnedThread(fixture, ThreadStatus.RUNNING);
    when(fixture.threadStore().renew(anyLong(), anyString(), any(), any())).thenReturn(true);
    when(fixture.threadStore().find(1L))
        .thenAnswer(invocation -> ownedThread(fixture))
        .thenReturn(Optional.of(thread(ThreadStatus.RUNNING, "other")));

    fixture.processor().process(1L);

    verifyNoInteractions(fixture.transactions());
  }

  @Test
  void retryingAdmissionRejectionUsesPolicyExitWithoutResolvingResources() {
    ProcessorFixture fixture = fixture(Runnable::run, mock(ScheduledExecutorService.class));
    acquireOwnedThread(fixture, ThreadStatus.RETRYING);
    when(fixture.threadStore().renew(anyLong(), anyString(), any(), any())).thenReturn(true);
    when(fixture.threadStore().find(1L))
        .thenAnswer(invocation -> ownedThread(fixture, ThreadStatus.RETRYING));
    when(fixture.transactions().beginTurn(anyLong(), anyString(), any()))
        .thenReturn(ThreadTransactions.BeginTurnResult.rejected("stopped"));

    fixture.processor().process(1L);

    verifyNoInteractions(fixture.resourceResolver());
  }

  @Test
  void retryingSetupFailureStopsWhenFailureCommitSucceeds() {
    ProcessorFixture fixture = fixture(Runnable::run, mock(ScheduledExecutorService.class));
    acquireOwnedThread(fixture, ThreadStatus.RETRYING);
    when(fixture.threadStore().renew(anyLong(), anyString(), any(), any())).thenReturn(true);
    when(fixture.threadStore().find(1L))
        .thenAnswer(invocation -> ownedThread(fixture, ThreadStatus.RETRYING));
    when(fixture.transactions().beginTurn(anyLong(), anyString(), any()))
        .thenReturn(ThreadTransactions.BeginTurnResult.admitted());
    when(fixture
            .transactions()
            .recordAssistantError(
                anyLong(), anyString(), anyLong(), any(), any(), any(), any(), any()))
        .thenReturn(true);

    fixture.processor().process(1L);

    verify(fixture.transactions())
        .recordAssistantError(anyLong(), anyString(), anyLong(), any(), any(), any(), any(), any());
  }

  @Test
  void regularSetupFailureThatCannotBeCommittedReturnsLostOwnership() {
    ProcessorFixture fixture = fixture(Runnable::run, mock(ScheduledExecutorService.class));
    acquireOwnedThread(fixture, ThreadStatus.RUNNING);
    when(fixture.threadStore().renew(anyLong(), anyString(), any(), any())).thenReturn(true);
    when(fixture.threadStore().find(1L)).thenAnswer(invocation -> ownedThread(fixture));
    when(fixture.entryStore().loadPath(2L, 3L)).thenReturn(List.of(userEntry()));
    when(fixture.transactions().harvestQueuedInputs(anyLong(), anyString(), any()))
        .thenReturn(ThreadTransactions.HarvestResult.none());
    when(fixture.transactions().beginTurn(anyLong(), anyString(), any()))
        .thenReturn(ThreadTransactions.BeginTurnResult.admitted());
    when(fixture
            .transactions()
            .recordAssistantError(
                anyLong(), anyString(), anyLong(), any(), any(), any(), any(), any()))
        .thenReturn(false);

    fixture.processor().process(1L);

    verify(fixture.transactions())
        .recordAssistantError(anyLong(), anyString(), anyLong(), any(), any(), any(), any(), any());
  }

  @Test
  void scheduledActivationContainsUnexpectedProcessingFailure() {
    ProcessorFixture fixture = fixture(Runnable::run, mock(ScheduledExecutorService.class));
    when(fixture.threadStore().tryAcquire(anyLong(), anyString(), any(), any()))
        .thenThrow(new IllegalStateException("store unavailable"));

    assertDoesNotThrow(() -> fixture.processor().kick(7L));
  }

  private static void configureCustomSystemHead(ProcessorFixture fixture) {
    acquireOwnedThread(fixture, ThreadStatus.RUNNING);
    when(fixture.threadStore().renew(anyLong(), anyString(), any(), any())).thenReturn(true);
    when(fixture.threadStore().find(1L)).thenAnswer(invocation -> ownedThread(fixture));
    when(fixture.entryStore().loadPath(2L, 3L)).thenReturn(List.of(systemCustomEntry()));
    when(fixture.transactions().harvestQueuedInputs(anyLong(), anyString(), any()))
        .thenReturn(ThreadTransactions.HarvestResult.none());
  }

  private static void acquireOwnedThread(ProcessorFixture fixture, ThreadStatus status) {
    when(fixture.threadStore().tryAcquire(anyLong(), anyString(), any(), any()))
        .thenAnswer(
            invocation -> {
              fixture.token().set(invocation.getArgument(1));
              return Optional.of(thread(status, fixture.token().get()));
            });
  }

  private static Optional<AgentThread> ownedThread(ProcessorFixture fixture) {
    return ownedThread(fixture, ThreadStatus.RUNNING);
  }

  private static Optional<AgentThread> ownedThread(ProcessorFixture fixture, ThreadStatus status) {
    return Optional.of(thread(status, fixture.token().get()));
  }

  private static AgentThread thread(ThreadStatus status, String token) {
    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    return new AgentThread(
        1L,
        2L,
        3L,
        status,
        0L,
        status == ThreadStatus.RETRYING ? 1 : 0,
        status == ThreadStatus.RETRYING ? now : null,
        1L,
        "default-assistant",
        "1",
        "default",
        false,
        token,
        now.plusSeconds(30),
        0L,
        now,
        now);
  }

  private static SessionEntry systemCustomEntry() {
    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    return new SessionEntry(
        3L,
        2L,
        null,
        SessionEntryType.CUSTOM_MESSAGE,
        new CustomMessageEntryPayload(
            new AgentMessage(
                AgentMessageRole.SYSTEM, List.of(new TextMessageContent("runtime instruction")))),
        now);
  }

  private static SessionEntry userEntry() {
    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    return new SessionEntry(
        3L,
        2L,
        null,
        SessionEntryType.MESSAGE,
        new MessageEntryPayload(
            new AgentMessage(AgentMessageRole.USER, List.of(new TextMessageContent("question")))),
        now);
  }

  private static ToolInvocation waitingInvocation() {
    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    return new ToolInvocation(
        1L,
        1L,
        3L,
        0,
        "call",
        "tool",
        "1",
        ToolExecutionLocation.PLATFORM,
        null,
        "{}",
        ToolInvocationStatus.WAITING_APPROVAL,
        PermissionAction.ASK,
        null,
        ToolSideEffect.READ_ONLY,
        now.plusSeconds(30),
        null,
        null,
        null,
        null,
        null,
        now,
        null,
        null,
        now);
  }

  private static ProcessorFixture fixture(Executor executor, ScheduledExecutorService scheduler) {
    ThreadStore threadStore = mock(ThreadStore.class);
    ThreadTransactions transactions = mock(ThreadTransactions.class);
    SessionEntryStore entryStore = mock(SessionEntryStore.class);
    ThreadToolPort toolPort = mock(ThreadToolPort.class);
    TurnResourceResolver resourceResolver = mock(TurnResourceResolver.class);
    ThreadIdGenerator idGenerator = mock(ThreadIdGenerator.class);
    // ThreadProcessor allocates a plannedAssistantEntryId before resolving resources; the failure
    // path now reuses that id for the durable ASSISTANT_ERROR Entry. Stub a positive id so the
    // ThreadEventDraft subject is valid.
    when(idGenerator.newSessionEntryId()).thenReturn(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L);
    ThreadProcessor processor =
        new ThreadProcessor(
            threadStore,
            transactions,
            entryStore,
            toolPort,
            mock(SessionContextBuilder.class),
            mock(ThreadRuntimeConfigResolver.class),
            new ProviderMessageProjector(),
            resourceResolver,
            mock(CompactionService.class),
            () -> ThreadRetryPolicy.DEFAULT,
            new ThreadProcessorConfig(Duration.ofSeconds(3), Duration.ofSeconds(1), 1_024, 2),
            Clock.systemUTC(),
            (delay, task) -> {},
            new ProviderRequestInterceptorChain(List.of()),
            new HarnessLifecycleObservers(List.of()),
            idGenerator,
            executor,
            scheduler);
    return new ProcessorFixture(
        processor,
        threadStore,
        transactions,
        entryStore,
        toolPort,
        resourceResolver,
        new AtomicReference<>());
  }

  private record ProcessorFixture(
      ThreadProcessor processor,
      ThreadStore threadStore,
      ThreadTransactions transactions,
      SessionEntryStore entryStore,
      ThreadToolPort toolPort,
      TurnResourceResolver resourceResolver,
      AtomicReference<String> token) {}
}
