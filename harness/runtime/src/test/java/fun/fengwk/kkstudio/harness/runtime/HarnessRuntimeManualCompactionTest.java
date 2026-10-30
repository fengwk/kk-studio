package fun.fengwk.kkstudio.harness.runtime;

import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.loadCommandsByThread;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.model.ModelCost;
import fun.fengwk.kkstudio.harness.model.ModelUsage;
import fun.fengwk.kkstudio.harness.model.provider.GenerationStopReason;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionConfig;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionTrigger;
import fun.fengwk.kkstudio.harness.runtime.driver.CompactionResolution;
import fun.fengwk.kkstudio.harness.runtime.driver.TurnResolution;
import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnEndOutcome;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnStartReason;
import fun.fengwk.kkstudio.harness.runtime.history.AssistantError;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.history.MessagePayload;
import fun.fengwk.kkstudio.harness.runtime.history.RootPayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndPayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnStartPayload;
import fun.fengwk.kkstudio.harness.runtime.join.JoinDelivery;
import fun.fengwk.kkstudio.harness.runtime.join.ThreadJoin;
import fun.fengwk.kkstudio.harness.runtime.port.TurnResolveTransientException;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.AssistantMessageMetadata;
import fun.fengwk.kkstudio.harness.runtime.session.Session;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.testing.InMemoryHarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.testing.SessionFirstThreadLockStore;
import fun.fengwk.kkstudio.harness.runtime.testing.FakeAgentDriver;
import fun.fengwk.kkstudio.harness.runtime.thread.SpawnTrigger;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadConstraints;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadExecutionControl;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.thread.command.CustomMessageCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.SetModelCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandPayloadJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandState;
import fun.fengwk.kkstudio.harness.runtime.work.Work;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTarget;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTargetType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** HarnessRuntime 手动压缩入口：availability 与单事务 candidate 构造 / resolve / 提交。 */
class HarnessRuntimeManualCompactionTest {

  private static final Instant NOW = Instant.parse("2026-07-01T00:00:00Z");
  private static final int CONTEXT_WINDOW = 100_000;
  private static final int MAX_OUTPUT_TOKENS = 16_384;
  private static final ModelUsage USAGE =
      new ModelUsage(50_000L, 2L, 0L, 0L, Map.of(), 0L, 50_002L);
  private static final ModelCost COST =
      new ModelCost(
          "USD",
          BigDecimal.ZERO,
          BigDecimal.ZERO,
          BigDecimal.ZERO,
          BigDecimal.ZERO,
          BigDecimal.ZERO,
          BigDecimal.ZERO);

  @Test
  void availabilityDisablesSmallAndBusyThreads() {
    // 小上下文低于 manualMinimum；open turn 即使无 Model 行也属于不安全控制面。
    Fixture smallFixture = fixture();
    ClosedTurnBaseline small = seedSmallClosedTurn(smallFixture.store);
    ManualCompactionAvailability smallAvailability =
        smallFixture.runtime.manualCompactionAvailability(small.threadId());
    assertFalse(smallAvailability.available());
    assertEquals(
        ManualCompactionAvailability.DisabledReason.BELOW_MINIMUM,
        smallAvailability.disabledReason());

    Fixture busyFixture = fixture();
    HarnessRuntimeTestSupport.TurnBaseline busy =
        HarnessRuntimeTestSupport.seedOpenTurn(busyFixture.store);
    ManualCompactionAvailability busyAvailability =
        busyFixture.runtime.manualCompactionAvailability(busy.threadId());
    assertFalse(busyAvailability.available());
    assertEquals(
        ManualCompactionAvailability.DisabledReason.THREAD_BUSY, busyAvailability.disabledReason());
  }

