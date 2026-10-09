package fun.fengwk.kkstudio.harness.runtime.store.testing;

import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.CREATION_REQUEST_HASH;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.T1;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.branchSettings;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.harness.runtime.ThreadLifecycleCoordinator;
import fun.fengwk.kkstudio.harness.runtime.ThreadSnapshot;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionConfig;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionPhase;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionStart;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionTrigger;
import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnEndOutcome;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnStartReason;
import fun.fengwk.kkstudio.harness.runtime.history.CompactionPayload;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.history.MessagePayload;
import fun.fengwk.kkstudio.harness.runtime.history.RootPayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndPayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnStartPayload;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelRequestSpec;
import fun.fengwk.kkstudio.harness.runtime.join.JoinPurpose;
import fun.fengwk.kkstudio.harness.runtime.join.ThreadJoin;
import fun.fengwk.kkstudio.harness.runtime.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.ModelInputModality;
import fun.fengwk.kkstudio.harness.runtime.model.ModelUsage;
import fun.fengwk.kkstudio.harness.runtime.model.ModelVariant;
import fun.fengwk.kkstudio.harness.runtime.model.cache.ProviderCacheControl;
import fun.fengwk.kkstudio.harness.runtime.model.provider.GenerationStopReason;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderResponse;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderType;
import fun.fengwk.kkstudio.harness.runtime.port.TurnResolver;
import fun.fengwk.kkstudio.harness.runtime.processor.ProcessorLeaseConfig;
import fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessResult;
import fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessor;
import fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorConfig;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.AssistantMessageMetadata;
import fun.fengwk.kkstudio.harness.runtime.session.Session;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadExecutionControl;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadYoloPolicy;
import fun.fengwk.kkstudio.harness.runtime.thread.command.CustomMessageCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandType;
import fun.fengwk.kkstudio.harness.runtime.thread.command.UserMessageCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.work.ClaimedWork;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTarget;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTargetType;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/** 真实临时 PostgreSQL 端到端集成测试：覆盖压缩子 Thread 创建、父 durable wait、子真实执行到终态、父消费并按摘要提交。 */
class PostgresqlCompactionChildTest {

  private static final ModelUsage OVER_THRESHOLD_USAGE =
      new ModelUsage(90_000L, 2L, 0L, 0L, 0L, 0L, 90_002L);
  private static final int CONTEXT_WINDOW = 100_000;
  private static final int MAX_OUTPUT_TOKENS = 16_384;

  private HarnessStore store;
  private HarnessRuntime runtime;
  private ThreadProcessor processor;
  private ScheduledExecutorService scheduler;

