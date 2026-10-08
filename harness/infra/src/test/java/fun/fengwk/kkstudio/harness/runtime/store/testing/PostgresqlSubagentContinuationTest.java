package fun.fengwk.kkstudio.harness.runtime.store.testing;

import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.CREATION_REQUEST_HASH;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.T1;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.assistantResponse;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.branchSettings;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.succeededRequest;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.AcceptCommandsCommand;
import fun.fengwk.kkstudio.harness.runtime.AcceptCommandsTarget;
import fun.fengwk.kkstudio.harness.runtime.AcceptancePreflight;
import fun.fengwk.kkstudio.harness.runtime.AcceptedCommands;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.harness.runtime.ThreadLifecycleCoordinator;
import fun.fengwk.kkstudio.harness.runtime.ThreadSnapshot;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionConfig;
import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.history.CustomMessagePayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndPayload;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.join.ThreadJoin;
import fun.fengwk.kkstudio.harness.runtime.join.ThreadJoinOutcome;
import fun.fengwk.kkstudio.harness.runtime.join.ThreadJoinRequest;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderResponse;
import fun.fengwk.kkstudio.harness.runtime.port.TurnResolver;
import fun.fengwk.kkstudio.harness.runtime.processor.ProcessorLeaseConfig;
import fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessResult;
import fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessor;
import fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorConfig;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadRuntimeStatus;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.thread.command.CustomMessageCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.NewThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.thread.command.NotificationCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.SetAgentCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.SetEnvironmentCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.SetModelCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandState;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandType;
import fun.fengwk.kkstudio.harness.runtime.work.ClaimedWork;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTarget;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTargetType;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.stream.Collectors;

/** 真实独立子 Session 的忙时重委派与 fork 回执隔离；只控制 durable 模型状态，不调用外部模型或用 sleep 制造窗口。 */
class PostgresqlSubagentContinuationTest {

  private HarnessStore store;
  private HarnessRuntime runtime;
  private ThreadProcessor processor;
  private ScheduledExecutorService scheduler;

  @BeforeEach
  void setUp() {
    store = PostgresqlHarnessStoreFixture.resetAndCreate();
    Clock clock = Clock.fixed(T1, ZoneOffset.UTC);
    TurnResolver resolver =
        (threadId, path, preparation) ->
            new TurnResolver.Resolved(succeededRequest(), 100_000, 16_384);
    runtime = new HarnessRuntime(store, clock, resolver, () -> CompactionConfig.DEFAULT);
    scheduler = Executors.newSingleThreadScheduledExecutor();
    processor =
        new ThreadProcessor(
            store,
            resolver,
            new ThreadProcessorConfig(
                new ProcessorLeaseConfig(Duration.ofSeconds(30), Duration.ofSeconds(5)),
                Duration.ofSeconds(5),
                () -> new CompactionConfig(20_000, null)),
            clock,
            scheduler,
            Runnable::run);
  }

  @AfterEach
  void tearDown() {
    if (scheduler != null) {
      scheduler.shutdownNow();
    }
  }

  private record Delegation(AcceptedCommands parent, AcceptedCommands child, UUID firstJoin) {}

  private Delegation runningChild() {
    AcceptedCommands parent =
        runtime.acceptCommands(
            new AcceptCommandsCommand(
                new AcceptCommandsTarget.NewRootSession(
                    UUID.randomUUID(), UUID.randomUUID(), branchSettings(), false),
                List.of(prompt("parent task"))),
            AcceptancePreflight.IDENTITY);
    processNext(parent.thread().id());
    finishModel(parent.thread().id(), "parent final");
    processNext(parent.thread().id());
    UUID invocation = UUID.randomUUID();
    AcceptedCommands child =
        runtime.acceptCommandsAndJoin(
            new AcceptCommandsCommand(
                new AcceptCommandsTarget.NewChildSession(
                    UUID.randomUUID(), UUID.randomUUID(), branchSettings(), parent.thread().id()),
                List.of(prompt("first task"))),
            joinRequest(invocation, parent.thread().id()),
            AcceptancePreflight.IDENTITY);
    assertNotEquals(parent.session().id(), child.session().id());
    assertEquals(parent.thread().id(), child.thread().parentThreadId());
    processNext(child.thread().id());
    startModel(child.thread().id());
    assertEquals(ThreadRuntimeStatus.MODEL_RUNNING, snapshot(child.thread().id()).runtimeStatus());
    assertFalse(hasThreadWork(parent.thread().id()));
    return new Delegation(parent, child, invocation);
  }