  @Test
  void compactThreadCommitsManualTurnAndModelWorkWithoutMailboxCommand() {
    // Runtime 同步提交 TURN_START + ModelInvocation；模型执行仍由既有 MODEL Work 异步驱动。
    Fixture fixture = fixture();
    ClosedTurnBaseline baseline = seedCompactionReadyClosedTurn(fixture.store);
    fixture.resolver.autoConsistent = true;
    assertTrue(fixture.runtime.manualCompactionAvailability(baseline.threadId()).available());

    CompactThreadResult result =
        fixture.runtime.compactThread(new CompactThreadCommand(baseline.threadId(), 0));

    assertEquals(1L, result.thread().version());
    assertNotNull(result.childThreadId());
    EntryPath path = path(fixture.store, baseline.threadId());
    TurnStartPayload start = (TurnStartPayload) path.head().payload();
    assertEquals(result.turnStartEntryId(), path.head().id());
    assertEquals(CompactionTrigger.MANUAL, start.compaction().trigger());
    assertEquals(
        HarnessRuntimeTestSupport.builtinExecutionModel(), start.compaction().executionModel());
    assertNotNull(start.contextWindow());
    assertNotNull(start.maxOutputTokens());
    assertNotNull(
        work(fixture.store, new WorkTarget(WorkTargetType.THREAD, result.childThreadId())));
    assertNull(work(fixture.store, new WorkTarget(WorkTargetType.THREAD, baseline.threadId())));
  }

  @Test
  void compactThreadLocksSessionBeforeThread() {
    // availability 与单事务 compact 均经过守卫，防止 Entry 外键锁与 Session 深删除形成 Thread -> Session 逆序。
    InMemoryHarnessStore store = new InMemoryHarnessStore();
    Fixture fixture = fixture(store, SessionFirstThreadLockStore.wrap(store));
    ClosedTurnBaseline baseline = seedCompactionReadyClosedTurn(store);
    fixture.resolver.autoConsistent = true;

    CompactThreadResult result =
        fixture.runtime.compactThread(new CompactThreadCommand(baseline.threadId(), 0));

    assertNotNull(result.childThreadId());
  }

  @Test
  void compactThreadUsesTypedVersionAndAvailabilityConflicts() {
    Fixture staleFixture = fixture();
    ClosedTurnBaseline stale = seedCompactionReadyClosedTurn(staleFixture.store);
    HarnessRuntimeConflictException staleError =
        assertThrows(
            HarnessRuntimeConflictException.class,
            () ->
                staleFixture.runtime.compactThread(new CompactThreadCommand(stale.threadId(), 1)));
    assertEquals(HarnessRuntimeConflictException.Reason.STALE_VERSION, staleError.reason());
    assertEquals(9, path(staleFixture.store, stale.threadId()).entries().size());

    Fixture busyFixture = fixture();
    HarnessRuntimeTestSupport.TurnBaseline busy =
        HarnessRuntimeTestSupport.seedOpenTurn(busyFixture.store);
    HarnessRuntimeConflictException busyError =
        assertThrows(
            HarnessRuntimeConflictException.class,
            () -> busyFixture.runtime.compactThread(new CompactThreadCommand(busy.threadId(), 0)));
    assertEquals(
        HarnessRuntimeConflictException.Reason.MANUAL_COMPACTION_UNAVAILABLE, busyError.reason());
  }

  @Test
  void resolverRejectionClosesManualTurnWithoutModelInvocation() {
    // Resolver 业务拒绝仍形成完整 failed turn；没有半开的 manual intent。
    Fixture fixture = fixture();
    ClosedTurnBaseline baseline = seedCompactionReadyClosedTurn(fixture.store);
    fixture.resolver.results.add(
        new TurnResolution.Rejected(new AssistantError("CONFIG_ERROR", "model unavailable")));

    CompactThreadResult result =
        fixture.runtime.compactThread(new CompactThreadCommand(baseline.threadId(), 0));

    assertNull(result.childThreadId());
    assertEquals(1L, result.thread().version());
    EntryPath path = path(fixture.store, baseline.threadId());
    assertEquals(12, path.entries().size());
    TurnStartPayload start = (TurnStartPayload) path.entries().get(9).payload();
    assertEquals(CompactionTrigger.MANUAL, start.compaction().trigger());
    TurnEndPayload end = (TurnEndPayload) path.head().payload();
    assertEquals(TurnEndOutcome.FAILED, end.outcome());
  }

  @Test
  void threadMustExistForAvailabilityAndCompact() {
    // Thread 不存在时，availability 与 compact 都由根控制面确定性返回 NotFound。
    Fixture fixture = fixture();
    UUID missing = UUID.randomUUID();
    assertThrows(
        HarnessRuntimeNotFoundException.class,
        () -> fixture.runtime.manualCompactionAvailability(missing));
    assertThrows(
        HarnessRuntimeNotFoundException.class,
        () -> fixture.runtime.compactThread(new CompactThreadCommand(missing, 0)));
  }