  @BeforeEach
  void setUp() {
    store = PostgresqlHarnessStoreFixture.resetAndCreate();
    Clock clock = Clock.fixed(T1, ZoneOffset.UTC);
    scheduler = Executors.newSingleThreadScheduledExecutor();
    TurnResolver resolver =
        (threadId, path, preparation) -> {
          if (preparation != null) {
            ModelSelection compactorModel = new ModelSelection("provider", "compactor-model", "v1");
            return new TurnResolver.CompactionResolved(
                compactorModel,
                4096L,
                CONTEXT_WINDOW,
                MAX_OUTPUT_TOKENS,
                new BranchSettings(
                    "compaction", compactorModel, path.baseSettings().environmentName(), null));
          }
          return new TurnResolver.Resolved(
              requestFor(path.baseSettings()), CONTEXT_WINDOW, MAX_OUTPUT_TOKENS);
        };
    runtime = new HarnessRuntime(store, clock, resolver, () -> CompactionConfig.DEFAULT);
    processor =
        new ThreadProcessor(
            store,
            resolver,
            new ThreadProcessorConfig(
                new ProcessorLeaseConfig(Duration.ofSeconds(30), Duration.ofSeconds(5)),
                Duration.ofSeconds(5),
                () -> CompactionConfig.DEFAULT),
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

  @Test
  void compactionChildLifecycleEndToEnd() {
    // a. seed 一个父 Session/ROOT + 历史，使自动压缩在 threshold 下被规划
    ClosedTurnBaseline baseline = seedCompactionReadyClosedTurn(store, OVER_THRESHOLD_USAGE);
    UUID parentId = baseline.threadId();
    assertTrue(findModelInvocationByTurn(parentId, baseline.turnStartEntryId()).isEmpty());

    UUID userCommandId = seedUserCommand(parentId, "queued input");
    requestThreadWork(parentId);

    // b. claim 父线程直到规划出压缩
    processNext(parentId);

    EntryPath parentPath1 = path(parentId);
    assertEquals(10, parentPath1.entries().size());
    Entry compactionStartEntry = parentPath1.head();
    assertInstanceOf(TurnStartPayload.class, compactionStartEntry.payload());
    TurnStartPayload startPayload = (TurnStartPayload) compactionStartEntry.payload();
    assertEquals(TurnStartReason.COMPACTION, startPayload.reason());
    assertEquals(parentId, startPayload.ownerThreadId());

    // 父 TURN_START.compaction 的 run group 完整
    CompactionStart compaction = startPayload.compaction();
    assertNotNull(compaction);
    assertNotNull(compaction.executionModel());
    assertEquals("compactor-model", compaction.executionModel().modelName());
    assertNotNull(compaction.outputBudget());
    assertTrue(compaction.outputBudget() > 0);
    assertNotNull(compaction.childThreadId());
    assertNotNull(compaction.joinInvocationId());
    assertEquals(CompactionPhase.FULL, compaction.phase());
    assertEquals(CompactionTrigger.THRESHOLD, compaction.trigger());

    UUID childThreadId = compaction.childThreadId();
    UUID joinInvocationId = compaction.joinInvocationId();

    // 父线程没有 ModelInvocation
    assertTrue(findModelInvocationByTurn(parentId, compactionStartEntry.id()).isEmpty());

    // 新建了子 Session/ROOT Entry/子 Thread（parentThreadId = 父）
    store.transaction(
        tx -> {
          ThreadState childThread = tx.lockThread(childThreadId).orElseThrow();
          assertEquals(parentId, childThread.parentThreadId());
          Session childSession = tx.findSession(childThread.sessionId()).orElseThrow();
          assertNotNull(childSession);
          Entry rootEntry = tx.findEntry(childThread.headEntryId()).orElseThrow();
          assertInstanceOf(RootPayload.class, rootEntry.payload());

          // 子第一条 command 的 payload 是 CustomMessageCommandPayload（摘要 prompt，非空）
          List<ThreadCommand> childCommands = tx.loadCommandsByThread(childThreadId);
          assertEquals(1, childCommands.size());
          ThreadCommand childFirstCmd = childCommands.getFirst();
          assertEquals(1L, childFirstCmd.sequence());
          assertEquals(ThreadCommandType.CUSTOM_MESSAGE, childFirstCmd.type());
          assertInstanceOf(CustomMessageCommandPayload.class, childFirstCmd.payload());
          CustomMessageCommandPayload promptPayload =
              (CustomMessageCommandPayload) childFirstCmd.payload();
          TextMessageContent textContent =
              (TextMessageContent) promptPayload.message().contents().getFirst();
          assertFalse(textContent.text().isBlank());

          // 存在 purpose == COMPACTION、childThreadId = 子、未 matched 的 join
          ThreadJoin join = tx.findJoin(joinInvocationId).orElseThrow();
          assertEquals(JoinPurpose.COMPACTION, join.purpose());
          assertEquals(parentId, join.parentThreadId());
          assertEquals(childThreadId, join.childThreadId());
          assertFalse(join.matched());
          assertNull(join.terminalEntryId());
          assertNull(join.finalAnswerEntryId());
          return null;
        });

    // 子 THREAD Work 被请求
    assertTrue(hasWork(WorkTargetType.THREAD, childThreadId));

    // c. 父 durable wait：父没有待处理 Work（除子之外没有父自身的 THREAD/MODEL Work）、父历史没有新的 TURN_END
    assertFalse(hasWork(WorkTargetType.THREAD, parentId));
    assertEquals(compactionStartEntry.id(), path(parentId).head().id());
    assertFalse(path(parentId).head().payload() instanceof TurnEndPayload);

    // 用户原始输入仍然在父队列中未被消费
    List<ThreadCommand> parentQueued =
        store.transaction(
            tx -> {
              tx.lockThread(parentId);
              return tx.loadQueuedCommands(parentId);
            });
    assertEquals(1, parentQueued.size());
    assertEquals(userCommandId, parentQueued.getFirst().idempotencyKey());

    // d. 真实执行子 Thread：claim 子线程（注入的 TurnResolver 对子返回普通 Resolved）
    processNext(childThreadId);

    // 子线程产生了 ModelInvocation，状态为 READY
    ModelInvocation childModel =
        store.transaction(
            tx -> {
              ThreadState childThread = tx.lockThread(childThreadId).orElseThrow();
              UUID childTurn =
                  tx.loadEntryPath(childThread.headEntryId()).openTurnStart().orElseThrow().id();
              return tx.findModelInvocationByTurn(childThreadId, childTurn).orElseThrow();
            });
    assertEquals(ModelInvocationStatus.READY, childModel.status());

    // 把它的 ModelInvocation 推进到 SUCCEEDED
    String childSummaryText = "compacted summary of history";
    finishModel(childThreadId, childSummaryText);

    // 使子 turn 走完并结算
    processNext(childThreadId);

    // 断言 COMPACTION join 已 matched（terminalEntryId/finalAnswerEntryId 非空）
    ThreadJoin settledJoin = findJoin(joinInvocationId);
    assertTrue(settledJoin.matched());
    assertNotNull(settledJoin.terminalEntryId());
    assertNotNull(settledJoin.finalAnswerEntryId());

    // 父 THREAD Work 被重新请求
    assertTrue(hasWork(WorkTargetType.THREAD, parentId));

    // e. 父恢复并消费：claim 父线程
    processNext(parentId);

    // 断言父历史新增 CompactionPayload（summaryText 等于子最终 assistant 文本）+ TurnEndPayload(COMPLETED)
    EntryPath parentPathFinal = path(parentId);
    assertEquals(12, parentPathFinal.entries().size());
    Entry compactionResultEntry = parentPathFinal.entries().get(10);
    assertInstanceOf(CompactionPayload.class, compactionResultEntry.payload());
    CompactionPayload compactionPayload = (CompactionPayload) compactionResultEntry.payload();
    assertEquals(childSummaryText, compactionPayload.summaryText());
    assertNotNull(compactionPayload.assistantMetadata());
    assertEquals(GenerationStopReason.COMPLETE, compactionPayload.assistantMetadata().stopReason());
    assertEquals(
        new ModelUsage(100L, 20L, 0L, 0L, 0L, 0L, 120L),
        compactionPayload.assistantMetadata().usage());

    Entry turnEndEntry = parentPathFinal.entries().get(11);
    assertEquals(parentPathFinal.head().id(), turnEndEntry.id());
    assertInstanceOf(TurnEndPayload.class, turnEndEntry.payload());
    TurnEndPayload endPayload = (TurnEndPayload) turnEndEntry.payload();
    assertEquals(compactionStartEntry.id(), endPayload.turnStartEntryId());
    assertEquals(TurnEndOutcome.COMPLETED, endPayload.outcome());
    assertFalse(endPayload.continueModel());

    // 父 head 推进
    ThreadState finalParentThread = store.transaction(tx -> tx.findThread(parentId).orElseThrow());
    assertEquals(turnEndEntry.id(), finalParentThread.headEntryId());

    // 父无 ModelInvocation
    assertTrue(findModelInvocationByTurn(parentId, compactionStartEntry.id()).isEmpty());
  }

  private record ClosedTurnBaseline(
      UUID sessionId,
      UUID rootEntryId,
      UUID turnStartEntryId,
      UUID userEntryId,
      UUID assistantEntryId,
      UUID turnEndEntryId,
      UUID threadId) {}

  private ClosedTurnBaseline seedCompactionReadyClosedTurn(HarnessStore store, ModelUsage usage) {
    ProviderResponse response =
        new ProviderResponse(
            "assistant reply",
            "",
            List.of(),
            GenerationStopReason.COMPLETE,
            usage,
            "req-1",
            null,
            "{}");
    return store.transaction(
        tx -> {
          UUID sessionId = tx.nextId();
          UUID rootEntryId = tx.nextId();
          UUID turn1StartId = tx.nextId();
          UUID turn1UserId = tx.nextId();
          UUID turn1AssistantId = tx.nextId();
          UUID turn1EndId = tx.nextId();
          UUID turn2StartId = tx.nextId();
          UUID turn2UserId = tx.nextId();
          UUID turn2AssistantId = tx.nextId();
          UUID turn2EndId = tx.nextId();
          UUID threadId = tx.nextId();

          tx.insertSession(new Session(sessionId, "session-" + sessionId, T1));
          tx.insertEntry(
              new Entry(rootEntryId, sessionId, null, new RootPayload(branchSettings()), T1));

          // turn1：可摘要历史显著大于测试摘要 wrapper，确保成功摘要有真实 gain
          tx.insertEntry(
              new Entry(
                  turn1StartId, sessionId, rootEntryId, resolvedInputTurnStart(threadId), T1));
          tx.insertEntry(
              new Entry(
                  turn1UserId,
                  sessionId,
                  turn1StartId,
                  new MessagePayload(
                      new AgentMessage(
                          AgentMessageRole.USER,
                          List.of(new TextMessageContent("historical user " + "h".repeat(50_000)))),
                      null,
                      null),
                  T1));
          tx.insertEntry(
              new Entry(
                  turn1AssistantId,
                  sessionId,
                  turn1UserId,
                  new MessagePayload(
                      new AgentMessage(
                          AgentMessageRole.ASSISTANT,
                          List.of(
                              new TextMessageContent(
                                  "historical assistant " + "a".repeat(50_000)))),
                      new AssistantMessageMetadata(
                          GenerationStopReason.COMPLETE,
                          new ModelUsage(1L, 2L, 0L, 0L, 0L, 0L, 3L)),
                      null),
                  T1));
          tx.insertEntry(
              new Entry(
                  turn1EndId,
                  sessionId,
                  turn1AssistantId,
                  new TurnEndPayload(turn1StartId, TurnEndOutcome.COMPLETED, false, null, null),
                  T1));

          // turn2：长 USER + 短 ASSISTANT（携带越过阈值的 usage）
          tx.insertEntry(
              new Entry(turn2StartId, sessionId, turn1EndId, resolvedInputTurnStart(threadId), T1));
          tx.insertEntry(
              new Entry(
                  turn2UserId,
                  sessionId,
                  turn2StartId,
                  new MessagePayload(
                      new AgentMessage(
                          AgentMessageRole.USER,
                          List.of(
                              new TextMessageContent(
                                  "user asks a very long question" + "x".repeat(90_000)))),
                      null,
                      null),
                  T1));
          tx.insertEntry(
              new Entry(
                  turn2AssistantId,
                  sessionId,
                  turn2UserId,
                  new MessagePayload(
                      new AgentMessage(
                          AgentMessageRole.ASSISTANT,
                          List.of(new TextMessageContent(response.text()))),
                      new AssistantMessageMetadata(response.stopReason(), response.usage()),
                      null),
                  T1));
          tx.insertEntry(
              new Entry(
                  turn2EndId,
                  sessionId,
                  turn2AssistantId,
                  new TurnEndPayload(turn2StartId, TurnEndOutcome.COMPLETED, false, null, null),
                  T1));

          tx.insertThread(
              new ThreadState(
                  threadId,
                  sessionId,
                  null,
                  turn2EndId,
                  CREATION_REQUEST_HASH,
                  "main",
                  ThreadYoloPolicy.root(false),
                  ThreadExecutionControl.RUNNABLE,
                  0L,
                  1L,
                  0L,
                  T1,
                  T1));

          return new ClosedTurnBaseline(
              sessionId,
              rootEntryId,
              turn2StartId,
              turn2UserId,
              turn2AssistantId,
              turn2EndId,
              threadId);
        });
  }

  private static TurnStartPayload resolvedInputTurnStart(UUID ownerThreadId) {
    return new TurnStartPayload(
        TurnStartReason.INPUT,
        branchSettings(),
        ownerThreadId,
        CONTEXT_WINDOW,
        MAX_OUTPUT_TOKENS,
        null);
  }

  private UUID seedUserCommand(UUID threadId, String text) {
    return store.transaction(
        tx -> {
          ThreadState thread = tx.lockThread(threadId).orElseThrow();
          UUID idempotencyKey = tx.nextId();
          long seq = thread.nextCommandSequence();
          UserMessageCommandPayload payload =
              new UserMessageCommandPayload(AgentMessage.user(text));
          ThreadCommand command =
              new ThreadCommand(
                  threadId,
                  seq,
                  payload,
                  idempotencyKey,
                  CREATION_REQUEST_HASH,
                  null,
                  null,
                  null,
                  T1);
          tx.insertCommands(List.of(command));
          tx.updateThread(thread.reserveCommandSequences(1, T1));
          return idempotencyKey;
        });
  }

  private void requestThreadWork(UUID threadId) {
    store.transaction(
        tx -> {
          tx.lockThread(threadId).orElseThrow();
          tx.requestWork(new WorkTarget(WorkTargetType.THREAD, threadId), T1);
          return null;
        });
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
    ThreadSnapshot current = snapshot(threadId);
    if (current.model() != null && current.model().status() == ModelInvocationStatus.READY) {
      startModel(threadId);
    }
    store.transaction(
        tx -> {
          ThreadState thread = ThreadLifecycleCoordinator.lockThreadWithAncestors(tx, threadId);
          UUID turn = tx.loadEntryPath(thread.headEntryId()).openTurnStart().orElseThrow().id();
          ModelInvocation model = tx.findModelInvocationByTurn(threadId, turn).orElseThrow();
          assertEquals(ModelInvocationStatus.RUNNING, model.status());
          tx.lockModelInvocation(model.id());
          ProviderResponse response =
              new ProviderResponse(
                  text,
                  "",
                  List.of(),
                  GenerationStopReason.COMPLETE,
                  new ModelUsage(100L, 20L, 0L, 0L, 0L, 0L, 120L),
                  "req-compaction-child",
                  null,
                  "{}");
          tx.updateModelInvocation(model.succeed(response, T1));
          tx.requestWork(new WorkTarget(WorkTargetType.THREAD, threadId), T1);
          return null;
        });
  }

  private ThreadSnapshot snapshot(UUID threadId) {
    return runtime.getThreadSnapshot(threadId);
  }

  private EntryPath path(UUID threadId) {
    return store.transaction(
        tx -> {
          ThreadState thread = tx.findThread(threadId).orElseThrow();
          return tx.loadEntryPath(thread.headEntryId());
        });
  }

  private boolean hasWork(WorkTargetType type, UUID targetId) {
    return store.<Boolean>transaction(
        tx -> tx.findWork(new WorkTarget(type, targetId)).isPresent());
  }

  private ThreadJoin findJoin(UUID invocationId) {
    return store.transaction(tx -> tx.findJoin(invocationId)).orElseThrow();
  }

  private Optional<ModelInvocation> findModelInvocationByTurn(UUID threadId, UUID turnStartId) {
    return store.transaction(tx -> tx.findModelInvocationByTurn(threadId, turnStartId));
  }

  private static ModelRequestSpec requestFor(BranchSettings settings) {
    return new ModelRequestSpec(
        ProviderType.OPENAI,
        new UUID(0L, 1L),
        new ModelDescriptor(
            settings.model().providerName(),
            settings.model().modelName(),
            settings.model().modelName(),
            Set.of(ModelInputModality.TEXT),
            true,
            true),
        new ModelVariant(settings.model().variant()),
        1024,
        "Test system instruction.",
        List.of(),
        List.of(),
        ProviderCacheControl.none());
  }
}