  @Test
  void runningChildQueuesResumeAndBothJoinsDeliverLatestQuiescentFinalExactlyOnce() {
    Delegation delegation = runningChild();
    UUID childId = delegation.child().thread().id();
    UUID parentId = delegation.parent().thread().id();
    ThreadSnapshot running = snapshot(childId);
    UUID secondJoin = UUID.randomUUID();
    AcceptCommandsCommand resume = resume(childId, prompt("second task"));
    ThreadJoinRequest request = joinRequest(secondJoin, parentId);
    AcceptedCommands accepted =
        runtime.acceptCommandsAndJoin(resume, request, AcceptancePreflight.IDENTITY);
    assertEquals(2L, accepted.acceptedCommands().getFirst().sequence());
    assertEquals(5L, accepted.acceptedCommands().getLast().sequence());
    ThreadSnapshot queued = snapshot(childId);
    List<ThreadCommand> pendingBatch = queued.queuedCommands();
    assertEquals(4, pendingBatch.size());
    assertEquals(accepted.acceptedCommands(), pendingBatch);
    assertEquals(
        List.of(2L, 3L, 4L, 5L), pendingBatch.stream().map(ThreadCommand::sequence).toList());
    BranchSettings settings = running.entryPath().baseSettings();
    assertEquals(
        List.of(
            new SetAgentCommandPayload(settings.agentName()),
            new SetModelCommandPayload(settings.model()),
            new SetEnvironmentCommandPayload(settings.environmentName()),
            new CustomMessageCommandPayload(AgentMessage.user("second task"))),
        pendingBatch.stream().map(ThreadCommand::payload).toList());
    for (ThreadCommand command : pendingBatch) {
      assertEquals(ThreadCommandState.QUEUED, command.state());
    }
    ThreadCommand secondInput = pendingBatch.getLast();
    assertEquals(ThreadCommandType.CUSTOM_MESSAGE, secondInput.type());
    assertEquals(
        new CustomMessageCommandPayload(AgentMessage.user("second task")), secondInput.payload());
    assertEquals(
        running.model(), queued.model(), "resume must not replace or cancel the running model");
    assertEquals(running.thread().headEntryId(), queued.thread().headEntryId());
    assertEquals(1L, queued.thread().inputThroughSequence());
    assertUnmatchedWithoutReceipts(delegation, secondJoin);

    // 消费忙时 wake 是 no-op；新的 INPUT 必须等原模型 final 被应用后才开始。
    ClaimedWork busyClaim = processNext(childId);
    assertEquals(queued, snapshot(childId));
    assertUnmatchedWithoutReceipts(delegation, secondJoin);
    finishModel(childId, "first final");
    processNext(childId);
    ThreadSnapshot firstFinal = snapshot(childId);
    UUID earlyTerminal = firstFinal.thread().headEntryId();
    assertInstanceOf(TurnEndPayload.class, firstFinal.entryPath().head().payload());
    assertEquals(1L, firstFinal.thread().inputThroughSequence());
    assertEquals(pendingBatch, firstFinal.queuedCommands());
    assertUnmatchedWithoutReceipts(delegation, secondJoin);

    processNext(childId);
    ThreadSnapshot secondTurn = snapshot(childId);
    assertEquals(ThreadRuntimeStatus.MODEL_READY, secondTurn.runtimeStatus());
    assertEquals(5L, secondTurn.thread().inputThroughSequence());
    assertEquals(settings, secondTurn.entryPath().baseSettings());
    assertTrue(secondTurn.queuedCommands().isEmpty());
    assertNotEquals(running.model().id(), secondTurn.model().id());
    assertTrue(
        secondTurn.entryPath().entries().stream()
            .anyMatch(
                entry ->
                    entry.payload() instanceof CustomMessagePayload custom
                        && custom.message().equals(AgentMessage.user("second task"))));
    assertUnmatchedWithoutReceipts(delegation, secondJoin);
    startModel(childId);
    finishModel(childId, "second final");
    ClaimedWork finalClaim = processNext(childId);
    ThreadSnapshot latest = snapshot(childId);
    assertEquals(ThreadRuntimeStatus.IDLE, latest.runtimeStatus());

    ThreadJoin first = join(delegation.firstJoin());
    ThreadJoin second = join(secondJoin);
    assertTrue(first.matched());
    assertTrue(second.matched());
    assertEquals(1L, first.sourceCommandSequence());
    assertEquals(5L, second.sourceCommandSequence());
    assertNotEquals(earlyTerminal, first.terminalEntryId());
    assertEquals(latest.thread().headEntryId(), first.terminalEntryId());
    assertEquals(first.terminalEntryId(), second.terminalEntryId());
    assertNotNull(first.finalAnswerEntryId());
    assertEquals(first.finalAnswerEntryId(), second.finalAnswerEntryId());
    for (ThreadJoin settled : List.of(first, second)) {
      var receipt = runtime.projectJoinReceipt(settled.invocationId()).orElseThrow();
      assertEquals(ThreadJoinOutcome.COMPLETED, receipt.outcome());
      assertEquals("second final", receipt.report());
    }
    List<ThreadCommand> deliveries = notifications(parentId);
    assertEquals(2, deliveries.size());
    assertEquals(
        Set.of(first.invocationId(), second.invocationId()),
        deliveries.stream().map(ThreadCommand::idempotencyKey).collect(Collectors.toSet()));
    for (ThreadJoin settled : List.of(first, second)) {
      ThreadCommand delivery =
          deliveries.stream()
              .filter(command -> command.idempotencyKey().equals(settled.invocationId()))
              .findFirst()
              .orElseThrow();
      assertEquals(settled.deliveryCommandSequence(), delivery.sequence());
      assertEquals(ThreadCommandState.QUEUED, delivery.state());
      assertEquals(childId, ((NotificationCommandPayload) delivery.payload()).sourceThreadId());
    }
    assertTrue(hasThreadWork(parentId));
    assertEquals(ThreadProcessResult.LOST_OWNERSHIP, processor.process(busyClaim));
    assertEquals(ThreadProcessResult.LOST_OWNERSHIP, processor.process(finalClaim));
    assertTrue(
        runtime.acceptCommandsAndJoin(resume, request, AcceptancePreflight.IDENTITY).replayed());
    assertEquals(deliveries, notifications(parentId));
    assertEquals(first, join(first.invocationId()));
    assertEquals(second, join(second.invocationId()));
  }