  /**
   * Stop 后 Thread 仍是完整历史，旧实现会把它当作可手动压缩：availability 返回 enabled，compactThread 还会写 COMPACTION Turn
   * 并调用 resolver。真实 Stop 路径的回归必须证明守卫发生在 resolve/写入之前，且重放 Stop 不改变结论。
   */
  @Test
  void stoppedThreadRejectsManualCompactionBeforeResolveOrWrites() {
    Fixture fixture = fixture();
    ClosedTurnBaseline baseline = seedCompactionReadyClosedTurn(fixture.store);
    fixture.resolver.autoConsistent = true;
    UUID stopRequestId = UUID.randomUUID();
    StopResult stopped =
        fixture.runtime.stop(new StopCommand(baseline.threadId(), stopRequestId, 0));
    assertFalse(stopped.replayed());
    assertEquals(ThreadExecutionControl.STOPPED, stopped.thread().executionControl());

    EntryPath stoppedPath = path(fixture.store, baseline.threadId());
    UUID stoppedHeadId = stoppedPath.head().id();
    long stoppedVersion = stopped.thread().version();
    int stoppedEntryCount = stoppedPath.entries().size();

    ManualCompactionAvailability availability =
        fixture.runtime.manualCompactionAvailability(baseline.threadId());
    assertFalse(availability.available());
    assertEquals(
        ManualCompactionAvailability.DisabledReason.THREAD_STOPPED, availability.disabledReason());

    HarnessRuntimeConflictException rejected =
        assertThrows(
            HarnessRuntimeConflictException.class,
            () ->
                fixture.runtime.compactThread(
                    new CompactThreadCommand(baseline.threadId(), stoppedVersion)));
    assertEquals(
        HarnessRuntimeConflictException.Reason.MANUAL_COMPACTION_UNAVAILABLE, rejected.reason());
    assertEquals(0, fixture.resolver.calls, "STOPPED must be rejected before turn resolution");
    assertUnchanged(fixture, baseline.threadId(), stoppedHeadId, stoppedVersion, stoppedEntryCount);

    // 同一 stopRequestId 的 replay 不写新事实；结论仍是 STOPPED，重复 compact 仍不触发 resolver。
    StopResult replay =
        fixture.runtime.stop(new StopCommand(baseline.threadId(), stopRequestId, stoppedVersion));
    assertTrue(replay.replayed());
    assertEquals(stoppedVersion, replay.thread().version());
    assertEquals(
        ManualCompactionAvailability.DisabledReason.THREAD_STOPPED,
        fixture.runtime.manualCompactionAvailability(baseline.threadId()).disabledReason());
    assertThrows(
        HarnessRuntimeConflictException.class,
        () ->
            fixture.runtime.compactThread(
                new CompactThreadCommand(baseline.threadId(), stoppedVersion)));
    assertEquals(0, fixture.resolver.calls);
    assertUnchanged(fixture, baseline.threadId(), stoppedHeadId, stoppedVersion, stoppedEntryCount);
  }

  private static void assertUnchanged(
      Fixture fixture, UUID threadId, UUID headEntryId, long version, int entryCount) {
    EntryPath after = path(fixture.store, threadId);
    assertEquals(headEntryId, after.head().id());
    assertEquals(entryCount, after.entries().size());
    assertEquals(version, thread(fixture.store, threadId).version());
    assertEquals(
        1,
        fixture.store.<Integer>transaction(
            tx -> tx.threads().listBySession(after.root().sessionId()).size()),
        "rejected manual compaction must not create a COMPACTION child thread");
  }

  @Test
  void compactThreadRejectsNullResolverResult() {
    // Resolver 返回 null 违背契约，必须零 durable mutation。
    Fixture fixture = fixture();
    ClosedTurnBaseline baseline = seedCompactionReadyClosedTurn(fixture.store);

    IllegalStateException error =
        assertThrows(
            IllegalStateException.class,
            () -> fixture.runtime.compactThread(new CompactThreadCommand(baseline.threadId(), 0)));
    // FakeAgentDriver 未配置 resolution 时返回 null，Runtime 必须直接拒绝。
    assertEquals("turn resolver returned null for manual compaction", error.getMessage());
    // 零 durable mutation：Thread 版本、Entry 历史与 COMPACTION 子线程都不变。
    assertEquals(0L, thread(fixture.store, baseline.threadId()).version());
    EntryPath path = path(fixture.store, baseline.threadId());
    assertEquals(9, path.entries().size());
    assertEquals(
        1,
        fixture.store.<Integer>transaction(
            tx -> tx.threads().listBySession(path.root().sessionId()).size()),
        "rejected manual compaction must not create a COMPACTION child thread");
  }