  @Test
  void forkDoesNotTakeRunningChildOrItsReceiptAndCannotJoinOldChild() {
    Delegation delegation = runningChild();
    UUID parentId = delegation.parent().thread().id();
    UUID childId = delegation.child().thread().id();
    ThreadSnapshot running = snapshot(childId);
    AcceptedCommands fork =
        runtime.acceptCommands(
            new AcceptCommandsCommand(
                new AcceptCommandsTarget.NewThread(
                    delegation.parent().session().id(),
                    delegation.parent().rootEntry().id(),
                    UUID.randomUUID(),
                    "fork",
                    false),
                List.of(prompt("fork task"))),
            AcceptancePreflight.IDENTITY);
    UUID forkId = fork.thread().id();
    assertEquals(delegation.parent().session().id(), fork.session().id());
    assertNull(fork.thread().parentThreadId());
    assertEquals(parentId, snapshot(childId).thread().parentThreadId());
    assertEquals(Set.of(parentId, childId), treeIds(parentId));
    assertEquals(Set.of(forkId), treeIds(forkId));
    assertEquals(running, snapshot(childId));

    // 新分支企图为旧 child 添加 Join：direct-parent mismatch 必须在任何新 command/join 写入前拒绝。
    UUID stolenJoin = UUID.randomUUID();
    List<ThreadCommand> beforeCommands = commands(childId);
    IllegalArgumentException rejected =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                runtime.acceptCommandsAndJoin(
                    resume(childId, prompt("take over old child")),
                    joinRequest(stolenJoin, forkId),
                    AcceptancePreflight.IDENTITY));
    assertEquals("join parent differs from immutable child parent", rejected.getMessage());
    assertTrue(runtime.findJoin(stolenJoin).isEmpty());
    assertEquals(beforeCommands, commands(childId));
    assertEquals(running, snapshot(childId));
    assertEquals(parentId, join(delegation.firstJoin()).parentThreadId());