  @Test
  void resolverRunsInsideTheSameCompactionTransaction() {
    // 嵌套 store transaction 会被明确拒绝：resolver 能在 onResolve 中观察到活动事务，证明 candidate 构造与 resolve 同事务。
    Fixture fixture = fixture();
    ClosedTurnBaseline baseline = seedCompactionReadyClosedTurn(fixture.store);
    fixture.resolver.autoConsistent = true;
    fixture.resolver.onResolve =
        () ->
            assertThrows(IllegalStateException.class, () -> fixture.store.transaction(tx -> null));

    CompactThreadResult result =
        fixture.runtime.compactThread(new CompactThreadCommand(baseline.threadId(), 0));

    assertNotNull(result.childThreadId());
    assertEquals(
        CompactionTrigger.MANUAL,
        ((TurnStartPayload) path(fixture.store, baseline.threadId()).head().payload())
            .compaction()
            .trigger());
  }

  @Test
  void typedSqlFailureRollsBackCompactionTurnChildThreadAndCommands() {
    // SQL 失败可能已污染当前事务：typed transient 整体回滚，绝不落 COMPACTION/child/Command marker/Work。
    Fixture fixture = fixture();
    ClosedTurnBaseline baseline = seedCompactionReadyClosedTurn(fixture.store);
    seedCommand(
        fixture.store,
        baseline.threadId(),
        new CustomMessageCommandPayload(userMessage("deferred input")));
    long version = thread(fixture.store, baseline.threadId()).version();
    fixture.resolver.onResolve =
        () -> {
          throw new TurnResolveTransientException("sql failed", new RuntimeException("db down"));
        };

    assertThrows(
        TurnResolveTransientException.class,
        () ->
            fixture.runtime.compactThread(new CompactThreadCommand(baseline.threadId(), version)));

    EntryPath after = path(fixture.store, baseline.threadId());
    assertEquals(9, after.entries().size());
    assertEquals(baseline.turnEndEntryId(), after.head().id());
    ThreadState thread = thread(fixture.store, baseline.threadId());
    assertEquals(version, thread.version());
    assertEquals(
        1,
        fixture.store.<Integer>transaction(
            tx -> tx.threads().listBySession(after.root().sessionId()).size()));
    List<ThreadCommand> commands =
        fixture.store.<List<ThreadCommand>>transaction(
            tx -> loadCommandsByThread(tx, baseline.threadId()));
    assertEquals(1, commands.size());
    assertEquals(ThreadCommandState.QUEUED, commands.getFirst().state());
    assertNull(work(fixture.store, new WorkTarget(WorkTargetType.THREAD, baseline.threadId())));
  }

  @Test
  void resolverRejectionWakesThreadOnlyForDeferredUserDemand() {
    // 任何 queued 消息命令（USER_MESSAGE 或 USER CUSTOM_MESSAGE，含运行时 reminder）都保留 INPUT obligation；
    // SET_* 配置命令不构成 user demand。
    Fixture userFixture = fixture();
    ClosedTurnBaseline userBaseline = seedCompactionReadyClosedTurn(userFixture.store);
    seedCommand(
        userFixture.store,
        userBaseline.threadId(),
        new CustomMessageCommandPayload(userMessage("deferred input")));
    userFixture.resolver.results.add(rejection());
    long userVersion = thread(userFixture.store, userBaseline.threadId()).version();
    userFixture.runtime.compactThread(
        new CompactThreadCommand(userBaseline.threadId(), userVersion));
    assertNotNull(
        work(userFixture.store, new WorkTarget(WorkTargetType.THREAD, userBaseline.threadId())));

    Fixture configFixture = fixture();
    ClosedTurnBaseline configBaseline = seedCompactionReadyClosedTurn(configFixture.store);
    seedCommand(
        configFixture.store,
        configBaseline.threadId(),
        new SetModelCommandPayload(ModelSelection.model("model-b", "v2")));
    configFixture.resolver.results.add(rejection());
    long configVersion = thread(configFixture.store, configBaseline.threadId()).version();
    configFixture.runtime.compactThread(
        new CompactThreadCommand(configBaseline.threadId(), configVersion));
    assertNull(
        work(
            configFixture.store, new WorkTarget(WorkTargetType.THREAD, configBaseline.threadId())));
  }

  private static Fixture fixture() {
    InMemoryHarnessStore store = new InMemoryHarnessStore();
    return fixture(store, store);
  }

  private static Fixture fixture(InMemoryHarnessStore store, HarnessStore runtimeStore) {
    FakeTurnResolver resolver = new FakeTurnResolver();
    HarnessRuntime runtime =
        new HarnessRuntime(
            runtimeStore, new FakeAgentDriver(resolver::resolve), () -> CompactionConfig.DEFAULT);
    return new Fixture(store, resolver, runtime);
  }

  private static TurnResolution.Rejected rejection() {
    return new TurnResolution.Rejected(new AssistantError("CONFIG_ERROR", "model unavailable"));
  }

  private static UUID seedCommand(
      InMemoryHarnessStore store, UUID threadId, ThreadCommandPayload payload) {
    return store.transaction(
        tx -> {
          ThreadState thread = tx.threads().lock(threadId).orElseThrow();
          UUID idempotencyKey = tx.nextId();
          tx.commands()
              .insert(
                  List.of(
                      new ThreadCommand(
                          threadId,
                          thread.nextCommandSequence(),
                          payload,
                          idempotencyKey,
                          ThreadCommandPayloadJsonCodec.requestHash(payload),
                          null,
                          null,
                          null,
                          NOW)));
          tx.threads().update(thread.reserveCommandSequences(1, NOW));
          return idempotencyKey;
        });
  }

  private static ClosedTurnBaseline seedCompactionReadyClosedTurn(InMemoryHarnessStore store) {
    return seedClosedTurn(store, 50_000, 90_000);
  }

  private static ClosedTurnBaseline seedSmallClosedTurn(InMemoryHarnessStore store) {
    return seedClosedTurn(store, 10, 10);
  }

  private static ClosedTurnBaseline seedClosedTurn(
      InMemoryHarnessStore store, int historicalChars, int currentChars) {
    return store.transaction(
        tx -> {
          UUID sessionId = tx.nextId();
          UUID rootId = tx.nextId();
          UUID firstStartId = tx.nextId();
          UUID firstUserId = tx.nextId();
          UUID firstAssistantId = tx.nextId();
          UUID firstEndId = tx.nextId();
          UUID secondStartId = tx.nextId();
          UUID secondUserId = tx.nextId();
          UUID secondAssistantId = tx.nextId();
          UUID secondEndId = tx.nextId();
          UUID threadId = tx.nextId();
          tx.sessions().insert(new Session(sessionId, "session-" + sessionId, NOW));
          tx.entries()
              .insert(
                  new Entry(
                      rootId,
                      sessionId,
                      null,
                      new RootPayload(HarnessRuntimeTestSupport.settings()),
                      NOW));
          tx.entries().insert(turnStart(firstStartId, sessionId, rootId, threadId));
          tx.entries()
              .insert(
                  message(
                      firstUserId,
                      sessionId,
                      firstStartId,
                      userMessage("historical user " + "h".repeat(historicalChars)),
                      null));
          tx.entries()
              .insert(
                  message(
                      firstAssistantId,
                      sessionId,
                      firstUserId,
                      assistantMessage("historical assistant " + "a".repeat(historicalChars)),
                      metadata()));
          tx.entries().insert(turnEnd(firstEndId, sessionId, firstAssistantId, firstStartId));
          tx.entries().insert(turnStart(secondStartId, sessionId, firstEndId, threadId));
          tx.entries()
              .insert(
                  message(
                      secondUserId,
                      sessionId,
                      secondStartId,
                      userMessage("user asks a very long question" + "x".repeat(currentChars)),
                      null));
          tx.entries()
              .insert(
                  message(
                      secondAssistantId,
                      sessionId,
                      secondUserId,
                      assistantMessage("assistant reply"),
                      metadata()));
          tx.entries().insert(turnEnd(secondEndId, sessionId, secondAssistantId, secondStartId));
          tx.threads()
              .insert(
                  new ThreadState(
                      threadId,
                      sessionId,
                      null,
                      secondEndId,
                      HarnessRuntimeTestSupport.CREATION_REQUEST_HASH,
                      "main",
                      threadId,
                      false,
                      SpawnTrigger.USER,
                      ThreadConstraints.NONE,
                      ThreadExecutionControl.RUNNABLE,
                      0L,
                      1,
                      0,
                      NOW,
                      NOW));
          return new ClosedTurnBaseline(threadId, secondEndId);
        });
  }