    // 清掉 fork 自己的输入/wake 后再完成旧 child，区分分支自有工作与回执唤醒。
    processNext(forkId);
    finishModel(forkId, "fork final");
    processNext(forkId);
    ThreadSnapshot forkBefore = snapshot(forkId);
    List<ThreadCommand> forkCommandsBefore = commands(forkId);
    assertFalse(hasThreadWork(forkId));
    assertFalse(hasThreadWork(parentId));
    finishModel(childId, "child final");
    ClaimedWork finalClaim = processNext(childId);
    ThreadJoin settled = join(delegation.firstJoin());
    assertTrue(settled.matched());
    assertEquals(parentId, settled.parentThreadId());
    assertEquals(
        "child final", runtime.projectJoinReceipt(settled.invocationId()).orElseThrow().report());
    List<ThreadCommand> receipts = notifications(parentId);
    assertEquals(1, receipts.size());
    assertEquals(settled.invocationId(), receipts.getFirst().idempotencyKey());
    assertEquals(settled.deliveryCommandSequence(), receipts.getFirst().sequence());
    assertEquals(
        childId, ((NotificationCommandPayload) receipts.getFirst().payload()).sourceThreadId());
    assertTrue(hasThreadWork(parentId));
    assertFalse(hasThreadWork(forkId));
    assertEquals(
        forkBefore, snapshot(forkId), "fork history must not acquire the old child's receipt");
    assertEquals(forkCommandsBefore, commands(forkId));
    assertTrue(notifications(forkId).isEmpty());
    assertEquals(Set.of(parentId, childId), treeIds(parentId));
    assertEquals(Set.of(forkId), treeIds(forkId));
    assertEquals(ThreadProcessResult.LOST_OWNERSHIP, processor.process(finalClaim));
    assertEquals(receipts, notifications(parentId));
  }

  private void assertUnmatchedWithoutReceipts(Delegation delegation, UUID secondJoin) {
    for (UUID invocation : List.of(delegation.firstJoin(), secondJoin)) {
      assertFalse(join(invocation).matched());
      assertTrue(runtime.projectJoinReceipt(invocation).isEmpty());
    }
    assertTrue(notifications(delegation.parent().thread().id()).isEmpty());
    assertFalse(hasThreadWork(delegation.parent().thread().id()));
  }

  private static NewThreadCommand prompt(String text) {
    return new NewThreadCommand(
        new CustomMessageCommandPayload(AgentMessage.user(text)), UUID.randomUUID());
  }

  private AcceptCommandsCommand resume(UUID childId, NewThreadCommand prompt) {
    ThreadSnapshot snapshot = snapshot(childId);
    ThreadState child = snapshot.thread();
    BranchSettings settings = snapshot.entryPath().baseSettings();
    // 与 SubagentTaskRunner.appendCommand 一致：无条件发送完整 SET_* 前缀，再追加任务输入。
    return new AcceptCommandsCommand(
        new AcceptCommandsTarget.Thread(childId, child.headEntryId(), child.nextCommandSequence()),
        List.of(
            new NewThreadCommand(
                new SetAgentCommandPayload(settings.agentName()), UUID.randomUUID()),
            new NewThreadCommand(new SetModelCommandPayload(settings.model()), UUID.randomUUID()),
            new NewThreadCommand(
                new SetEnvironmentCommandPayload(settings.environmentName()), UUID.randomUUID()),
            prompt));
  }

  private ThreadJoinRequest joinRequest(UUID invocation, UUID parentId) {
    return new ThreadJoinRequest(
        invocation,
        parentId,
        snapshot(parentId).thread().headEntryId(),
        CREATION_REQUEST_HASH,
        "test-agent",
        10,
        3,
        3,
        3);
  }

  private ThreadSnapshot snapshot(UUID threadId) {
    return runtime.getThreadSnapshot(threadId);
  }

  private ThreadJoin join(UUID invocation) {
    return runtime.findJoin(invocation).orElseThrow();
  }

  private List<ThreadCommand> commands(UUID threadId) {
    return store.transaction(tx -> tx.loadCommandsByThread(threadId));
  }

  private List<ThreadCommand> notifications(UUID threadId) {
    return commands(threadId).stream()
        .filter(command -> command.type() == ThreadCommandType.NOTIFICATION)
        .toList();
  }

  private Set<UUID> treeIds(UUID threadId) {
    return runtime.getThreadTree(threadId).stream()
        .map(snapshot -> snapshot.thread().id())
        .collect(Collectors.toSet());
  }

  private boolean hasThreadWork(UUID threadId) {
    return store.<Boolean>transaction(
        tx -> tx.findWork(new WorkTarget(WorkTargetType.THREAD, threadId)).isPresent());
  }

  private ClaimedWork processNext(UUID expectedThreadId) {
    ClaimedWork claim =
        store
            .transaction(
                tx ->
                    tx.claimNextWork(
                        WorkTargetType.THREAD,
                        T1,
                        UUID.randomUUID().toString(),
                        Duration.ofSeconds(30)))
            .orElseThrow();
    assertEquals(expectedThreadId, claim.target().id());
    assertEquals(ThreadProcessResult.COMPLETED, processor.process(claim));
    return claim;
  }

  private void startModel(UUID threadId) {
    store.transaction(
        tx -> {
          ThreadState thread = ThreadLifecycleCoordinator.lockThreadWithAncestors(tx, threadId);
          UUID turn = tx.loadEntryPath(thread.headEntryId()).openTurnStart().orElseThrow().id();
          ModelInvocation model = tx.findModelInvocationByTurn(threadId, turn).orElseThrow();
          assertEquals(ModelInvocationStatus.READY, model.status());
          tx.lockModelInvocation(model.id());
          ModelInvocation dispatching = model.beginDispatch(T1);
          tx.updateModelInvocation(dispatching);
          tx.updateModelInvocation(dispatching.markRunning(T1));
          return null;
        });
  }

  private void finishModel(UUID threadId, String text) {
    if (snapshot(threadId).model().status() == ModelInvocationStatus.READY) {
      startModel(threadId);
    }
    store.transaction(
        tx -> {
          ThreadState thread = ThreadLifecycleCoordinator.lockThreadWithAncestors(tx, threadId);
          UUID turn = tx.loadEntryPath(thread.headEntryId()).openTurnStart().orElseThrow().id();
          ModelInvocation model = tx.findModelInvocationByTurn(threadId, turn).orElseThrow();
          assertEquals(ModelInvocationStatus.RUNNING, model.status());
          tx.lockModelInvocation(model.id());
          ProviderResponse template = assistantResponse();
          ProviderResponse response =
              new ProviderResponse(
                  text,
                  template.thinking(),
                  template.toolCalls(),
                  template.stopReason(),
                  template.usage(),
                  template.requestId(),
                  template.serviceTier(),
                  template.rawUsageJson());
          tx.updateModelInvocation(model.succeed(response, T1));
          tx.requestWork(new WorkTarget(WorkTargetType.THREAD, threadId), T1);
          return null;
        });
  }
}