  private static Entry turnStart(UUID id, UUID sessionId, UUID parentId, UUID threadId) {
    return new Entry(
        id,
        sessionId,
        parentId,
        new TurnStartPayload(
            TurnStartReason.INPUT,
            HarnessRuntimeTestSupport.settings(),
            threadId,
            CONTEXT_WINDOW,
            MAX_OUTPUT_TOKENS,
            null,
            null),
        NOW);
  }

  private static Entry turnEnd(UUID id, UUID sessionId, UUID parentId, UUID turnStartEntryId) {
    return new Entry(
        id,
        sessionId,
        parentId,
        new TurnEndPayload(turnStartEntryId, TurnEndOutcome.COMPLETED, false, null, null),
        NOW);
  }

  private static Entry message(
      UUID id,
      UUID sessionId,
      UUID parentId,
      AgentMessage message,
      AssistantMessageMetadata metadata) {
    return new Entry(id, sessionId, parentId, new MessagePayload(message, metadata, null), NOW);
  }

  private static AgentMessage userMessage(String text) {
    return new AgentMessage(AgentMessageRole.USER, List.of(new TextMessageContent(text)));
  }

  private static AgentMessage assistantMessage(String text) {
    return new AgentMessage(AgentMessageRole.ASSISTANT, List.of(new TextMessageContent(text)));
  }

  private static AssistantMessageMetadata metadata() {
    return new AssistantMessageMetadata(GenerationStopReason.COMPLETE, USAGE);
  }

  private static EntryPath path(InMemoryHarnessStore store, UUID threadId) {
    ThreadState thread = thread(store, threadId);
    return store.transaction(tx -> tx.entries().loadPath(thread.headEntryId()));
  }

  /**
   * 手动入口与自动压缩共用同一条约束 guard：创建约束不允许自动压缩的 Thread（压缩子及其经约束合并继承的后代）一律不可手动压缩，即使该 Thread 自己的委派已经结算（{@code
   * loadIncompleteJoins} 为空）。runtime primitive 自身拒绝并返回明确 receipt，不依赖上层调用方的 root guard。
   */
  @Test
  void availabilityRejectsThreadsInsideCompactionChildTree() {
    Fixture fixture = fixture();
    UUID parentThreadId = HarnessRuntimeTestSupport.seedBaseline(fixture.store).threadId();
    // 压缩子自身（其 COMPACTION Join 未结算）。
    UUID compactionChild =
        seedChildThread(
            fixture.store,
            parentThreadId,
            SpawnTrigger.RUNTIME,
            COMPACTION_CONSTRAINTS,
            JoinDelivery.SETTLE_OWNER_TURN,
            false);
    // 压缩子的后代：自己的 task Join 已结算，只有继承的创建约束能挡住它。
    UUID descendant =
        seedChildThread(
            fixture.store,
            compactionChild,
            SpawnTrigger.MODEL_TOOL,
            COMPACTION_CONSTRAINTS,
            JoinDelivery.NOTIFY_PARENT,
            true);
    assertTrue(
        fixture.store.<Boolean>transaction(tx -> tx.joins().loadIncomplete(descendant).isEmpty()));

    ManualCompactionAvailability descendantAvailability =
        fixture.runtime.manualCompactionAvailability(descendant);
    assertFalse(descendantAvailability.available());
    assertEquals(
        ManualCompactionAvailability.DisabledReason.THREAD_BUSY,
        descendantAvailability.disabledReason());
    HarnessRuntimeConflictException rejected =
        assertThrows(
            HarnessRuntimeConflictException.class,
            () -> fixture.runtime.compactThread(new CompactThreadCommand(descendant, 0)));
    assertEquals(
        HarnessRuntimeConflictException.Reason.MANUAL_COMPACTION_UNAVAILABLE, rejected.reason());

    assertFalse(fixture.runtime.manualCompactionAvailability(compactionChild).available());

    // 该压缩树的父 Thread 只是 Join owner，不在压缩子执行树内：其可用性仍由自身历史决定，不被压缩树牵连。
    ManualCompactionAvailability ownerAvailability =
        fixture.runtime.manualCompactionAvailability(parentThreadId);
    assertFalse(ownerAvailability.available());
    assertEquals(
        ManualCompactionAvailability.DisabledReason.NO_RESOLVED_CONTEXT,
        ownerAvailability.disabledReason());
  }

  /** 压缩子冻结的创建约束：输出上限、禁用 cache、不允许自动压缩；后代经约束合并继承。 */
  private static final ThreadConstraints COMPACTION_CONSTRAINTS =
      new ThreadConstraints(100, true, false);

  /** 手工创建带 source command 与 Join 的 child Thread，用于按创建约束事实验证手动入口 guard。 */
  private static UUID seedChildThread(
      InMemoryHarnessStore store,
      UUID parentThreadId,
      SpawnTrigger trigger,
      ThreadConstraints constraints,
      JoinDelivery delivery,
      boolean matchedJoin) {
    return store.transaction(
        tx -> {
          tx.threads().lock(parentThreadId);
          UUID sessionId = tx.nextId();
          UUID rootEntryId = tx.nextId();
          UUID threadId = tx.nextId();
          UUID commandId = tx.nextId();
          tx.sessions().insert(new Session(sessionId, "child-" + sessionId, NOW));
          tx.entries()
              .insert(
                  new Entry(
                      rootEntryId,
                      sessionId,
                      null,
                      new RootPayload(HarnessRuntimeTestSupport.settings()),
                      NOW));
          List<UUID> ancestors = tx.threads().findAncestorChain(parentThreadId);
          UUID rootThreadId = ancestors.isEmpty() ? parentThreadId : ancestors.getLast();
          tx.threads()
              .insert(
                  new ThreadState(
                      threadId,
                      sessionId,
                      parentThreadId,
                      rootEntryId,
                      HarnessRuntimeTestSupport.CREATION_REQUEST_HASH,
                      "child-" + delivery.name().toLowerCase(),
                      rootThreadId,
                      false,
                      trigger,
                      constraints,
                      ThreadExecutionControl.RUNNABLE,
                      0L,
                      2L,
                      0L,
                      NOW,
                      NOW));
          CustomMessageCommandPayload payload =
              new CustomMessageCommandPayload(AgentMessage.user("child instruction"));
          String requestHash = ThreadCommandPayloadJsonCodec.requestHash(payload);
          tx.commands()
              .insert(
                  List.of(
                      new ThreadCommand(
                          threadId, 1L, payload, commandId, requestHash, null, null, null, NOW)));
          ThreadJoin join =
              new ThreadJoin(
                  commandId,
                  requestHash,
                  parentThreadId,
                  threadId,
                  1L,
                  "child",
                  null,
                  0L,
                  null,
                  null,
                  null,
                  NOW,
                  NOW,
                  delivery,
                  null);
          tx.joins().insert(join);
          if (matchedJoin) {
            ThreadJoin matched = join.match(rootEntryId, null, NOW);
            if (delivery == JoinDelivery.NOTIFY_PARENT) {
              matched = matched.delivered(1L, NOW);
            }
            tx.joins().update(matched);
          }
          return threadId;
        });
  }

  private static ThreadState thread(InMemoryHarnessStore store, UUID threadId) {
    return store.transaction(tx -> tx.threads().find(threadId).orElseThrow());
  }

  private static Work work(InMemoryHarnessStore store, WorkTarget target) {
    return store.transaction(tx -> tx.work().find(target).orElse(null));
  }

  private record Fixture(
      InMemoryHarnessStore store, FakeTurnResolver resolver, HarnessRuntime runtime) {}

  private record ClosedTurnBaseline(UUID threadId, UUID turnEndEntryId) {}

  /** Scripted resolver：可返回显式结果、在 resolve 中触发竞态，或按冻结 compaction facts 生成一致请求。 */
  private static final class FakeTurnResolver {
    private final Deque<TurnResolution> results = new ArrayDeque<>();
    private Runnable onResolve;
    private boolean autoConsistent;
    private int calls;

    TurnResolution resolve() {
      calls++;
      if (onResolve != null) {
        onResolve.run();
      }
      if (autoConsistent) {
        return new TurnResolution.Compaction(
            new CompactionResolution(
                HarnessRuntimeTestSupport.builtinExecutionModel(),
                4096L,
                CONTEXT_WINDOW,
                MAX_OUTPUT_TOKENS,
                new BranchSettings(
                    "compaction",
                    HarnessRuntimeTestSupport.builtinExecutionModel(),
                    HarnessRuntimeTestSupport.settings().environmentName(),
                    null)));
      }
      return results.poll();
    }
  }
}
